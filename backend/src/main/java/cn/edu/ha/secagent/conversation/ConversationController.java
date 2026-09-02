package cn.edu.ha.secagent.conversation;

import cn.edu.ha.secagent.agent.AgentService;
import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversationService;
    private final AgentService agentService;

    @GetMapping
    Object list(@AuthenticationPrincipal AuthenticatedUser user,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
        return conversationService.list(user.id(), page, size);
    }

    @PostMapping
    ResponseEntity<?> create(@AuthenticationPrincipal AuthenticatedUser user,
                             @Valid @RequestBody(required = false) ConversationDtos.CreateConversationRequest body) {
        var created = conversationService.create(user.id(), body == null ? null : body.title());
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/{conversationId}/messages")
    Object messages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId) {
        return conversationService.messages(user.id(), conversationId);
    }

    @PatchMapping("/{conversationId}")
    Object rename(@AuthenticationPrincipal AuthenticatedUser user,
                  @PathVariable UUID conversationId,
                  @Valid @RequestBody ConversationDtos.RenameConversationRequest body) {
        return conversationService.rename(user.id(), conversationId, body.title());
    }

    @DeleteMapping("/{conversationId}")
    Map<String, String> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId) {
        conversationService.delete(user.id(), conversationId);
        return Map.of("message", "会话已删除");
    }

    @PostMapping(value = "/{conversationId}/messages/stream", produces = "application/x-ndjson")
    ResponseEntity<StreamingResponseBody> sendMessage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID conversationId,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            @Valid @RequestBody ConversationDtos.SendMessageRequest body) {
        var prepared = agentService.prepare(user.id(), conversationId, body.message(), requestId);
        StreamingResponseBody stream = output -> agentService.executeStream(prepared, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header("X-Request-ID", prepared.requestId())
                .body(stream);
    }
}

