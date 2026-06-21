package com.cloudvm.service;

import com.cloudvm.entity.CloudInstance;
import com.cloudvm.enums.InstanceStatus;
import com.cloudvm.repository.CloudInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cron Job xử lý vòng đời thu hồi instance (Luồng 3).
 *
 * Job 1 — stopExpiredInstances (mỗi 1 giờ):
 *   Query RUNNING instances có expire_date < now
 *   → Gọi ec2.stopInstances()
 *   → Update status = STOPPED_EXPIRED
 *
 * Job 2 — terminateStaleInstances (mỗi ngày lúc 2AM):
 *   Query STOPPED_EXPIRED instances có expire_date + 3 ngày < now
 *   → Gọi ec2.terminateInstances()
 *   → Update status = TERMINATED
 *
 * Sử dụng @Transactional để đảm bảo DB update atomic.
 * Nếu AWS API fail cho một instance → log lỗi và tiếp tục với instance tiếp theo.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpirationScheduler {

    /**
     * Số ngày sau khi hết hạn trước khi instance bị terminate.
     * Grace period = 3 ngày: user có thể liên hệ để gia hạn.
     */
    private static final int TERMINATE_GRACE_PERIOD_DAYS = 3;

    private final CloudInstanceRepository cloudInstanceRepository;
    private final Ec2Client ec2Client;
    private final InstanceNotificationService instanceNotificationService;

    // ================================================================
    // CRON JOB 1: Stop Expired Instances
    // ================================================================

    /**
     * Chạy mỗi 1 giờ.
     * Cron expression: "0 0 * * * *" = giây 0, phút 0, mọi giờ
     *
     * Tìm các instance RUNNING đã quá expire_date và stop chúng.
     * Stop (không phải Terminate): instance vẫn còn tồn tại, data chưa mất.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void stopExpiredInstances() {
        LocalDateTime now = LocalDateTime.now();
        log.info("[CRON-1] Bắt đầu kiểm tra instance hết hạn lúc: {}", now);

        List<CloudInstance> expiredInstances = cloudInstanceRepository.findExpiredRunningInstances(now);

        if (expiredInstances.isEmpty()) {
            log.info("[CRON-1] Không có instance nào hết hạn.");
            return;
        }

        log.info("[CRON-1] Tìm thấy {} instance hết hạn. Tiến hành dừng...", expiredInstances.size());

        for (CloudInstance instance : expiredInstances) {
            stopSingleInstance(instance);
        }

        log.info("[CRON-1] Hoàn thành kiểm tra instance hết hạn.");
    }

    /**
     * Dừng một instance cụ thể trên AWS và update DB.
     * Xử lý lỗi cô lập: nếu một instance lỗi → các instance khác vẫn tiếp tục.
     */
    private void stopSingleInstance(CloudInstance instance) {
        String awsId = instance.getAwsInstanceId();

        if (awsId == null || awsId.isBlank()) {
            log.warn("[CRON-1] Instance DB ID {} không có awsInstanceId, skip.", instance.getId());
            instance.setStatus(InstanceStatus.STOPPED_EXPIRED);
            cloudInstanceRepository.save(instance);
            instanceNotificationService.sendExpiredEmail(instance);
            return;
        }

        try {
            log.info("[CRON-1] Đang dừng instance: DB={}, AWS={}", instance.getId(), awsId);

            StopInstancesRequest stopRequest = StopInstancesRequest.builder()
                    .instanceIds(awsId)
                    .build();

            ec2Client.stopInstances(stopRequest);

            // Update DB status
            instance.setStatus(InstanceStatus.STOPPED_EXPIRED);
            instance.setPublicIp(null); // IP bị release khi stop
            cloudInstanceRepository.save(instance);
            instanceNotificationService.sendExpiredEmail(instance);

            log.info("[CRON-1] Đã dừng thành công: DB={}, AWS={}", instance.getId(), awsId);

        } catch (Exception e) {
            log.error("[CRON-1] Lỗi khi dừng instance DB={}, AWS={}: {}",
                    instance.getId(), awsId, e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${app.instance.expiration-reminder-cron:0 5 * * * *}")
    @Transactional(readOnly = true)
    public void sendExpirationReminders() {
        LocalDateTime from = LocalDateTime.now().plusDays(3);
        LocalDateTime to = from.plusHours(1);
        log.info("[CRON-REMINDER] Kiem tra instance sap het han tu {} den {}", from, to);

        List<CloudInstance> instances = cloudInstanceRepository.findRunningInstancesExpiringBetween(from, to);

        if (instances.isEmpty()) {
            log.info("[CRON-REMINDER] Khong co instance nao can nhac het han.");
            return;
        }

        log.info("[CRON-REMINDER] Tim thay {} instance can gui mail nhac het han.", instances.size());
        instances.forEach(instanceNotificationService::sendExpirationReminderEmail);
    }

    // ================================================================
    // CRON JOB 2: Terminate Stale Instances
    // ================================================================

    /**
     * Chạy mỗi ngày lúc 2:00 AM.
     * Cron expression: "0 0 2 * * *" = giây 0, phút 0, giờ 2, mọi ngày
     *
     * Tìm các instance STOPPED_EXPIRED đã quá 3 ngày kể từ expire_date
     * và terminate hoàn toàn trên AWS (không thể khôi phục).
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void terminateStaleInstances() {
        // cutoff = now - TERMINATE_GRACE_PERIOD_DAYS
        // instance phải có expire_date < cutoff (tức là đã dừng > 3 ngày rồi)
        LocalDateTime cutoff = LocalDateTime.now().minusDays(TERMINATE_GRACE_PERIOD_DAYS);
        log.info("[CRON-2] Bắt đầu terminate instance cũ lúc: {}, cutoff: {}",
                LocalDateTime.now(), cutoff);

        List<CloudInstance> staleInstances = cloudInstanceRepository.findInstancesToTerminate(cutoff);

        if (staleInstances.isEmpty()) {
            log.info("[CRON-2] Không có instance nào cần terminate.");
            return;
        }

        log.info("[CRON-2] Tìm thấy {} instance cần terminate.", staleInstances.size());

        for (CloudInstance instance : staleInstances) {
            terminateSingleInstance(instance);
        }

        log.info("[CRON-2] Hoàn thành terminate instance cũ.");
    }

    /**
     * Terminate một instance cụ thể trên AWS và update DB.
     * Terminate là hành động không thể hoàn tác — instance bị xóa vĩnh viễn trên AWS.
     */
    private void terminateSingleInstance(CloudInstance instance) {
        String awsId = instance.getAwsInstanceId();

        if (awsId == null || awsId.isBlank()) {
            log.warn("[CRON-2] Instance DB ID {} không có awsInstanceId, chỉ update DB.", instance.getId());
            instance.setStatus(InstanceStatus.TERMINATED);
            cloudInstanceRepository.save(instance);
            return;
        }

        try {
            log.info("[CRON-2] Đang terminate instance: DB={}, AWS={}", instance.getId(), awsId);

            TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                    .instanceIds(awsId)
                    .build();

            ec2Client.terminateInstances(terminateRequest);

            // Update DB status
            instance.setStatus(InstanceStatus.TERMINATED);
            cloudInstanceRepository.save(instance);

            log.info("[CRON-2] Đã terminate thành công: DB={}, AWS={}", instance.getId(), awsId);

        } catch (Exception e) {
            log.error("[CRON-2] Lỗi khi terminate instance DB={}, AWS={}: {}",
                    instance.getId(), awsId, e.getMessage(), e);
        }
    }
}
