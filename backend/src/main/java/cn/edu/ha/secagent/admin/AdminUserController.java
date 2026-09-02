package cn.edu.ha.secagent.admin;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserService service;

    @GetMapping
    Object list(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.list(actor.id());
    }

    @PatchMapping("/{userId}/status")
    Object updateStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                        @PathVariable UUID userId,
                        @Valid @RequestBody AdminDtos.UpdateStatusRequest body) {
        return service.updateStatus(actor.id(), userId, body.status());
    }

    @PatchMapping("/{userId}/role")
    Object updateRole(@AuthenticationPrincipal AuthenticatedUser actor,
                      @PathVariable UUID userId,
                      @Valid @RequestBody AdminDtos.UpdateRoleRequest body) {
        return service.updateRole(actor.id(), userId, body.role());
    }
}

