package cn.edu.ha.secagent.user;

import cn.edu.ha.secagent.auth.AuthService;
import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {
    private final AuthService authService;
    private final ProfileService profileService;

    @GetMapping
    Object get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.getUser(principal.id());
    }

    @PatchMapping
    Object update(@AuthenticationPrincipal AuthenticatedUser principal,
                  @Valid @RequestBody ProfileDtos.UpdateProfileRequest body) {
        return profileService.update(principal.id(), body);
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object uploadAvatar(@AuthenticationPrincipal AuthenticatedUser principal,
                        @RequestPart("file") MultipartFile file) {
        return profileService.updateAvatar(principal.id(), file);
    }

    @GetMapping("/avatar")
    ResponseEntity<byte[]> avatar(@AuthenticationPrincipal AuthenticatedUser principal) {
        var object = profileService.avatar(principal.id());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(object.contentType())).body(object.bytes());
    }
}

