package com.cloudvm.controller;

import com.cloudvm.dto.request.LoginRequest;
import com.cloudvm.dto.request.RegisterRequest;
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

/**
 * Controller xử lý Authentication — Register và Login.
 *
 * Các endpoint này được permit all trong SecurityConfig (không cần JWT).
 *
 * POST /api/auth/register  — Đăng ký tài khoản mới
 * POST /api/auth/login     — Đăng nhập, nhận JWT token
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    /**
     * Đăng ký tài khoản mới.
     *
     * Request body:
     * {
     *   "username": "john",
     *   "password": "password123",
     *   "email": "john@example.com"
     * }
     *
     * Response 201 Created:
     * {
     *   "success": true,
     *   "message": "Đăng ký thành công",
     *   "data": { "token": "...", "userId": 1, "username": "john", ... }
     * }
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        try {
            AuthResponse authResponse = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Đăng ký thành công", authResponse));
        } catch (IllegalArgumentException e) {
            log.warn("Đăng ký thất bại: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Đăng nhập và nhận JWT token.
     *
     * Request body:
     * {
     *   "username": "john",
     *   "password": "password123"
     * }
     *
     * Response 200 OK:
     * {
     *   "success": true,
     *   "message": "Đăng nhập thành công",
     *   "data": { "token": "eyJhbGci...", "userId": 1, ... }
     * }
     *
     * Response 401 Unauthorized nếu sai thông tin:
     * {
     *   "success": false,
     *   "message": "Tên đăng nhập hoặc mật khẩu không đúng"
     * }
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        try {
            AuthResponse authResponse = authService.login(request);
            return ResponseEntity.ok(ApiResponse.success("Đăng nhập thành công", authResponse));
        } catch (AuthenticationException e) {
            log.warn("Đăng nhập thất bại cho user: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Tên đăng nhập hoặc mật khẩu không đúng"));
        }
    }
}
