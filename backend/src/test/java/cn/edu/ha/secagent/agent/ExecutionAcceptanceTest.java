package cn.edu.ha.secagent.agent;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against an explicitly selected disposable MySQL schema; no external model calls. */
@EnabledIfSystemProperty(named="acceptance.db",matches=".*henan_agent_acceptance_20260904.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "app.bootstrap-admin.enabled=false","app.registration.require-approval=false",
    "spring.session.redis.namespace=henan:acceptance:session","app.execution.recover-on-startup=false",
    "app.dify.api-key=test-chat","app.dify.routed-api-key=test-routed",
    "app.dify.workflow-keys.ip-vt=test-failing-source","app.dify.workflow-keys.ip-weibu=test-success-source",
    "app.dify.workflow-keys.domain=test-partial-source"
})
class ExecutionAcceptanceTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static HttpServer upstream;
    static final Queue<JsonNode> calls=new ConcurrentLinkedQueue<>();
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url",()->System.getProperty("acceptance.db"));
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.setExecutor(Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;}));
        upstream.createContext("/v1/",exchange->{
            try {
                String path=exchange.getRequestURI().getPath();
                if(path.endsWith("parameters")) {
                    byte[] bytes="{\"user_input_form\":[{\"select\":{\"variable\":\"task_mode\"}}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);return;
                }
                if(path.endsWith("stop")) { exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes());return; }
                var request=JSON.readTree(exchange.getRequestBody());calls.add(request);
                if("Bearer test-failing-source".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(503,-1);return; }
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                var out=exchange.getResponseBody();String wid=UUID.randomUUID().toString();
                send(out,Map.of("event","workflow_started","workflow_run_id",wid,"task_id","task-1","conversation_id","remote-"+wid,"data",Map.of("id",wid)));
                send(out,Map.of("event","node_started","workflow_run_id",wid,"data",Map.of("id","execution-1","node_id","node-1","title","模拟证据查询","node_type","tool")));
                if(request.path("query").asText().equals("slow")) { for(int i=0;i<150;i++){out.write(": heartbeat\n\n".getBytes());out.flush();Thread.sleep(100);} }
                send(out,Map.of("event","message","answer","测试部分回答"));
                if(request.path("query").asText().equals("truncate")) return;
                send(out,Map.of("event","node_finished","workflow_run_id",wid,"data",Map.of("id","execution-1","node_id","node-1","title","模拟证据查询","status","succeeded","outputs",Map.of("f_unified_ioc_list_json","[]","f_unified_risk_level","测试未知","f_unified_evidence_count",0))));
                String terminalStatus="Bearer test-partial-source".equals(exchange.getRequestHeaders().getFirst("Authorization"))?"partial-succeeded":"succeeded";
                send(out,Map.of("event","workflow_finished","workflow_run_id",wid,"data",Map.of("id",wid,"status",terminalStatus,"outputs",Map.of("answer","这是隔离测试返回的回答，不是真实情报。"))));
            } catch(Exception ignored) { } finally {exchange.close();}
        });
        upstream.start();registry.add("app.dify.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1");
    }
    @AfterAll static void stop() { if(upstream!=null) upstream.stop(0); }
    static void send(OutputStream out,Object event) throws Exception {out.write(("data: "+JSON.writeValueAsString(event)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    record Session(HttpClient client,String csrf) {}
    Session user() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf=JSON.readTree(client.send(HttpRequest.newBuilder(URI.create(url("/auth/csrf"))).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
        var session=new Session(client,csrf);String username="test_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        var registration=request(session,"POST","/auth/register",Map.of("username",username,"displayName","验收测试","password","TestOnly!Pass123","email",username+"@example.org","organizationCode","HERCERT"),null);
        assertEquals(201,registration.statusCode(),registration.body());
        assertEquals(200,request(session,"POST","/auth/login",Map.of("login",username,"password","TestOnly!Pass123"),null).statusCode());
        return session;
    }
    String url(String path){return "http://127.0.0.1:"+port+"/api"+path;}
    HttpResponse<String> request(Session s,String method,String path,Object body,String requestId)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(url(path))).header("X-XSRF-TOKEN",s.csrf()).header("Content-Type","application/json");
        if(requestId!=null)builder.header("X-Request-ID",requestId);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return s.client().send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    String conversation(Session s)throws Exception{return JSON.readTree(request(s,"POST","/conversations",Map.of(),null).body()).path("id").asText();}
    List<JsonNode> events(String body)throws Exception{
        var list=new ArrayList<JsonNode>();for(String line:body.split("\n"))if(!line.isBlank())list.add(JSON.readTree(line));return list;
    }
    @Test void authenticatedTraceIsolationReportVersionsAndIdempotency()throws Exception{
        var a=user();var b=user();String conversation=conversation(a),requestId=UUID.randomUUID().toString();
        var response=request(a,"POST","/conversations/"+conversation+"/messages/stream",Map.of("message","test","taskMode","EXPLAIN"),requestId);
        assertEquals(200,response.statusCode(),response.body());var events=events(response.body());
        assertTrue(events.stream().anyMatch(e->e.path("type").asText().equals("done")),response.body());
        String run=events.get(0).path("runId").asText();assertFalse(run.isBlank());
        var snapshot=JSON.readTree(request(a,"GET","/runs/"+run,null,null).body());assertEquals("COMPLETED",snapshot.path("status").asText());
        assertTrue(snapshot.path("events").size()>=4);
        assertEquals(404,request(b,"GET","/runs/"+run,null,null).statusCode());
        assertEquals(404,request(b,"POST","/runs/"+run+"/stop",Map.of(),null).statusCode());
        assertEquals(409,request(a,"POST","/conversations/"+conversation+"/messages/stream",Map.of("message","test"),requestId).statusCode());
        var generated=request(a,"POST","/reports",Map.of("runId",run,"templateId","builtin-detailed-v1","title","验收报告"),null);
        assertEquals(200,generated.statusCode(),generated.body());var report=JSON.readTree(generated.body());String id=report.path("id").asText();
        assertEquals(404,request(b,"GET","/reports/"+id,null,null).statusCode());
        assertEquals(404,request(b,"GET","/reports/"+id+"/export",null,null).statusCode());
        var revision=request(a,"POST","/reports/"+id+"/versions",Map.of("title","人工复核版","content","# 新版本","status","FINAL"),null);
        assertEquals(200,revision.statusCode(),revision.body());var v2=JSON.readTree(revision.body());assertEquals(2,v2.path("version").asInt());
        assertEquals(report.path("snapshotSha256"),v2.path("snapshotSha256"));
        assertEquals("验收报告",JSON.readTree(request(a,"GET","/reports/"+id,null,null).body()).path("title").asText());
        assertEquals(403,request(a,"POST","/reports/templates",Map.of("key","brief","name","不允许发布","sections",List.of("scope")),null).statusCode());
        assertEquals(0,JSON.readTree(request(a,"GET","/runs/"+run+"?after="+snapshot.path("nextSequence").asLong(),null,null).body()).path("events").size());
    }
    @Test void quickQueryRetainsOtherSourcesWhenOneFails()throws Exception{
        var s=user();String conversation=conversation(s);
        var response=request(s,"POST","/conversations/"+conversation+"/queries/stream",Map.of("type","ip","value","8.8.8.8"),UUID.randomUUID().toString());
        var events=events(response.body());var done=events.stream().filter(e->e.path("type").asText().equals("done")).findFirst();
        assertTrue(done.isPresent(),response.body());assertEquals("PARTIAL",done.get().path("status").asText());
        assertTrue(done.get().path("answer").asText().contains("查询失败"));
    }
    @Test void prematureEofIsFailureNotSuccess()throws Exception{
        var s=user();String conversation=conversation(s);
        var response=request(s,"POST","/conversations/"+conversation+"/messages/stream",Map.of("message","truncate","taskMode","EXPLAIN"),UUID.randomUUID().toString());
        var events=events(response.body());assertTrue(events.stream().anyMatch(e->e.path("type").asText().equals("error")),response.body());
        assertFalse(events.stream().anyMatch(e->e.path("type").asText().equals("done")));
    }
    @Test void quickQueryPreservesPartialWorkflowStatus()throws Exception{
        var s=user();String conversation=conversation(s);
        var response=request(s,"POST","/conversations/"+conversation+"/queries/stream",Map.of("type","domain","value","example.org"),UUID.randomUUID().toString());
        var done=events(response.body()).stream().filter(e->e.path("type").asText().equals("done")).findFirst();
        assertTrue(done.isPresent(),response.body());assertEquals("PARTIAL",done.get().path("status").asText());
    }
    @Test void stopClosesSlowUpstreamAndPersistsTerminal()throws Exception{
        var s=user();String conversation=conversation(s);
        var body=Map.of("message","slow","taskMode","EXPLAIN");
        var future=CompletableFuture.supplyAsync(()->{try{return request(s,"POST","/conversations/"+conversation+"/messages/stream",body,UUID.randomUUID().toString());}catch(Exception e){throw new RuntimeException(e);}});
        String run="";
        for(int i=0;i<30;i++) {var list=JSON.readTree(request(s,"GET","/runs?conversationId="+conversation,null,null).body());if(list.size()>0){run=list.get(0).path("id").asText();break;}Thread.sleep(100);}
        assertFalse(run.isBlank());assertEquals(200,request(s,"POST","/runs/"+run+"/stop",Map.of(),null).statusCode());
        var events=events(future.get(15,TimeUnit.SECONDS).body());assertTrue(events.stream().anyMatch(e->e.path("status").asText().equals("CANCELLED")));
        assertEquals("CANCELLED",JSON.readTree(request(s,"GET","/runs/"+run,null,null).body()).path("status").asText());
    }
}
