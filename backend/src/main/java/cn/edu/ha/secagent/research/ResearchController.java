package cn.edu.ha.secagent.research;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchController {
    private final ResearchService researchService;

    @GetMapping("/sources")
    Object sources() {
        return researchService.sources();
    }

    @PostMapping("/search")
    Object search(@AuthenticationPrincipal AuthenticatedUser user,
                  @Valid @RequestBody ResearchDtos.SearchRequest request) {
        return researchService.search(user.id(), request);
    }

    @GetMapping("/history")
    Object history(@AuthenticationPrincipal AuthenticatedUser user,
                   @RequestParam(defaultValue = "30") int limit) {
        return researchService.history(user.id(), limit);
    }

    @GetMapping("/saved")
    Object saved(@AuthenticationPrincipal AuthenticatedUser user) {
        return researchService.saved(user.id());
    }

    @PostMapping("/saved")
    ResponseEntity<?> save(@AuthenticationPrincipal AuthenticatedUser user,
                           @Valid @RequestBody ResearchDtos.SaveRequest request) {
        return ResponseEntity.status(201).body(researchService.save(user.id(), request));
    }

    @DeleteMapping("/saved/{id}")
    Object deleteSaved(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        researchService.deleteSaved(user.id(), id);
        return Map.of("message", "已取消收藏");
    }
}
