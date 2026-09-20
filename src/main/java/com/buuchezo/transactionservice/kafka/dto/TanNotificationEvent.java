package com.buuchezo.transactionservice.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TanNotificationEvent {

    private String eventId;

    private String userEmail;

    private String operation;

    private String challengeId;

    private String tan;

    private LocalDateTime expiresAt;
}