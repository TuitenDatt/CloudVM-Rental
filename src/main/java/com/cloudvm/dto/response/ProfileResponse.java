package com.cloudvm.dto.response;

import com.cloudvm.entity.CloudInstance;
import com.cloudvm.entity.User;
import com.cloudvm.enums.InstanceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponse {

    private Integer userId;
    private String username;
    private String email;
    private LocalDateTime createdAt;
    private long runningInstances;
    private long pendingInstances;
    private long expiredInstances;
    private long totalInstances;
    private long activeInstances;
    private int quotaLimit;
    private String avatarUrl;

    public static ProfileResponse from(User user, List<CloudInstance> instances, long activeInstances, int quotaLimit) {
        return ProfileResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt())
                .runningInstances(countByStatus(instances, InstanceStatus.RUNNING))
                .pendingInstances(countByStatus(instances, InstanceStatus.PENDING))
                .expiredInstances(countByStatus(instances, InstanceStatus.STOPPED_EXPIRED))
                .totalInstances(instances.size())
                .activeInstances(activeInstances)
                .quotaLimit(quotaLimit)
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    private static long countByStatus(List<CloudInstance> instances, InstanceStatus status) {
        return instances.stream()
                .filter(instance -> status.equals(instance.getStatus()))
                .count();
    }
}
