package com.cloudvm.service;

import com.cloudvm.dto.request.ForgotPasswordRequest;
import com.cloudvm.dto.request.LoginRequest;
import com.cloudvm.dto.request.RefreshTokenRequest;
import com.cloudvm.dto.request.RegisterRequest;
import com.cloudvm.dto.request.ResetPasswordRequest;
import com.cloudvm.dto.request.VerifyEmailRequest;
import com.cloudvm.dto.response.AuthResponse;

public interface AuthService {

    void register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request);

    void verifyEmail(VerifyEmailRequest request);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
