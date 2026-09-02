package cn.edu.ha.secagent.security;

import cn.edu.ha.secagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        return userRepository.findForLogin(login.trim())
                .map(AuthenticatedUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
    }
}

