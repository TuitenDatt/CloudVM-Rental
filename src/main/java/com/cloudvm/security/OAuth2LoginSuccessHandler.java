package com.cloudvm.security;

import com.cloudvm.dto.response.AuthResponse;
import com.cloudvm.entity.User;
import com.cloudvm.enums.AuthProvider;
import com.cloudvm.repository.UserRepository;
import com.cloudvm.service.AuthTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        String email = oauthUser.getAttribute("email");
        String name = oauthUser.getAttribute("name");

        if (email == null || email.isBlank()) {
            response.sendRedirect("/#oauth=error&message=Google%20account%20does%20not%20provide%20email");
            return;
        }

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createGoogleUser(email, name));

        AuthResponse authResponse = authTokenService.issueAuthResponse(user);
        String redirectUrl = "/#oauth=success"
                + "&token=" + encode(authResponse.getToken())
                + "&refreshToken=" + encode(authResponse.getRefreshToken())
                + "&userId=" + user.getId()
                + "&username=" + encode(user.getUsername())
                + "&email=" + encode(user.getEmail())
                + "&authProvider=" + user.getAuthProvider().name()
                + "&expiresIn=" + authResponse.getExpiresIn();

        log.info("Google login thanh cong cho user: {}", user.getUsername());
        response.sendRedirect(redirectUrl);
    }

    private User createGoogleUser(String email, String name) {
        String username = buildUniqueUsername(email, name);
        return userRepository.save(User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .authProvider(AuthProvider.GOOGLE)
                .emailVerified(true)
                .build());
    }

    private String buildUniqueUsername(String email, String name) {
        String base = (name != null && !name.isBlank())
                ? name
                : email.substring(0, email.indexOf('@'));
        base = base.toLowerCase()
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");

        if (base.isBlank()) {
            base = "google_user";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }

        String username = base;
        int suffix = 1;
        while (userRepository.existsByUsername(username)) {
            username = base + "_" + suffix++;
        }
        return username;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
