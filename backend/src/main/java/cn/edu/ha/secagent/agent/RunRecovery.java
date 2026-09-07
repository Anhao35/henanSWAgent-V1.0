package cn.edu.ha.secagent.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Single-instance deployment recovery. Disable when introducing a distributed task worker. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="app.execution.recover-on-startup",havingValue="true",matchIfMissing=true)
public class RunRecovery {
    private final JdbcTemplate jdbc;
    private final RunTraceService traces;
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        var ids=jdbc.queryForList("SELECT BIN_TO_UUID(r.id) FROM agent_runs r JOIN run_contexts x ON x.run_id=r.id WHERE r.status IN ('RUNNING','CANCEL_REQUESTED')",String.class);
        for(var id:ids) {
            var run=UUID.fromString(id);
            traces.terminal(run,"INTERRUPTED");
            traces.stage(run,"recovery","后端重启，中断前的结果已保留；上游任务是否继续执行需复核","INTERRUPTED");
        }
    }
}
