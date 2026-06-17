package com.cloudvm.service;

import com.cloudvm.dto.request.ForgotPasswordRequest;
import com.cloudvm.dto.request.LoginRequest;
import com.cloudvm.dto.request.RefreshTokenRequest;
import com.cloudvm.dto.request.RegisterRequest;
import com.cloudvm.dto.request.ResetPasswordRequest;
import com.cloudvm.dto.request.VerifyEmailRequest;
import com.cloudvm.dto.response.AuthResponse;
import com.cloudvm.entity.User;
import com.cloudvm.enums.AuthProvider;
import com.cloudvm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AuthTokenService authTokenService;
    private final AccountRecoveryService accountRecoveryService;

    @Override
    @Transactional
    public void register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username '" + username + "' đã được sử dụng");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email '" + email + "' đã được sử dụng");
        }

        User savedUser = userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(request.getPassword()))
                .email(email)
                .authProvider(AuthProvider.LOCAL)
                .emailVerified(true)
                .build());

        log.info("Đăng ký thành công cho user: {}", savedUser.getUsername());
    }

    @Override
    public AuthResponse login(LoginRequest request) throws AuthenticationException {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new DisabledException("Tên đăng nhập hoặc mật khẩu không đúng"));

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        log.info("Đăng nhập thành công cho user: {}", user.getUsername());
        return authTokenService.issueAuthResponse(user);
    }

    @Override
    public AuthResponse refresh(RefreshTokenRequest request) {
        return authTokenService.refresh(request.getRefreshToken());
    }

    @Override
    public void logout(RefreshTokenRequest request) {
        authTokenService.revoke(request.getRefreshToken());
    }

    @Override
    public void verifyEmail(VerifyEmailRequest request) {
        accountRecoveryService.verifyEmail(request.getToken());
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        accountRecoveryService.sendPasswordResetEmail(request.getEmail());
    }

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        accountRecoveryService.resetPassword(request.getToken(), request.getNewPassword());
    }
}
