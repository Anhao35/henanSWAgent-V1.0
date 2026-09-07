package cn.edu.ha.secagent.agent;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Socket delivery may fail without losing durable execution events. */
public final class RunChannel implements AutoCloseable {
    private static final ScheduledExecutorService CLOCK=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"run-heartbeat");t.setDaemon(true);return t;});
    private final RunTraceService traces;
    private final UUID run;
    private final OutputStream output;
    private final StringBuilder answer=new StringBuilder();
    private final ScheduledFuture<?> heartbeat;
    private boolean connected=true;
    private long lastSave;
    private String taskId="",workflowId="";
    public RunChannel(RunTraceService traces,UUID run,UUID message,String mode,OutputStream output) {
        this.traces=traces;this.run=run;this.output=output;
        send(Map.of("type","accepted","runId",run,"messageId",message,"taskMode",mode));
        stage("accepted","请求已接收并保存","COMPLETED");
        heartbeat=CLOCK.scheduleAtFixedRate(()->send(Map.of("type","heartbeat","runId",run,"timestamp",java.time.Instant.now().toString())),5,5,TimeUnit.SECONDS);
    }
    public void stage(String id,String label,String status) { send(traces.stage(run,id,label,status)); }
    public void event(DifyClient.DifyEvent event) {
        switch(event.type()) {
            case "metadata" -> {
                String task=String.valueOf(event.details().getOrDefault("taskId","")),workflow=String.valueOf(event.details().getOrDefault("workflowRunId",""));
                if(!task.equals(taskId)||!workflow.equals(workflowId)) { traces.upstream(run,task,workflow);taskId=task;workflowId=workflow; }
            }
            case "evidence" -> traces.evidence(run,event.details());
            case "trace" -> send(traces.append(run,event.details()));
            case "append", "replace" -> {
                if(event.type().equals("replace")) answer.setLength(0);
                answer.append(event.content());
                if(System.nanoTime()-lastSave>TimeUnit.SECONDS.toNanos(2)) { traces.partial(run,answer.toString());lastSave=System.nanoTime(); }
                send(Map.of("type",event.type(),"answer",event.content(),"runId",run));
            }
            default -> { }
        }
    }
    public synchronized void send(Map<String,?> value) {
        if(!connected) return;
        try { output.write((traces.json(value)+"\n").getBytes(StandardCharsets.UTF_8));output.flush(); }
        catch(Exception e) { connected=false; }
    }
    public void flushPartial() { if(!answer.isEmpty()) traces.partial(run,answer.toString()); }
    @Override public void close() { heartbeat.cancel(false); }
}
