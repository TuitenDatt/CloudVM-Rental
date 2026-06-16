package com.cloudvm.repository;

import com.cloudvm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository cho entity User.
 * Spring Data JPA tự generate implementation dựa trên method name conventions.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    /**
     * Tìm user theo username — dùng để authenticate khi login.
     *
     * @param username  Username cần tìm
     * @return          Optional chứa User nếu tồn tại
     */
    Optional<User> findByUsername(String username);

    /**
     * Kiểm tra username đã tồn tại chưa — dùng khi register.
     *
     * @param username  Username cần kiểm tra
     * @return          true nếu đã tồn tại
     */
    boolean existsByUsername(String username);

    /**
     * Kiểm tra email đã tồn tại chưa — dùng khi register.
     *
     * @param email  Email cần kiểm tra
     * @return       true nếu đã tồn tại
     */
    boolean existsByEmail(String email);
}
