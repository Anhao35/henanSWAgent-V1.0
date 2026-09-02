package cn.edu.ha.secagent.admin;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.domain.UserRole;
import cn.edu.ha.secagent.domain.UserStatus;
import cn.edu.ha.secagent.repository.UserProfileRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.user.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public List<UserView> list(UUID actorId) {
        var actor = userRepository.findDetailedById(actorId).orElseThrow();
        var users = actor.getRole() == UserRole.SUPER_ADMIN
                ? userRepository.findAllDetailed()
                : userRepository.findAllDetailedByOrganizationId(actor.getOrganization().getId());
        return users.stream().map(user -> UserView.of(user, profileRepository.findById(user.getId()).orElse(null))).toList();
    }

    @Transactional
    public UserView updateStatus(UUID actorId, UUID targetId, UserStatus status) {
        if (actorId.equals(targetId) && status != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_DISABLE_SELF", "不能停用当前登录账号");
        }
        var actor = userRepository.findDetailedById(actorId).orElseThrow();
        var target = userRepository.findDetailedById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        assertManageable(actor.getRole(), actor.getOrganization() == null ? null : actor.getOrganization().getId(), target.getRole(), target.getOrganization() == null ? null : target.getOrganization().getId());
        target.setStatus(status);
        userRepository.save(target);
        return UserView.of(target, profileRepository.findById(targetId).orElse(null));
    }

    @Transactional
    public UserView updateRole(UUID actorId, UUID targetId, UserRole role) {
        var actor = userRepository.findDetailedById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CHANGE_FORBIDDEN", "只有系统管理员可以调整角色");
        }
        var target = userRepository.findDetailedById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        target.setRole(role);
        userRepository.save(target);
        return UserView.of(target, profileRepository.findById(targetId).orElse(null));
    }

    private static void assertManageable(UserRole actorRole, UUID actorOrg, UserRole targetRole, UUID targetOrg) {
        if (actorRole == UserRole.SUPER_ADMIN) return;
        if (targetRole == UserRole.SUPER_ADMIN || actorOrg == null || !actorOrg.equals(targetOrg)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_MANAGEMENT_FORBIDDEN", "不能管理该用户");
        }
    }
}

