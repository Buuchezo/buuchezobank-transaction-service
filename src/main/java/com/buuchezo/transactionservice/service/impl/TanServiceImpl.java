package com.buuchezo.transactionservice.service.impl;

import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TanChallengeResponse;
import com.buuchezo.transactionservice.entity.TanChallenge;
import com.buuchezo.transactionservice.exceptions.BadRequestException;
import com.buuchezo.transactionservice.kafka.service.TanNotificationPublisher;
import com.buuchezo.transactionservice.repository.TanChallengeRepository;
import com.buuchezo.transactionservice.service.TanAttemptService;
import com.buuchezo.transactionservice.service.TanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TanServiceImpl implements TanService {

    private static final int MAX_ATTEMPTS = 5;

    private final TanChallengeRepository tanChallengeRepository;
    private final TanAttemptService tanAttemptService;
    private final TanNotificationPublisher tanNotificationPublisher;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public ApiResponse<TanChallengeResponse> createChallenge(
            String userEmail,
            String operation,
            String transactionFingerprint
    ) {

        if (userEmail == null || userEmail.isBlank()) {
            throw new BadRequestException(
                    "Authenticated user is required"
            );
        }

        if (operation == null || operation.isBlank()) {
            throw new BadRequestException(
                    "Operation is required"
            );
        }

        if (
                transactionFingerprint == null ||
                        transactionFingerprint.isBlank()
        ) {
            throw new BadRequestException(
                    "Transaction fingerprint is required"
            );
        }

        String tan = generateTan();

        String challengeId =
                UUID.randomUUID().toString();

        int expirationMinutes =
                getExpirationMinutes();

        LocalDateTime now =
                LocalDateTime.now();

        LocalDateTime expiresAt =
                now.plusMinutes(expirationMinutes);

        TanChallenge challenge =
                TanChallenge.builder()
                        .challengeId(challengeId)
                        .userEmail(userEmail)
                        .tanHash(hash(tan))
                        .operation(operation)
                        .transactionFingerprint(
                                transactionFingerprint
                        )
                        .expiresAt(expiresAt)
                        .used(false)
                        .failedAttempts(0)
                        .maxAttempts(MAX_ATTEMPTS)
                        .createdAt(now)
                        .build();

        tanChallengeRepository.save(challenge);

        /*
         * IMPORTANT:
         * The plaintext TAN is intentionally NOT logged.
         */

        tanNotificationPublisher.publishTanNotification(
                userEmail,
                operation,
                challengeId,
                tan,
                expiresAt
        );

        long expiresInSeconds =
                Duration.between(
                        now,
                        expiresAt
                ).getSeconds();

        TanChallengeResponse response =
                TanChallengeResponse.builder()
                        .challengeId(challengeId)
                        .operation(operation)
                        .expiresInSeconds(
                                expiresInSeconds
                        )
                        .deliveryMessage(
                                "TAN has been sent to your registered notification channels."
                        )
                        .build();

        return new ApiResponse<>(
                HttpStatus.OK.value(),
                "TAN challenge created successfully",
                response
        );
    }

    @Override
    @Transactional
    public void verifyAndConsume(
            String userEmail,
            String challengeId,
            String tan,
            String operation,
            String transactionFingerprint
    ) {

        if (
                userEmail == null ||
                        userEmail.isBlank()
        ) {
            throw new BadRequestException(
                    "Authenticated user is required"
            );
        }

        if (
                challengeId == null ||
                        challengeId.isBlank()
        ) {
            throw new BadRequestException(
                    "TAN challenge ID is required"
            );
        }

        if (
                tan == null ||
                        !tan.matches("\\d{6}")
        ) {
            throw new BadRequestException(
                    "TAN must contain exactly 6 digits"
            );
        }

        TanChallenge challenge =
                tanChallengeRepository
                        .findByChallengeIdAndUserEmailForUpdate(
                                challengeId,
                                userEmail
                        )
                        .orElseThrow(() ->
                                new BadRequestException(
                                        "Invalid TAN challenge"
                                )
                        );

        LocalDateTime now =
                LocalDateTime.now();

        if (challenge.isUsed()) {
            throw new BadRequestException(
                    "TAN challenge has already been used"
            );
        }

        if (
                challenge.getExpiresAt() == null ||
                        challenge.getExpiresAt().isBefore(now)
        ) {
            throw new BadRequestException(
                    "TAN challenge has expired"
            );
        }

        if (
                challenge.getFailedAttempts()
                        >= challenge.getMaxAttempts()
        ) {
            throw new BadRequestException(
                    "Maximum TAN attempts exceeded"
            );
        }

        if (
                !challenge.getOperation()
                        .equals(operation)
        ) {
            throw new BadRequestException(
                    "TAN is not valid for this operation"
            );
        }

        if (
                !challenge.getTransactionFingerprint()
                        .equals(transactionFingerprint)
        ) {
            throw new BadRequestException(
                    "TAN is not valid for this transaction"
            );
        }

        String suppliedHash =
                hash(tan);

        boolean valid =
                MessageDigest.isEqual(
                        suppliedHash.getBytes(
                                StandardCharsets.UTF_8
                        ),
                        challenge.getTanHash().getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        if (!valid) {

            tanAttemptService.recordFailedAttempt(
                    challengeId,
                    userEmail
            );

            int attemptsRemaining =
                    Math.max(
                            0,
                            challenge.getMaxAttempts()
                                    - challenge.getFailedAttempts()
                                    - 1
                    );

            if (attemptsRemaining == 0) {
                throw new BadRequestException(
                        "Maximum TAN attempts exceeded"
                );
            }

            throw new BadRequestException(
                    "Invalid TAN. Attempts remaining: "
                            + attemptsRemaining
            );
        }

        challenge.setUsed(true);
        challenge.setUsedAt(now);

        tanChallengeRepository.save(challenge);

        log.info(
                "TAN successfully consumed. challengeId={}, user={}, operation={}",
                challengeId,
                userEmail,
                operation
        );
    }

    private String generateTan() {

        return String.format(
                "%06d",
                secureRandom.nextInt(1_000_000)
        );
    }

    private String hash(String value) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of().formatHex(
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }

    private int getExpirationMinutes() {

        String configured =
                System.getProperty(
                        "tan.expiration-minutes"
                );

        if (
                configured == null ||
                        configured.isBlank()
        ) {
            return 5;
        }

        try {
            return Integer.parseInt(
                    configured
            );
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    @Scheduled(
            fixedDelayString =
                    "${tan.cleanup.fixed-delay-ms:3600000}"
    )
    @Transactional
    public void cleanupChallenges() {

        LocalDateTime now =
                LocalDateTime.now();

        LocalDateTime usedBefore =
                now.minusHours(24);

        int deleted =
                tanChallengeRepository
                        .deleteExpiredAndOldUsedChallenges(
                                now,
                                usedBefore
                        );

        if (deleted > 0) {

            log.info(
                    "Cleaned up {} TAN challenge(s)",
                    deleted
            );
        }
    }
}