package cn.edu.ha.secagent.admin;

import cn.edu.ha.secagent.domain.UserRole;
import cn.edu.ha.secagent.domain.UserStatus;
import jakarta.validation.constraints.NotNull;

public final class AdminDtos {
    private AdminDtos() {}
    public record UpdateStatusRequest(@NotNull UserStatus status) {}
    public record UpdateRoleRequest(@NotNull UserRole role) {}
}

