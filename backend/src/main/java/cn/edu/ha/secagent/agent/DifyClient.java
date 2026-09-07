package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.conversation.AttachmentService;
import cn.edu.ha.secagent.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class DifyClient {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectStorageService storageService;
    private final AttachmentTextExtractor extractor;
    private final DifyStreamTransport transport;
    @Value("${app.dify.routed-api-key:}") private String routedApiKey;
    private volatile long contractCheckedAt;

    public String keyFor(String mode) {
        if(routedApiKey!=null&&!routedApiKey.isBlank()) { verifyRoutingContract(); return routedApiKey; }
        if(!"AUTO".equals(mode)&&!"SECURITY".equals(mode))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"ROUTING_NOT_CONFIGURED","安全分流工作流尚未发布：请导入新工作流并配置 DIFY_ROUTED_API_KEY；为避免误查 IOC，本次未发送给旧工作流");
        return properties.dify().apiKey();
    }

    private synchronized void verifyRoutingContract() {
        if(System.currentTimeMillis()-contractCheckedAt<60000) return;
        try {
            var request=HttpRequest.newBuilder(URI.create(properties.dify().baseUrl().replaceAll("/$","")+"/parameters"))
                .timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+routedApiKey).GET().build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2) throw new IllegalStateException();
            var form=objectMapper.readTree(response.body()).path("user_input_form");
            boolean found=false;
            for(var item:form) { var values=item.elements();while(values.hasNext()) if(values.next().path("variable").asText().equals("task_mode")) found=true; }
            if(!found) throw new IllegalStateException();
            contractCheckedAt=System.currentTimeMillis();
        } catch(Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DIFY_ROUTING_CONTRACT","已配置的 Dify 应用未暴露 task_mode 输入，或参数接口不可用；请确认新工作流已发布，本次未执行查询");
        }
    }

    public DifyResult streamChat(String user,String query,String conversationId,List<AttachmentService.AttachmentContent> attachments,
                                UUID run,String mode,Consumer<DifyEvent> consumer) {
        var config=properties.dify(); String key=keyFor(mode);
        if(key==null||key.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DIFY_NOT_CONFIGURED","Agent 服务尚未配置");
        var payload=new LinkedHashMap<String,Object>();
        payload.put("inputs",Map.of("task_mode",mode,"platform_run_id",run.toString()));
        consumer.accept(progress("attachment","读取附件内容","RUNNING"));
        payload.put("query",extractor.enrich(query,attachments));
        payload.put("response_mode","streaming");payload.put("user",user);
        if(conversationId!=null&&!conversationId.isBlank()) payload.put("conversation_id",conversationId);
        try {
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var files=new ArrayList<Map<String,String>>();
            for(var attachment:attachments) {
                if(!attachment.contentType().startsWith("image/")) continue;
                files.add(Map.of("type","image","transfer_method","local_file","upload_file_id",uploadFile(client,key,user,attachment)));
            }
            if(!files.isEmpty()) payload.put("files",files);
            consumer.accept(progress("attachment","附件准备完成（非图片文本最多提取每文件 12,000 字符）","COMPLETED"));
            consumer.accept(progress("connection","连接 Dify 工作流","RUNNING"));
            var request=HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/$","")+"/chat-messages"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds())).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json").header("Accept","text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
            var result=transport.execute(request,key,user,false,config.timeoutSeconds(),run,"main",consumer);
            return new DifyResult(result.answer(),result.conversationId(),result.messageId(),result.taskId(),result.workflowRunId(),result.partial());
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_UNAVAILABLE","Agent 服务不可用，请检查运行过程"); }
    }

    private String uploadFile(HttpClient client,String key,String user,AttachmentService.AttachmentContent attachment) throws Exception {
        var stored=storageService.read(attachment.objectKey());var boundary="----HnSec"+UUID.randomUUID();
        var output=new ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"user\"\r\n\r\n"+user+"\r\n").getBytes(StandardCharsets.UTF_8));
        var filename=attachment.name().replaceAll("[\\r\\n\"]","_");
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+stored.contentType()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(stored.bytes());output.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(properties.dify().baseUrl().replaceAll("/$","")+"/files/upload"))
            .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+key).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()/100!=2) throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_FILE_UPLOAD_ERROR","图片上传到 Dify 失败（HTTP "+response.statusCode()+"）");
        String id=objectMapper.readTree(response.body()).path("id").asText();
        if(id.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY,"DIFY_FILE_UPLOAD_ERROR","Dify 未返回附件 ID");
        return id;
    }
    public static DifyEvent progress(String id,String name,String status) {
        return new DifyEvent("trace",name,"",Map.of("nodeExecutionId",id,"displayName",name,"status",status,"stage","platform"));
    }
    public record DifyEvent(String type,String content,String conversationId,Map<String,Object> details) {
        public DifyEvent(String type,String content,String conversationId) { this(type,content,conversationId,Map.of()); }
    }
    public record DifyResult(String answer,String conversationId,String messageId,String taskId,String workflowRunId,boolean partial) {}
}
