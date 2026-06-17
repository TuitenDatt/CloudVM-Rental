package com.cloudvm.repository;

import com.cloudvm.entity.VerificationToken;
import com.cloudvm.enums.VerificationTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Integer> {

    Optional<VerificationToken> findByTokenAndTypeAndUsedFalse(String token, VerificationTokenType type);

    List<VerificationToken> findByUserIdAndTypeAndUsedFalse(Integer userId, VerificationTokenType type);
}
