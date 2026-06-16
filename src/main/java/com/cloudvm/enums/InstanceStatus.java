package com.cloudvm.enums;

/**
 * Trạng thái vòng đời của một Cloud Instance.
 *
 * Luồng chuyển trạng thái:
 *
 *   PENDING
 *      │  (EC2 đang khởi động, @Async provisionInstanceAsync đang chạy)
 *      ▼
 *   RUNNING
 *      │  (Cron Job 1: expire_date < now → ec2.stopInstances)
 *      ▼
 *   STOPPED_EXPIRED
 *      │  (Cron Job 2: expire_date + 3 ngày < now → ec2.terminateInstances)
 *      ▼
 *   TERMINATED
 */
public enum InstanceStatus {

    /** Instance đang được khởi tạo (AWS EC2 chưa chạy xong). */
    PENDING,

    /** Instance đang chạy, user có thể truy cập qua SSM Terminal. */
    RUNNING,

    /** Instance đã bị dừng do hết hạn thuê. Chưa bị xóa, còn trong billing period. */
    STOPPED_EXPIRED,

    /** Instance đã bị terminate hoàn toàn, không thể khôi phục. */
    TERMINATED
}
