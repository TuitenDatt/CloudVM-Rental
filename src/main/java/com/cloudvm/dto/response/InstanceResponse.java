package com.cloudvm.dto.response;

import com.cloudvm.entity.CloudInstance;
import com.cloudvm.enums.InstanceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response trả về thông tin của một Cloud Instance.
 * Dùng DTO để tránh trả về toàn bộ entity (có thể lộ thông tin không cần thiết).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceResponse {

    private Integer id;
    private String awsInstanceId;
    private String publicIp;
    private InstanceStatus status;
    private LocalDateTime startDate;
    private LocalDateTime expireDate;
    private LocalDateTime createdAt;

    // Thông tin Package (flatten để frontend dễ dùng)
    private String packageName;
    private Integer durationDays;
    private BigDecimal price;
    private String instanceType;

    /**
     * Static factory method — chuyển CloudInstance entity thành DTO.
     * Tránh viết mapping code lặp lại ở nhiều nơi.
     *
     * @param instance  CloudInstance entity (phải có pkg loaded)
     * @return          InstanceResponse DTO
     */
    public static InstanceResponse from(CloudInstance instance) {
        return InstanceResponse.builder()
                .id(instance.getId())
                .awsInstanceId(instance.getAwsInstanceId())
                .publicIp(instance.getPublicIp())
                .status(instance.getStatus())
                .startDate(instance.getStartDate())
                .expireDate(instance.getExpireDate())
                .createdAt(instance.getCreatedAt())
                .packageName(instance.getPkg().getPackageName())
                .durationDays(instance.getPkg().getDurationDays())
                .price(instance.getPkg().getPrice())
                .instanceType(instance.getPkg().getInstanceType())
                .build();
    }
}
