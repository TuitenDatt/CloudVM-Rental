package com.cloudvm.service;

import com.cloudvm.dto.request.LoginRequest;
import com.cloudvm.dto.request.RegisterRequest;
import com.cloudvm.dto.response.AuthResponse;

/**
 * Service interface cho authentication.
 */
public interface AuthService {

    /**
     * Đăng ký tài khoản mới.
     *
     * @param request  Thông tin đăng ký (username, password, email)
     * @return         AuthResponse chứa JWT token
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Đăng nhập và lấy JWT token.
     *
     * @param request  Thông tin đăng nhập (username, password)
     * @return         AuthResponse chứa JWT token
     */
    AuthResponse login(LoginRequest request);
}
