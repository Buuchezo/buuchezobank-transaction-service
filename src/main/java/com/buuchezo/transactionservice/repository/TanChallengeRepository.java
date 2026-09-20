package com.buuchezo.transactionservice.repository;

import com.buuchezo.transactionservice.entity.TanChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TanChallengeRepository
        extends JpaRepository<TanChallenge, Long> {

    Optional<TanChallenge> findByChallengeId(String challengeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t
            FROM TanChallenge t
            WHERE t.challengeId = :challengeId
              AND t.userEmail = :userEmail
            """)
    Optional<TanChallenge> findByChallengeIdAndUserEmailForUpdate(
            @Param("challengeId") String challengeId,
            @Param("userEmail") String userEmail
    );

    @Modifying
    @Query("""
            DELETE FROM TanChallenge t
            WHERE t.expiresAt < :now
               OR (t.used = true AND t.usedAt < :usedBefore)
            """)
    int deleteExpiredAndOldUsedChallenges(
            @Param("now") LocalDateTime now,
            @Param("usedBefore") LocalDateTime usedBefore
    );
}