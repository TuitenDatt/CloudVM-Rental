package com.cloudvm.controller;

import com.cloudvm.dto.request.ForgotPasswordRequest;
import com.cloudvm.dto.request.LoginRequest;
import com.cloudvm.dto.request.RefreshTokenRequest;
import com.cloudvm.dto.request.RegisterRequest;
import com.cloudvm.dto.request.ResetPasswordRequest;
import com.cloudvm.dto.request.VerifyEmailRequest;
import com.cloudvm.dto.response.ApiResponse;
import com.cloudvm.dto.response.AuthResponse;
import com.cloudvm.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        try {
            authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Dang ky thanh cong. Ban co the dang nhap ngay.", null));
        } catch (IllegalArgumentException e) {
            log.warn("Dang ky that bai: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        try {
            AuthResponse authResponse = authService.login(request);
            return ResponseEntity.ok(ApiResponse.success("Dang nhap thanh cong", authResponse));
        } catch (AuthenticationException e) {
            log.warn("Dang nhap that bai cho user: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        try {
            AuthResponse authResponse = authService.refresh(request);
            return ResponseEntity.ok(ApiResponse.success("Lam moi token thanh cong", authResponse));
        } catch (AuthenticationException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.success("Dang xuat thanh cong", null));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request
    ) {
        try {
            authService.verifyEmail(request);
            return ResponseEntity.ok(ApiResponse.success("Xac thuc email thanh cong", null));
        } catch (AuthenticationException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        try {
            authService.forgotPassword(request);
            return ResponseEntity.ok(ApiResponse.success("Neu email ton tai, chung toi da gui huong dan dat lai mat khau.", null));
        } catch (IllegalStateException e) {
            log.warn("Gui email dat lai mat khau that bai: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        try {
            authService.resetPassword(request);
            return ResponseEntity.ok(ApiResponse.success("Dat lai mat khau thanh cong", null));
        } catch (AuthenticationException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
