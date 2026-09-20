package com.buuchezo.transactionservice.service.impl;

import com.buuchezo.transactionservice.entity.TanChallenge;
import com.buuchezo.transactionservice.exceptions.BadRequestException;
import com.buuchezo.transactionservice.repository.TanChallengeRepository;
import com.buuchezo.transactionservice.service.TanAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TanAttemptServiceImpl implements TanAttemptService {

    private final TanChallengeRepository tanChallengeRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(
            String challengeId,
            String userEmail
    ) {

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

        if (challenge.isUsed()) {
            return;
        }

        int newAttemptCount =
                challenge.getFailedAttempts() + 1;

        challenge.setFailedAttempts(newAttemptCount);

        tanChallengeRepository.save(challenge);

        log.warn(
                "Failed TAN attempt. challengeId={}, user={}, attempts={}/{}",
                challengeId,
                userEmail,
                newAttemptCount,
                challenge.getMaxAttempts()
        );
    }
}