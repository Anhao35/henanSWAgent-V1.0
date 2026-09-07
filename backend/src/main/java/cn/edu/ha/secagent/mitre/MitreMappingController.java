package cn.edu.ha.secagent.mitre;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/mitre")
@RequiredArgsConstructor
public class MitreMappingController {
    private final MitreMappingService mappingService;

    @PostMapping("/map")
    Object map(@AuthenticationPrincipal AuthenticatedUser user,
               @Valid @RequestBody MitreDtos.MappingRequest request) {
        return mappingService.map(user.id(), request);
    }

    @GetMapping("/history")
    Object history(@AuthenticationPrincipal AuthenticatedUser user,
                   @RequestParam(defaultValue = "30") int limit) {
        return mappingService.history(user.id(), limit);
    }

    @GetMapping("/history/{id}")
    Object detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return mappingService.detail(user.id(), id);
    }
}
