package com.cloudvm.service;

import com.cloudvm.entity.CloudInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstanceNotificationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final EmailService emailService;

    public void sendRentalSuccessEmail(CloudInstance instance) {
        sendInstanceEmail(
                instance,
                "CloudVM - Máy ảo của bạn đã sẵn sàng",
                """
                <p>Xin chào %s,</p>
                <p>Máy ảo bạn thuê đã được khởi tạo thành công và sẵn sàng sử dụng.</p>
                <ul>
                    <li>Gói thuê: <strong>%s</strong></li>
                    <li>Loại máy: <strong>%s</strong></li>
                    <li>Mã EC2: <strong>%s</strong></li>
                    <li>IP: <strong>%s</strong></li>
                    <li>Hết hạn lúc: <strong>%s</strong></li>
                </ul>
                <p>Bạn có thể đăng nhập CloudVM để mở terminal và quản lý máy ảo.</p>
                """.formatted(
                        instance.getUser().getUsername(),
                        instance.getPkg().getPackageName(),
                        instance.getPkg().getInstanceType(),
                        valueOrDash(instance.getAwsInstanceId()),
                        valueOrDash(instance.getPublicIp()),
                        formatDateTime(instance)
                )
        );
    }

    public void sendExpirationReminderEmail(CloudInstance instance) {
        sendInstanceEmail(
                instance,
                "CloudVM - Máy ảo sắp hết hạn trong 3 ngày",
                """
                <p>Xin chào %s,</p>
                <p>Máy ảo của bạn sẽ hết hạn trong khoảng 3 ngày tới.</p>
                <ul>
                    <li>Gói thuê: <strong>%s</strong></li>
                    <li>Loại máy: <strong>%s</strong></li>
                    <li>Mã EC2: <strong>%s</strong></li>
                    <li>Hết hạn lúc: <strong>%s</strong></li>
                </ul>
                <p>Hãy sao lưu dữ liệu quan trọng hoặc liên hệ quản trị viên nếu bạn cần gia hạn.</p>
                """.formatted(
                        instance.getUser().getUsername(),
                        instance.getPkg().getPackageName(),
                        instance.getPkg().getInstanceType(),
                        valueOrDash(instance.getAwsInstanceId()),
                        formatDateTime(instance)
                )
        );
    }

    public void sendExpiredEmail(CloudInstance instance) {
        sendInstanceEmail(
                instance,
                "CloudVM - Máy ảo đã hết hạn",
                """
                <p>Xin chào %s,</p>
                <p>Máy ảo của bạn đã hết hạn và hệ thống đã dừng máy ảo này.</p>
                <ul>
                    <li>Gói thuê: <strong>%s</strong></li>
                    <li>Loại máy: <strong>%s</strong></li>
                    <li>Mã EC2: <strong>%s</strong></li>
                    <li>Hết hạn lúc: <strong>%s</strong></li>
                </ul>
                <p>Máy ảo sẽ được giữ trong thời gian gia hạn trước khi bị xóa hoàn toàn.</p>
                """.formatted(
                        instance.getUser().getUsername(),
                        instance.getPkg().getPackageName(),
                        instance.getPkg().getInstanceType(),
                        valueOrDash(instance.getAwsInstanceId()),
                        formatDateTime(instance)
                )
        );
    }

    private void sendInstanceEmail(CloudInstance instance, String subject, String bodyContent) {
        try {
            emailService.sendEmail(
                    instance.getUser().getEmail(),
                    subject,
                    wrapTemplate(bodyContent)
            );
        } catch (IllegalStateException e) {
            log.warn("Khong the gui email thong bao instance DB ID {} toi {}: {}",
                    instance.getId(), instance.getUser().getEmail(), e.getMessage());
        }
    }

    private String wrapTemplate(String bodyContent) {
        return """
                <!doctype html>
                <html lang="vi">
                <body style="margin:0;padding:24px;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#0f172a;">
                    <div style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:14px;padding:28px;border:1px solid #e2e8f0;">
                        <h2 style="margin:0 0 18px;color:#2563eb;">CloudVM</h2>
                        <div style="font-size:15px;line-height:1.7;">
                            %s
                        </div>
                        <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0;">
                        <p style="margin:0;font-size:12px;color:#64748b;">Email này được gửi tự động, vui lòng không trả lời email này.</p>
                    </div>
                </body>
                </html>
                """.formatted(bodyContent);
    }

    private String formatDateTime(CloudInstance instance) {
        return instance.getExpireDate().format(DATE_TIME_FORMATTER);
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
