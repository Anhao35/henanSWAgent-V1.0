package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reports;
    private final RunTraceService traces;
    @GetMapping("/templates") Object templates() { return reports.templates(); }
    @PostMapping("/templates") @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object publish(@Valid @RequestBody TemplateRequest body) { return reports.publish(body.key(),body.name(),body.sections()); }
    @GetMapping Object list(@AuthenticationPrincipal AuthenticatedUser user) { return reports.list(user.id()); }
    @GetMapping("/{id}") Object get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id) { return reports.get(user.id(),id); }
    @PostMapping Object create(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody GenerateRequest body) { return reports.generate(user.id(),body.runId(),body.templateId(),body.title()); }
    @PostMapping("/{id}/versions") Object revise(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id,@Valid @RequestBody RevisionRequest body) { return reports.revise(user.id(),id,body.title(),body.content(),body.status()); }
    @GetMapping("/{id}/export") ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id,@RequestParam(defaultValue="md") String format) {
        var report=reports.get(user.id(),id);String text;String mime;String extension;
        switch(format) {
            case "json" -> {text=traces.json(Map.of("snapshot",report.get("snapshot"),"sha256",report.get("snapshotSha256")));mime="application/json";extension="json";}
            case "html" -> {
                text="<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>研判报告</title><style>body{max-width:900px;margin:40px auto;padding:24px;font:15px/1.8 sans-serif;color:#172d4e}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}@media print{body{margin:0}}</style><body><pre>"+escape(String.valueOf(report.get("content")))+"</pre><hr><p>证据快照 SHA-256："+report.get("snapshotSha256")+"</p></body></html>";
                mime="text/html";extension="html";
            }
            default -> {text=String.valueOf(report.get("content"));mime="text/markdown";extension="md";}
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime+";charset=UTF-8"))
            .header("Content-Disposition","attachment; filename=\"report-"+UUID.fromString(id)+"."+extension+"\"")
            .header("Cache-Control","no-store").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; sandbox")
            .body(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    public record GenerateRequest(@NotNull UUID runId,@NotBlank String templateId,@NotBlank @Size(max=160) String title) {}
    public record RevisionRequest(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=200000) String content,@NotBlank String status) {}
    public record TemplateRequest(@NotBlank @Size(max=48) String key,@NotBlank @Size(max=120) String name,@NotEmpty @Size(max=5) List<String> sections) {}
}
