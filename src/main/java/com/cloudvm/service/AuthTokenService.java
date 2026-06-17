package com.cloudvm.service;

import com.cloudvm.dto.response.AuthResponse;
import com.cloudvm.entity.RefreshToken;
import com.cloudvm.entity.User;
import com.cloudvm.repository.RefreshTokenRepository;
import com.cloudvm.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final JwtUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Transactional
    public AuthResponse issueAuthResponse(User user) {
        revokeActiveTokens(user.getId());
        String accessToken = jwtUtil.generateToken(user.getUsername(), user.getId());
        String refreshToken = createRefreshToken(user);
        return buildResponse(user, accessToken, refreshToken);
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new BadCredentialsException("Refresh token khong hop le"));

        if (Boolean.TRUE.equals(refreshToken.getRevoked())) {
            throw new BadCredentialsException("Refresh token da bi thu hoi");
        }

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshToken.setRevoked(true);
            refreshTokenRepository.save(refreshToken);
            throw new BadCredentialsException("Refresh token da het han");
        }

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        User user = refreshToken.getUser();
        String accessToken = jwtUtil.generateToken(user.getUsername(), user.getId());
        String newRefreshToken = createRefreshToken(user);
        return buildResponse(user, accessToken, newRefreshToken);
    }

    @Transactional
    public void revoke(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            return;
        }

        refreshTokenRepository.findByToken(refreshTokenValue).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public void revokeAllForUser(Integer userId) {
        revokeActiveTokens(userId);
    }

    private AuthResponse buildResponse(User user, String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .authProvider(user.getAuthProvider())
                .expiresIn(jwtExpirationMs)
                .build();
    }

    private String createRefreshToken(User user) {
        String refreshTokenValue = UUID.randomUUID().toString() + "-" + UUID.randomUUID();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenValue)
                .expiresAt(LocalDateTime.now().plusNanos(refreshExpirationMs * 1_000_000L))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);
        return refreshTokenValue;
    }

    private void revokeActiveTokens(Integer userId) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
        activeTokens.forEach(token -> token.setRevoked(true));
        refreshTokenRepository.saveAll(activeTokens);
    }
}
