package com.cloudvm.service;

import com.cloudvm.dto.request.LoginRequest;
import com.cloudvm.dto.request.RegisterRequest;
import com.cloudvm.dto.response.AuthResponse;
import com.cloudvm.entity.User;
import com.cloudvm.repository.UserRepository;
import com.cloudvm.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation của AuthService.
 *
 * Luồng Register:
 * 1. Validate username + email chưa tồn tại
 * 2. Hash password bằng BCrypt
 * 3. Lưu User vào DB
 * 4. Generate JWT token
 * 5. Trả về AuthResponse
 *
 * Luồng Login:
 * 1. Dùng AuthenticationManager authenticate(username, password)
 * 2. Nếu thành công → generate JWT token
 * 3. Trả về AuthResponse
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    /**
     * Đăng ký tài khoản mới.
     * @throws IllegalArgumentException nếu username hoặc email đã tồn tại
     */
    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Kiểm tra username đã tồn tại
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException(
                    "Username '" + request.getUsername() + "' đã được sử dụng"
            );
        }

        // Kiểm tra email đã tồn tại
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException(
                    "Email '" + request.getEmail() + "' đã được sử dụng"
            );
        }

        // Tạo và lưu User mới
        User newUser = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .build();

        User savedUser = userRepository.save(newUser);
        log.info("Đăng ký thành công cho user: {}", savedUser.getUsername());

        // Generate JWT token
        String token = jwtUtil.generateToken(savedUser.getUsername(), savedUser.getId());

        return AuthResponse.builder()
                .token(token)
                .userId(savedUser.getId())
                .username(savedUser.getUsername())
                .email(savedUser.getEmail())
                .expiresIn(jwtExpirationMs)
                .build();
    }

    /**
     * Đăng nhập và lấy JWT token.
     * AuthenticationManager sẽ throw AuthenticationException nếu sai thông tin.
     */
    @Override
    public AuthResponse login(LoginRequest request) {
        // Spring Security tự validate username/password qua CustomUserDetailsService
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        // Load thông tin user từ DB để lấy userId
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalStateException("User không tồn tại sau khi authenticate"));

        log.info("Đăng nhập thành công cho user: {}", user.getUsername());

        // Generate JWT token
        String token = jwtUtil.generateToken(user.getUsername(), user.getId());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .expiresIn(jwtExpirationMs)
                .build();
    }
}
