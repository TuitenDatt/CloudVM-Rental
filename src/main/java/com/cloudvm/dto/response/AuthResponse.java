package com.cloudvm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response trả về sau khi register hoặc login thành công.
 * Chứa JWT token và thông tin cơ bản của user.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /** JWT token — frontend lưu vào localStorage và gửi kèm mỗi request. */
    private String token;

    private Integer userId;
    private String username;
    private String email;

    /** Thời gian token hết hạn (milliseconds từ epoch). */
    private long expiresIn;
}
