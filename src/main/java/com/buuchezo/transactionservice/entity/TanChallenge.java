package com.buuchezo.transactionservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "tan_challenges",
        indexes = {
                @Index(
                        name = "idx_tan_user_email",
                        columnList = "user_email"
                ),
                @Index(
                        name = "idx_tan_expires_at",
                        columnList = "expires_at"
                ),
                @Index(
                        name = "idx_tan_challenge_id",
                        columnList = "challenge_id"
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TanChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            nullable = false,
            unique = true,
            length = 64
    )
    private String challengeId;

    @Column(nullable = false)
    private String userEmail;

    @Column(nullable = false, length = 64)
    private String tanHash;

    @Column(nullable = false)
    private String operation;

    @Column(nullable = false, length = 64)
    private String transactionFingerprint;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    @Column(nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(nullable = false)
    @Builder.Default
    private int maxAttempts = 5;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime usedAt;
}