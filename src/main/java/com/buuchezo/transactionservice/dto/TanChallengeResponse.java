package com.buuchezo.transactionservice.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TanChallengeResponse {

    private String challengeId;

    private String operation;

    private long expiresInSeconds;

    private String deliveryMessage;
}