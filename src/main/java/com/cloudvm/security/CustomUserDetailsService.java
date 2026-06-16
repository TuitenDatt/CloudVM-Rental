package com.cloudvm.security;

import com.cloudvm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Implementation của UserDetailsService — Spring Security dùng để load user
 * từ database khi authenticate.
 *
 * Dùng entity User của chúng ta trực tiếp bằng cách implement UserDetails
 * (hoặc wrap vào org.springframework.security.core.userdetails.User).
 * Ở đây dùng cách wrap để không coupling entity với Spring Security.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Load user bằng username từ database.
     * Spring Security gọi method này trong quá trình authenticate.
     *
     * @param username  Username cần tìm
     * @return          UserDetails chứa username, password (BCrypt), và roles
     * @throws UsernameNotFoundException nếu không tìm thấy user
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        com.cloudvm.entity.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Không tìm thấy user với username: " + username
                ));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .roles("USER")
                .build();
    }
}
