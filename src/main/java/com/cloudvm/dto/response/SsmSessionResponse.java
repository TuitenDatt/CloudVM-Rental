package com.cloudvm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response chứa thông tin SSM Session để frontend kết nối WebSocket terminal.
 *
 * Sau khi nhận response này, frontend dùng:
 * - streamUrl: WebSocket endpoint (wss://...)
 * - tokenValue: Token xác thực cho WebSocket handshake
 * - sessionId: ID session để theo dõi/terminate nếu cần
 *
 * Frontend sẽ kết nối trực tiếp từ browser đến AWS SSM endpoint,
 * không cần proxy qua backend — giảm tải cho server.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsmSessionResponse {

    /** ID của SSM session (dùng để terminate session khi user đóng terminal). */
    private String sessionId;

    /**
     * WebSocket URL để frontend kết nối (dạng wss://ssmmessages.ap-southeast-1.amazonaws.com/...).
     * Có thời hạn ngắn — frontend cần dùng ngay sau khi nhận.
     */
    private String streamUrl;

    /**
     * Token xác thực cho WebSocket connection.
     * Gửi kèm trong WebSocket handshake header.
     */
    private String tokenValue;

    /** AWS Instance ID của target instance (i-xxxx). */
    private String targetInstanceId;
}
