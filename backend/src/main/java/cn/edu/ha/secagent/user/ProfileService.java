package cn.edu.ha.secagent.user;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.domain.UserProfile;
import cn.edu.ha.secagent.repository.UserProfileRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final ObjectStorageService storageService;

    @Transactional
    public UserView update(UUID userId, ProfileDtos.UpdateProfileRequest body) {
        var user = userRepository.findDetailedById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var profile = profileRepository.findById(userId).orElseGet(() -> {
            var created = new UserProfile();
            created.setUser(user);
            return created;
        });

        if (body.displayName() != null) user.setDisplayName(body.displayName().trim());
        if (body.email() != null) {
            var email = normalizedContact(body.email());
            boolean changed = email == null ? user.getEmail() != null : !email.equalsIgnoreCase(user.getEmail());
            if (changed && email != null && userRepository.existsByEmailIgnoreCase(email)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已被使用");
            }
            if (changed) {
                user.setEmail(email);
                user.setEmailVerified(false);
            }
        }
        if (body.phone() != null) {
            var phone = normalizedContact(body.phone());
            boolean changed = phone == null ? user.getPhone() != null : !phone.equals(user.getPhone());
            if (changed && phone != null && userRepository.existsByPhone(phone)) {
                throw new ApiException(HttpStatus.CONFLICT, "PHONE_EXISTS", "手机号已被使用");
            }
            if (changed) {
                user.setPhone(phone);
                user.setPhoneVerified(false);
            }
        }
        if (body.gender() != null) profile.setGender(trimToNull(body.gender()));
        if (body.birthDate() != null) profile.setBirthDate(body.birthDate());
        if (body.education() != null) profile.setEducation(trimToNull(body.education()));
        if (body.jobTitle() != null) profile.setJobTitle(trimToNull(body.jobTitle()));
        if (body.bio() != null) profile.setBio(trimToNull(body.bio()));
        userRepository.save(user);
        profileRepository.save(profile);
        return UserView.of(user, profile);
    }

    @Transactional
    public UserView updateAvatar(UUID userId, MultipartFile file) {
        var user = userRepository.findDetailedById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var profile = profileRepository.findById(userId).orElseGet(() -> {
            var created = new UserProfile();
            created.setUser(user);
            return created;
        });
        profile.setAvatarObjectKey(storageService.saveAvatar(userId, file));
        profileRepository.save(profile);
        return UserView.of(user, profile);
    }

    @Transactional(readOnly = true)
    public ObjectStorageService.StoredObject avatar(UUID userId) {
        var profile = profileRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AVATAR_NOT_FOUND", "用户尚未设置头像"));
        if (profile.getAvatarObjectKey() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AVATAR_NOT_FOUND", "用户尚未设置头像");
        }
        return storageService.read(profile.getAvatarObjectKey());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String normalizedContact(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
