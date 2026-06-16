package com.cloudvm.repository;

import com.cloudvm.entity.Package;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository cho entity Package.
 * Dùng các method CRUD mặc định từ JpaRepository.
 * findAll() — lấy tất cả gói cước để hiển thị cho user chọn.
 * findById(id) — lấy thông tin gói khi user thuê máy.
 */
@Repository
public interface PackageRepository extends JpaRepository<Package, Integer> {
    // Không cần custom query — JpaRepository đã cung cấp đủ
}
