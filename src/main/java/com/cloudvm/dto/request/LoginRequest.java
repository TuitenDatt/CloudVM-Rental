package com.cloudvm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body cho API đăng nhập.
 * POST /api/auth/login
 */
@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Username không được để trống")
    private String username;

    @NotBlank(message = "Password không được để trống")
    private String password;
}
