package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** SSE framing is independent from model tokens; no thoughts, inputs or headers are forwarded. */
@Component
@RequiredArgsConstructor
public class DifyStreamTransport {
    private final ObjectMapper mapper;
    private final RunTraceService traces;
    private final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2, r -> { var t=new Thread(r,"dify-stream-watchdog");t.setDaemon(true);return t; });
    @PreDestroy void close() { timer.shutdownNow(); }

    public Result execute(HttpRequest request, String key, String user, boolean workflow, int timeoutSeconds,
                          UUID run, String scope, Consumer<DifyClient.DifyEvent> consumer) {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var state=new State();
        try {
            if (traces.cancelled(run)) throw cancelled();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            while(true) {
                if(traces.cancelled(run)) { pending.cancel(true);throw cancelled(); }
                if(System.nanoTime()>deadline) { pending.cancel(true);throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"DIFY_TIMEOUT","等待 Dify 响应超时"); }
                try { response=pending.get(1,TimeUnit.SECONDS);break; }
                catch(TimeoutException waiting) { /* Allow cancellation before response headers arrive. */ }
            }
            try (var input=response.body()) {
                if(response.statusCode()/100!=2) throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_HTTP_ERROR","Dify 请求失败（HTTP "+response.statusCode()+"），请按运行编号检查服务配置");
                var timedOut=new AtomicBoolean(); var stopped=new AtomicBoolean();
                var watch=timer.scheduleAtFixedRate(() -> {
                    try {
                        if(traces.cancelled(run)) stopped.set(true);
                        if(System.nanoTime()>deadline) timedOut.set(true);
                        if(stopped.get()||timedOut.get()) input.close();
                    } catch(Exception ignored) { /* Request deadline remains independently enforced. */ }
                },1,1,TimeUnit.SECONDS);
                try {
                    consume(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8)),data -> {
                        if(data.equals("[DONE]")) return;
                        try { accept(mapper.readTree(data),state,scope,consumer); }
                        catch(ApiException e) { throw e; }
                        catch(Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_STREAM_INVALID","Dify 流事件格式异常，已保留此前进度"); }
                    });
                } finally {
                    watch.cancel(false);
                    if(stopped.get() || traces.cancelled(run)) {
                        stop(client,request.uri(),key,user,workflow,state.taskId);
                        throw cancelled();
                    }
                    if(timedOut.get()) {
                        stop(client,request.uri(),key,user,workflow,state.taskId);
                        throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"DIFY_TIMEOUT","Dify 执行超过本次时间上限");
                    }
                }
            }
            if(!state.terminal) throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_STREAM_INTERRUPTED","上游连接已结束，但未收到完成标记；本次不能视为成功");
            return new Result(state.answer.toString(),state.conversationId,state.messageId,state.taskId,state.workflowId,state.partial);
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_CONNECTION_FAILED","与 Dify 的连接中断或无法建立，请检查运行过程与后端服务"); }
    }

    static void consume(BufferedReader reader, Consumer<String> consumer) throws IOException {
        var data=new StringBuilder(); String line;
        while((line=reader.readLine())!=null) {
            if(line.isEmpty()) {
                if(!data.isEmpty()) { consumer.accept(data.toString()); data.setLength(0); }
            } else if(line.startsWith("data:")) {
                if(!data.isEmpty()) data.append('\n');
                String value=line.substring(5); data.append(value.startsWith(" ")?value.substring(1):value);
                if(data.length()>4_000_000) throw new IOException("SSE frame too large");
            }
        }
        // A truncated final frame must never be treated as a completed event.
        if(!data.isEmpty()) throw new IOException("Incomplete SSE frame");
    }

    void accept(JsonNode event,State state,String scope,Consumer<DifyClient.DifyEvent> consumer) {
        state.conversationId=text(event,"conversation_id",state.conversationId);
        state.messageId=text(event,"message_id",state.messageId);
        state.taskId=text(event,"task_id",state.taskId);
        state.workflowId=text(event,"workflow_run_id",state.workflowId);
        consumer.accept(new DifyClient.DifyEvent("metadata","",state.conversationId,Map.of("taskId",state.taskId,"workflowRunId",state.workflowId)));
        String name=event.path("event").asText(); var data=event.path("data");
        if(name.equals("message")||name.equals("agent_message")) {
            String delta=event.path("answer").asText(""); state.answer.append(delta);
            consumer.accept(new DifyClient.DifyEvent("append",delta,state.conversationId));
        } else if(name.equals("message_replace")) {
            state.answer.setLength(0);state.answer.append(event.path("answer").asText(""));
            consumer.accept(new DifyClient.DifyEvent("replace",state.answer.toString(),state.conversationId));
        } else if(name.equals("message_end")) state.terminal=true;
        else if(name.equals("error")) throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_EXECUTION_FAILED",errorSummary(event.path("message").asText()));
        else if(name.equals("workflow_finished")) {
            String status=data.path("status").asText();
            if(!Set.of("succeeded","partial-succeeded").contains(status))
                throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_WORKFLOW_FAILED",errorSummary(data.path("error").asText()));
            state.partial=status.equals("partial-succeeded");state.terminal=true;
            String answer=answer(data.path("outputs"));
            var outputs=data.path("outputs");
            if(answer.isBlank() && outputs.isObject()) {
                var fields=outputs.fields();
                while(fields.hasNext()) {
                    var field=fields.next();
                    if(field.getKey().startsWith("error")&&!field.getValue().asText("").isBlank())
                        throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_SOURCE_FAILED","情报子工作流返回错误出口，不代表查询成功");
                }
            }
            if(!answer.isBlank()) { state.answer.setLength(0);state.answer.append(answer);consumer.accept(new DifyClient.DifyEvent("replace",answer,state.conversationId)); }
        }
        if(name.equals("node_finished")) {
            var outputs=data.path("outputs");
            if(outputs.has("f_unified_ioc_list_json")) {
                var evidence=new LinkedHashMap<String,Object>();
                for(String field:List.of("f_unified_ioc_list_json","f_unified_risk_level","f_unified_risk_rank","f_unified_evidence_count","f_llm_evidence_items_json"))
                    if(outputs.has(field)) evidence.put(field,outputs.get(field));
                consumer.accept(new DifyClient.DifyEvent("evidence","",state.conversationId,evidence));
            }
        }
        if(Set.of("workflow_started","workflow_finished","node_started","node_finished","iteration_started","iteration_completed","loop_started","loop_completed").contains(name)) {
            if(name.equals("workflow_started")&&scope.equals("main")) consumer.accept(DifyClient.progress("connection","Dify 已连接，工作流开始执行","COMPLETED"));
            String status=name.endsWith("started")?"RUNNING":state.partial&&name.equals("workflow_finished")?"WARNING":"COMPLETED";
            String rawStatus=data.path("status").asText();
            if(Set.of("failed","exception","stopped").contains(rawStatus)) status="FAILED";
            String title=data.path("title").asText(name.startsWith("workflow")?"Dify 工作流":name.startsWith("iteration")?"批量迭代":"分析节点");
            if(status.equals("FAILED")) title += "："+errorSummary(data.path("error").asText());
            String execution=data.path("id").asText(data.path("node_id").asText("workflow"));
            var details=new LinkedHashMap<String,Object>();
            details.put("nodeExecutionId",scope+":"+state.workflowId+":"+execution);
            details.put("nodeId",data.path("node_id").asText(""));details.put("parentId",scope);
            details.put("displayName",RunTraceService.safe(title,120));details.put("status",status);
            details.put("stage",data.path("node_type").asText("workflow"));
            if(data.has("elapsed_time")) details.put("elapsedMs",Math.round(data.path("elapsed_time").asDouble()*1000));
            consumer.accept(new DifyClient.DifyEvent("trace",title,state.conversationId,details));
        }
    }

    private static String text(JsonNode n,String key,String fallback) { return n.hasNonNull(key)?n.path(key).asText():fallback; }
    static String errorSummary(String raw) {
        String value=raw.toLowerCase(Locale.ROOT);
        if(value.contains("pluginnotfound")||value.contains("plugin not found")) return "Dify 找不到所需插件，请检查 SIR/工具插件的安装与绑定";
        if(value.contains("timeout")||value.contains("timed out")) return "上游节点等待超时";
        if(value.contains("429")||value.contains("rate limit")) return "上游服务限流，请稍后重试";
        if(value.contains("unauthorized")||value.contains("invalid api key")) return "上游服务鉴权失败，请检查对应凭据配置";
        return "Dify 节点执行失败，请按运行编号检查上游日志";
    }
    static String answer(JsonNode outputs) {
        for(String key:List.of("answer","final_output","result","result2","text","text1","output","content")) {
            var value=outputs.path(key);
            if(value.isTextual()&&!value.asText().isBlank()) return value.asText();
            if(value.isObject()) { var nested=answer(value);if(!nested.isBlank()) return nested; }
        }
        return "";
    }
    private void stop(HttpClient client,URI uri,String key,String user,boolean workflow,String task) {
        if(task.isBlank()) return;
        try {
            String base=uri.toString().replaceAll("/(chat-messages|workflows/run)$","");
            var request=HttpRequest.newBuilder(URI.create(base+(workflow?"/workflows/tasks/":"/chat-messages/")+task+"/stop"))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("user",user)))).build();
            client.send(request,HttpResponse.BodyHandlers.discarding());
        } catch(Exception ignored) { /* Local cancellation does not guarantee cancellation of an external provider job. */ }
    }
    private static ApiException cancelled() { return new ApiException(HttpStatus.CONFLICT,"RUN_CANCELLED","已停止本平台等待并尝试停止 Dify；已提交的第三方分析可能继续执行"); }
    static class State { final StringBuilder answer=new StringBuilder();String conversationId="",messageId="",taskId="",workflowId="";boolean terminal,partial; }
    public record Result(String answer,String conversationId,String messageId,String taskId,String workflowRunId,boolean partial) {}
}
