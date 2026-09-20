package com.buuchezo.transactionservice.service;

public interface TanAttemptService {

    void recordFailedAttempt(
            String challengeId,
            String userEmail
    );
}