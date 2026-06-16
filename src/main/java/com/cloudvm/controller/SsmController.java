package com.cloudvm.controller;

import com.cloudvm.dto.response.ApiResponse;
import com.cloudvm.dto.response.SsmSessionResponse;
import com.cloudvm.security.JwtUtil;
import com.cloudvm.service.SsmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Controller xử lý SSM Web Terminal (Luồng 2).
 *
 * POST /api/ssm/session/{instanceId}
 *   → Tạo SSM session, trả về WebSocket URL và Token cho Frontend
 *   → Frontend dùng thông tin này để kết nối Xterm.js terminal
 */
@RestController
@RequestMapping("/api/ssm")
@RequiredArgsConstructor
@Slf4j
public class SsmController {

    private final SsmService ssmService;
    private final JwtUtil jwtUtil;

    /**
     * Tạo một SSM session để mở Web Terminal cho instance.
     *
     * POST /api/ssm/session/{instanceId}
     * Header: Authorization: Bearer <token>
     *
     * Trong đó:
     * - instanceId: ID của CloudInstance trong DB (không phải AWS instance ID)
     *
     * Response 200 OK:
     * {
     *   "success": true,
     *   "message": "SSM session đã được tạo",
     *   "data": {
     *     "sessionId": "xxxxxxxxxxxxxxxx",
     *     "streamUrl": "wss://ssmmessages.ap-southeast-1.amazonaws.com/v1/data-channel/...",
     *     "tokenValue": "xxxxxxxxxxxxxxxx",
     *     "targetInstanceId": "i-xxxxxxxxxxxxxxxxx"
     *   }
     * }
     *
     * Response 404 nếu instance không tồn tại hoặc không thuộc về user.
     * Response 409 nếu instance chưa ở trạng thái RUNNING.
     */
    @PostMapping("/session/{instanceId}")
    public ResponseEntity<ApiResponse<SsmSessionResponse>> startSession(
            @PathVariable Integer instanceId,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            Integer userId = extractUserIdFromHeader(authHeader);
            log.info("User {} yêu cầu mở SSM terminal cho instance DB ID: {}", userId, instanceId);

            SsmSessionResponse sessionResponse = ssmService.startSession(instanceId, userId);

            return ResponseEntity.ok(
                    ApiResponse.success("SSM session đã được tạo. Đang kết nối terminal...", sessionResponse)
            );
        } catch (NoSuchElementException e) {
            log.warn("SSM session thất bại — instance không tồn tại: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("SSM session thất bại — instance chưa RUNNING: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("SSM session thất bại — lỗi không xác định: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Không thể tạo SSM session: " + e.getMessage()));
        }
    }

    /**
     * Extract userId từ JWT token trong Authorization header.
     */
    private Integer extractUserIdFromHeader(String authHeader) {
        String token = authHeader.substring(7);
        return jwtUtil.extractUserId(token);
    }
}
