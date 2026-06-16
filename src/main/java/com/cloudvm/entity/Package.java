package com.cloudvm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Entity mapping table Packages trong SQL Server.
 *
 * Mỗi Package định nghĩa:
 * - AMI để launch Windows Server
 * - Loại instance (t2.micro / t3.micro)
 * - Thời hạn thuê và giá tiền
 */
@Entity
@Table(name = "Packages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Package {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "package_name", nullable = false, length = 100)
    private String packageName;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /**
     * AWS AMI ID của Windows Server image tại region ap-southeast-1.
     * Ví dụ: ami-0c7c4f1e6f1e1e1e1 (Windows Server 2022 Base)
     */
    @Column(name = "ami_id", nullable = false, length = 50)
    private String amiId;

    /**
     * Loại instance EC2: t2.micro hoặc t3.micro.
     * Free tier eligible: t2.micro (750 giờ/tháng cho Windows).
     */
    @Column(name = "instance_type", length = 20)
    private String instanceType;
}
