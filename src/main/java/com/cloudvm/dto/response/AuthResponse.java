package com.cloudvm.dto.response;

import com.cloudvm.enums.AuthProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private String refreshToken;
    private Integer userId;
    private String username;
    private String email;
    private AuthProvider authProvider;
    private long expiresIn;
}
