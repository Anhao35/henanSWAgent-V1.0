package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunTraceController {
    private final RunTraceService traces;
    @GetMapping Object list(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam UUID conversationId) {
        return traces.list(user.id(),conversationId);
    }
    @GetMapping("/{id}") Object get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id,@RequestParam(defaultValue="0") long after) {
        return traces.snapshot(user.id(),id,after);
    }
    @PostMapping("/{id}/stop") Object stop(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id) {
        return traces.cancel(user.id(),id);
    }
}
