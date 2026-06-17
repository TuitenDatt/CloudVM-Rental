package com.cloudvm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {

    @NotBlank(message = "Username khong duoc de trong")
    @Size(min = 3, max = 50, message = "Username phai tu 3 den 50 ky tu")
    @Pattern(
            regexp = "^[a-zA-Z0-9_.-]+$",
            message = "Username chi duoc chua chu cai, so va ky tu ._-"
    )
    private String username;
}
