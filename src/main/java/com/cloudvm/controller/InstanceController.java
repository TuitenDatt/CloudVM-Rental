package com.cloudvm.controller;

import com.cloudvm.dto.request.RentInstanceRequest;
import com.cloudvm.dto.response.ApiResponse;
import com.cloudvm.dto.response.InstanceResponse;
import com.cloudvm.entity.Package;
import com.cloudvm.repository.PackageRepository;
import com.cloudvm.security.JwtUtil;
import com.cloudvm.service.InstanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Controller quản lý Cloud Instance.
 *
 * Tất cả endpoints yêu cầu JWT token trong header: Authorization: Bearer <token>
 *
 * GET  /api/packages         — Danh sách gói cước (public trong context của user)
 * POST /api/instances/rent   — Thuê máy ảo mới
 * GET  /api/instances        — Danh sách instance của user hiện tại
 * GET  /api/instances/{id}   — Chi tiết một instance
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class InstanceController {

    private final InstanceService instanceService;
    private final PackageRepository packageRepository;
    private final JwtUtil jwtUtil;

    // ================================================================
    // PACKAGES
    // ================================================================

    /**
     * Lấy danh sách tất cả gói cước.
     * Endpoint này cũng cần JWT (user phải đăng nhập mới xem được).
     *
     * GET /api/packages
     * Response: [ { id, packageName, durationDays, price, instanceType }, ... ]
     */
    @GetMapping("/api/packages")
    public ResponseEntity<ApiResponse<List<Package>>> getAllPackages() {
        List<Package> packages = packageRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success("Danh sách gói cước", packages));
    }

    // ================================================================
    // INSTANCES
    // ================================================================

    /**
     * Thuê một máy ảo mới (Luồng 1).
     *
     * POST /api/instances/rent
     * Header: Authorization: Bearer <token>
     * Body: { "packageId": 1 }
     *
     * Response 201 Created:
     * {
     *   "success": true,
     *   "message": "Yêu cầu thuê máy đang được xử lý",
     *   "data": { "id": 5, "status": "PENDING", ... }
     * }
     *
     * userId được extract từ JWT token — không nhận từ client để tránh giả mạo.
     */
    @PostMapping("/api/instances/rent")
    public ResponseEntity<ApiResponse<InstanceResponse>> rentInstance(
            @Valid @RequestBody RentInstanceRequest request,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            Integer userId = extractUserIdFromHeader(authHeader);
            InstanceResponse response = instanceService.rentInstance(userId, request.getPackageId());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(
                            "Yêu cầu thuê máy đang được xử lý. Máy sẽ sẵn sàng trong vài phút.",
                            response
                    ));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // Quota exceeded
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Lấy danh sách tất cả instance của user hiện tại.
     *
     * GET /api/instances
     * Header: Authorization: Bearer <token>
     *
     * Response: [ { id, status, publicIp, expireDate, packageName, ... }, ... ]
     */
    @GetMapping("/api/instances")
    public ResponseEntity<ApiResponse<List<InstanceResponse>>> getMyInstances(
            @RequestHeader("Authorization") String authHeader
    ) {
        Integer userId = extractUserIdFromHeader(authHeader);
        List<InstanceResponse> instances = instanceService.getInstancesByUser(userId);
        return ResponseEntity.ok(ApiResponse.success("Danh sách máy ảo của bạn", instances));
    }

    /**
     * Lấy chi tiết một instance theo ID.
     *
     * GET /api/instances/{id}
     * Header: Authorization: Bearer <token>
     *
     * Response: { id, status, awsInstanceId, publicIp, expireDate, ... }
     *
     * 404 nếu instance không tồn tại hoặc không thuộc về user hiện tại.
     */
    @GetMapping("/api/instances/{id}")
    public ResponseEntity<ApiResponse<InstanceResponse>> getInstanceById(
            @PathVariable Integer id,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            Integer userId = extractUserIdFromHeader(authHeader);
            InstanceResponse instance = instanceService.getInstanceById(id, userId);
            return ResponseEntity.ok(ApiResponse.success("Chi tiết máy ảo", instance));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Hủy một máy ảo (Terminate).
     *
     * DELETE /api/instances/{id}
     * Header: Authorization: Bearer <token>
     *
     * Response 200:
     * {
     *   "success": true,
     *   "message": "Hủy máy thành công",
     *   "data": null
     * }
     */
    @DeleteMapping("/api/instances/{id}")
    public ResponseEntity<ApiResponse<Void>> terminateInstance(
            @PathVariable Integer id,
            @RequestHeader("Authorization") String authHeader
    ) {
        try {
            Integer userId = extractUserIdFromHeader(authHeader);
            instanceService.terminateInstance(id, userId);
            return ResponseEntity.ok(ApiResponse.success("Hủy máy thành công. Hệ thống đang dọn dẹp tài nguyên trên AWS.", null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Lỗi terminate instance {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Lỗi hệ thống: " + e.getMessage()));
        }
    }

    // ================================================================
    // PRIVATE HELPER
    // ================================================================

    /**
     * Extract userId từ JWT token trong Authorization header.
     * Header format: "Bearer eyJhbGci..."
     *
     * @param authHeader  Giá trị của header Authorization
     * @return            userId từ JWT claims
     */
    private Integer extractUserIdFromHeader(String authHeader) {
        String token = authHeader.substring(7); // Bỏ prefix "Bearer "
        return jwtUtil.extractUserId(token);
    }
}
