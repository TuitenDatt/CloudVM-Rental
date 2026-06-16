package com.cloudvm.service;

import com.cloudvm.dto.response.SsmSessionResponse;

/**
 * Service interface cho SSM Session Manager.
 */
public interface SsmService {

    /**
     * Tạo một SSM session vào instance và trả về thông tin kết nối WebSocket.
     *
     * @param dbInstanceId  ID của CloudInstance trong DB
     * @param userId        ID của user đang request (để validate quyền)
     * @return              SsmSessionResponse chứa streamUrl, tokenValue, sessionId
     */
    SsmSessionResponse startSession(Integer dbInstanceId, Integer userId);
}
