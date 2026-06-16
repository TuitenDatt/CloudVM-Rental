package com.cloudvm.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Generic API response wrapper cho tất cả endpoints.
 *
 * Cấu trúc thống nhất giúp frontend dễ xử lý:
 * {
 *   "success": true,
 *   "message": "Thành công",
 *   "data": { ... }
 * }
 *
 * @param <T>  Kiểu dữ liệu của trường data
 *
 * @JsonInclude(NON_NULL): không serialize trường null vào JSON
 * (ví dụ: khi có lỗi thì data = null, không cần hiển thị "data": null)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    /**
     * Factory method tạo response thành công với data.
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * Factory method tạo response thành công không có data.
     */
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .build();
    }

    /**
     * Factory method tạo response lỗi.
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
