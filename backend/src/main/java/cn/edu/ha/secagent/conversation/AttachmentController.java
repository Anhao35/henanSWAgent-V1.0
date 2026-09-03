package cn.edu.ha.secagent.conversation;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations/{conversationId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {
    private final AttachmentService attachmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object upload(@AuthenticationPrincipal AuthenticatedUser user,
                  @PathVariable UUID conversationId,
                  @RequestPart("file") MultipartFile file) {
        return attachmentService.upload(user.id(), conversationId, file);
    }

    @GetMapping("/{attachmentId}")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthenticatedUser user,
                                    @PathVariable UUID conversationId,
                                    @PathVariable UUID attachmentId) {
        var stored = attachmentService.read(user.id(), conversationId, attachmentId);
        var disposition = ContentDisposition.inline().filename("attachment", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header("Content-Disposition", disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(stored.bytes());
    }

    @DeleteMapping("/{attachmentId}")
    Map<String, String> delete(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable UUID conversationId,
                               @PathVariable UUID attachmentId) {
        attachmentService.delete(user.id(), conversationId, attachmentId);
        return Map.of("message", "附件已移除");
    }
}
