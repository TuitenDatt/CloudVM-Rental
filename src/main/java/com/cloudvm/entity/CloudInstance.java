package com.cloudvm.entity;

import com.cloudvm.enums.InstanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping table CloudInstances trong SQL Server.
 *
 * Đây là entity trung tâm của hệ thống, track toàn bộ vòng đời
 * của một EC2 instance từ lúc được thuê đến lúc bị terminate.
 *
 * Relationship:
 * - @ManyToOne User: một user có nhiều instance
 * - @ManyToOne Package: nhiều instance có thể cùng package
 * FetchType.LAZY: chỉ load User/Package khi cần, tránh N+1 query.
 */
@Entity
@Table(name = "CloudInstances")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private Package pkg;

    /**
     * AWS EC2 Instance ID (ví dụ: i-0abc123def456789).
     * NULL khi status = PENDING (chưa được tạo trên AWS).
     */
    @Column(name = "aws_instance_id", length = 50)
    private String awsInstanceId;

    /**
     * Public IP của EC2 instance.
     * NULL khi status = PENDING hoặc STOPPED_EXPIRED/TERMINATED.
     */
    @Column(name = "public_ip", length = 50)
    private String publicIp;

    /**
     * Trạng thái hiện tại của instance.
     * Lưu dạng String trong DB để dễ query bằng JPQL/SQL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InstanceStatus status;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    /**
     * Thời điểm hết hạn thuê = start_date + duration_days của package.
     */
    @Column(name = "expire_date", nullable = false)
    private LocalDateTime expireDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
