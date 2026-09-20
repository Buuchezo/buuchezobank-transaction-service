package com.buuchezo.transactionservice.service;

import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TanChallengeResponse;

public interface TanService {

    ApiResponse<TanChallengeResponse> createChallenge(
            String userEmail,
            String operation,
            String transactionFingerprint
    );

    void verifyAndConsume(
            String userEmail,
            String challengeId,
            String tan,
            String operation,
            String transactionFingerprint
    );
}