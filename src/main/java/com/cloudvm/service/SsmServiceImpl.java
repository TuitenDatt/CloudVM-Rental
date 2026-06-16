package com.cloudvm.service;

import com.cloudvm.dto.response.SsmSessionResponse;
import com.cloudvm.entity.CloudInstance;
import com.cloudvm.enums.InstanceStatus;
import com.cloudvm.repository.CloudInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.StartSessionRequest;
import software.amazon.awssdk.services.ssm.model.StartSessionResponse;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Implementation của SsmService — xử lý Luồng 2: Web Terminal qua SSM.
 *
 * Cách hoạt động:
 * 1. Backend gọi AWS SSM API StartSession, truyền target = awsInstanceId
 * 2. AWS trả về: sessionId, streamUrl (wss://...), tokenValue
 * 3. Backend trả về các thông tin này cho Frontend
 * 4. Frontend dùng Xterm.js kết nối WebSocket trực tiếp đến streamUrl
 *    (không qua backend server — giảm bandwidth và latency)
 *
 * Document dùng: AWS-StartInteractiveCommand với command = powershell
 * Cho phép user mở PowerShell terminal trên Windows Server.
 *
 * Điều kiện:
 * - EC2 instance phải đang RUNNING
 * - Instance phải có IAM Role với policy AmazonSSMManagedInstanceCore
 * - SSM Agent phải đang chạy trên Windows Server (có sẵn trên các AMI mới)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SsmServiceImpl implements SsmService {

    private final SsmClient ssmClient;
    private final CloudInstanceRepository cloudInstanceRepository;

    /**
     * Tạo SSM session và trả về thông tin kết nối WebSocket.
     *
     * @param dbInstanceId  ID của CloudInstance trong DB
     * @param userId        ID của user (để validate quyền)
     * @return              SsmSessionResponse với streamUrl + tokenValue
     * @throws NoSuchElementException  nếu instance không tồn tại hoặc không thuộc user
     * @throws IllegalStateException   nếu instance chưa RUNNING
     */
    @Override
    @Transactional(readOnly = true)
    public SsmSessionResponse startSession(Integer dbInstanceId, Integer userId) {
        // Validate instance tồn tại và thuộc về user
        if (!cloudInstanceRepository.existsByIdAndUserId(dbInstanceId, userId)) {
            throw new NoSuchElementException(
                    "Instance không tồn tại hoặc bạn không có quyền truy cập"
            );
        }

        CloudInstance instance = cloudInstanceRepository.findById(dbInstanceId)
                .orElseThrow(() -> new NoSuchElementException("Instance không tồn tại: " + dbInstanceId));

        // Chỉ cho phép mở terminal khi instance đang RUNNING
        if (!InstanceStatus.RUNNING.equals(instance.getStatus())) {
            throw new IllegalStateException(
                    "Chỉ có thể mở terminal khi instance đang RUNNING. " +
                    "Trạng thái hiện tại: " + instance.getStatus()
            );
        }

        String awsInstanceId = instance.getAwsInstanceId();
        if (awsInstanceId == null || awsInstanceId.isBlank()) {
            throw new IllegalStateException("Instance chưa có AWS Instance ID");
        }

        log.info("Tạo SSM session cho DB instance: {}, AWS instance: {}, User: {}",
                dbInstanceId, awsInstanceId, userId);

        // Gọi AWS SSM StartSession API
        StartSessionRequest startSessionRequest = buildStartSessionRequest(awsInstanceId);
        StartSessionResponse startSessionResponse = ssmClient.startSession(startSessionRequest);

        log.info("SSM session đã tạo thành công. Session ID: {}", startSessionResponse.sessionId());

        return SsmSessionResponse.builder()
                .sessionId(startSessionResponse.sessionId())
                .streamUrl(startSessionResponse.streamUrl())
                .tokenValue(startSessionResponse.tokenValue())
                .targetInstanceId(awsInstanceId)
                .build();
    }

    /**
     * Xây dựng SSM StartSession request.
     *
     * Document "AWS-StartInteractiveCommand":
     * - Cho phép chạy một lệnh interactive trên target instance
     * - Parameter "command": ["powershell"] → mở PowerShell trên Windows Server
     *
     * Thay thế cho RDP/SSH — user không cần biết password hay certificate,
     * chỉ cần có quyền IAM để gọi ssm:StartSession.
     *
     * @param awsInstanceId  AWS Instance ID (i-xxxxxxxxxxxxxxxxx)
     * @return               StartSessionRequest được cấu hình sẵn
     */
    private StartSessionRequest buildStartSessionRequest(String awsInstanceId) {
        return StartSessionRequest.builder()
                .target(awsInstanceId)
                .documentName("AWS-StartInteractiveCommand")
                .parameters(Map.of(
                        "command", List.of("powershell")
                ))
                .build();
    }
}
