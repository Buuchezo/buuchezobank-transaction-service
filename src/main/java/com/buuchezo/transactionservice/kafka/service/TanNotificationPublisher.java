package com.buuchezo.transactionservice.kafka.service;

import com.buuchezo.transactionservice.kafka.dto.TanNotificationEvent;
import com.buuchezo.transactionservice.entity.OutboxEvent;
import com.buuchezo.transactionservice.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TanNotificationPublisher {

    private static final String TAN_NOTIFICATION_TOPIC =
            "tan-notification-event";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void publishTanNotification(
            String userEmail,
            String operation,
            String challengeId,
            String tan,
            LocalDateTime expiresAt
    ) {

        TanNotificationEvent event =
                TanNotificationEvent.builder()
                        .eventId(
                                UUID.randomUUID()
                                        .toString()
                        )
                        .userEmail(userEmail)
                        .operation(operation)
                        .challengeId(challengeId)
                        .tan(tan)
                        .expiresAt(expiresAt)
                        .build();

        try {

            String payload =
                    objectMapper.writeValueAsString(
                            event
                    );

            OutboxEvent outboxEvent =
                    OutboxEvent.builder()
                            .eventId(
                                    UUID.fromString(
                                            event.getEventId()
                                    )
                            )
                            .topic(
                                    TAN_NOTIFICATION_TOPIC
                            )
                            .payload(payload)
                            .published(false)
                            .createdAt(
                                    LocalDateTime.now()
                            )
                            .build();

            outboxEventRepository.save(
                    outboxEvent
            );

            log.info(
                    "TAN notification stored in outbox. challengeId={}, user={}, operation={}",
                    challengeId,
                    userEmail,
                    operation
            );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to serialize TAN notification event",
                    e
            );
        }
    }
}