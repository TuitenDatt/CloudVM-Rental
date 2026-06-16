package com.cloudvm.service;

import com.cloudvm.dto.response.InstanceResponse;

import java.util.List;

/**
 * Service interface cho quản lý Cloud Instance.
 */
public interface InstanceService {

    /**
     * Thuê một máy ảo mới (Luồng 1).
     * Tạo record PENDING ngay, khởi tạo EC2 chạy ngầm (@Async).
     *
     * @param userId     ID của user đang thuê máy (từ JWT)
     * @param packageId  ID của gói cước muốn thuê
     * @return           InstanceResponse với status = PENDING
     */
    InstanceResponse rentInstance(Integer userId, Integer packageId);

    /**
     * Lấy danh sách tất cả instance của một user.
     *
     * @param userId  ID của user
     * @return        Danh sách InstanceResponse
     */
    List<InstanceResponse> getInstancesByUser(Integer userId);

    /**
     * Lấy chi tiết một instance theo ID.
     * Validate instance phải thuộc về user đang request.
     *
     * @param instanceId  ID của instance trong DB
     * @param userId      ID của user đang request (từ JWT)
     * @return            InstanceResponse
     */
    InstanceResponse getInstanceById(Integer instanceId, Integer userId);

    /**
     * Hủy/Xóa một máy ảo.
     * Xóa máy chủ trên AWS (nếu có) và cập nhật trạng thái DB thành TERMINATED.
     *
     * @param instanceId ID của instance trong DB
     * @param userId     ID của user đang thao tác
     */
    void terminateInstance(Integer instanceId, Integer userId);
}
