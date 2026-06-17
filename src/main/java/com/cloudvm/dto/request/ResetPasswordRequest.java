package com.cloudvm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordRequest {

    @NotBlank(message = "Token khong duoc de trong")
    private String token;

    @NotBlank(message = "Mat khau moi khong duoc de trong")
    @Size(min = 6, message = "Mat khau moi phai it nhat 6 ky tu")
    private String newPassword;
}
