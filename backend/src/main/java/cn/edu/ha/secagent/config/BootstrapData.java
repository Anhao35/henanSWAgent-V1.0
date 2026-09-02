package cn.edu.ha.secagent.config;

import cn.edu.ha.secagent.domain.Organization;
import cn.edu.ha.secagent.domain.User;
import cn.edu.ha.secagent.domain.UserProfile;
import cn.edu.ha.secagent.domain.UserRole;
import cn.edu.ha.secagent.domain.UserStatus;
import cn.edu.ha.secagent.repository.OrganizationRepository;
import cn.edu.ha.secagent.repository.UserProfileRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class BootstrapData implements CommandLineRunner {
    private final AppProperties properties;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        var organization = organizationRepository.findByCode("HERCERT").orElseGet(() -> {
            var org = new Organization();
            org.setCode("HERCERT");
            org.setName("河南省教育科研计算机网络中心");
            return organizationRepository.save(org);
        });
        var admin = properties.bootstrapAdmin();
        if (!admin.enabled() || userRepository.existsByUsernameIgnoreCase(admin.username())) return;
        var user = new User();
        user.setOrganization(organization);
        user.setUsername(admin.username().trim().toLowerCase());
        user.setDisplayName("系统管理员");
        user.setEmail(admin.email());
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode(admin.password()));
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);
        var profile = new UserProfile();
        profile.setUser(user);
        profile.setJobTitle("系统管理员");
        profileRepository.save(profile);
    }
}

