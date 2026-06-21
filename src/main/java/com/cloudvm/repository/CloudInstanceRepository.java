package com.cloudvm.repository;

import com.cloudvm.entity.CloudInstance;
import com.cloudvm.enums.InstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho entity CloudInstance.
 *
 * Chứa các custom JPQL query quan trọng cho:
 * 1. Kiểm tra quota (đếm instance active của user)
 * 2. Cron Job 1: tìm instance RUNNING đã hết hạn
 * 3. Cron Job 2: tìm instance STOPPED_EXPIRED đã quá 3 ngày
 */
@Repository
public interface CloudInstanceRepository extends JpaRepository<CloudInstance, Integer> {

    /**
     * Đếm số instance active của một user.
     * Active = PENDING hoặc RUNNING hoặc STOPPED_EXPIRED.
     * Dùng để kiểm tra quota (tối đa 2 instance active cùng lúc).
     *
     * @param userId  ID của user cần kiểm tra
     * @return        Số lượng instance active
     */
    @Query("SELECT COUNT(c) FROM CloudInstance c " +
           "WHERE c.user.id = :userId " +
           "AND c.status IN ('PENDING', 'RUNNING', 'STOPPED_EXPIRED')")
    long countActiveByUserId(@Param("userId") Integer userId);

    /**
     * Lấy danh sách instance của một user theo thứ tự tạo mới nhất trước.
     * Dùng để hiển thị danh sách instance trong dashboard.
     *
     * @param userId  ID của user
     * @return        Danh sách CloudInstance
     */
    @Query("SELECT c FROM CloudInstance c " +
           "WHERE c.user.id = :userId " +
           "ORDER BY c.createdAt DESC")
    List<CloudInstance> findByUserIdOrderByCreatedAtDesc(@Param("userId") Integer userId);

    @Query("SELECT c FROM CloudInstance c " +
           "JOIN FETCH c.user " +
           "JOIN FETCH c.pkg " +
           "WHERE c.id = :id")
    Optional<CloudInstance> findWithUserAndPkgById(@Param("id") Integer id);

    /**
     * Cron Job 1: Tìm các instance đang RUNNING đã quá thời hạn thuê.
     * Những instance này sẽ bị stop và chuyển thành STOPPED_EXPIRED.
     *
     * @param now  Thời điểm hiện tại
     * @return     Danh sách instance cần dừng
     */
    @Query("SELECT c FROM CloudInstance c " +
           "WHERE c.status = 'RUNNING' " +
           "AND c.expireDate < :now")
    List<CloudInstance> findExpiredRunningInstances(@Param("now") LocalDateTime now);

    @Query("SELECT c FROM CloudInstance c " +
           "JOIN FETCH c.user " +
           "JOIN FETCH c.pkg " +
           "WHERE c.status = 'RUNNING' " +
           "AND c.expireDate >= :from " +
           "AND c.expireDate < :to")
    List<CloudInstance> findRunningInstancesExpiringBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Cron Job 2: Tìm các instance đã STOPPED_EXPIRED và đã quá ngưỡng
     * terminate (expire_date + 3 ngày < now).
     * Những instance này sẽ bị terminate hoàn toàn trên AWS.
     *
     * @param cutoff  Mốc thời gian (now - 3 ngày) — instance expire_date phải trước mốc này
     * @return        Danh sách instance cần terminate
     */
    @Query("SELECT c FROM CloudInstance c " +
           "WHERE c.status = 'STOPPED_EXPIRED' " +
           "AND c.expireDate < :cutoff")
    List<CloudInstance> findInstancesToTerminate(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Kiểm tra một instance có thuộc về user không.
     * Dùng để validate quyền truy cập trước khi tạo SSM session.
     *
     * @param instanceId  ID của CloudInstance trong DB
     * @param userId      ID của user
     * @return            true nếu instance thuộc về user
     */
    boolean existsByIdAndUserId(Integer instanceId, Integer userId);
}
