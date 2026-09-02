package cn.edu.ha.secagent.user;

import cn.edu.ha.secagent.domain.User;
import cn.edu.ha.secagent.domain.UserProfile;

import java.time.LocalDate;
import java.util.UUID;

public record UserView(
        UUID id,
        String username,
        String displayName,
        String email,
        String phone,
        String role,
        String status,
        UUID organizationId,
        String organizationName,
        String avatarUrl,
        String gender,
        LocalDate birthDate,
        String education,
        String jobTitle,
        String bio
) {
    public static UserView of(User user, UserProfile profile) {
        var organization = user.getOrganization();
        var avatarUrl = profile != null && profile.getAvatarObjectKey() != null ? "/api/profile/avatar" : null;
        return new UserView(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getPhone(),
                user.getRole().name(), user.getStatus().name(),
                organization == null ? null : organization.getId(),
                organization == null ? null : organization.getName(),
                avatarUrl,
                profile == null ? null : profile.getGender(),
                profile == null ? null : profile.getBirthDate(),
                profile == null ? null : profile.getEducation(),
                profile == null ? null : profile.getJobTitle(),
                profile == null ? null : profile.getBio()
        );
    }
}

