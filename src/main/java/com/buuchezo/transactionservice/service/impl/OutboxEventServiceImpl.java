package com.buuchezo.transactionservice.service.impl;

import com.buuchezo.transactionservice.entity.OutboxEvent;
import com.buuchezo.transactionservice.kafka.dto.BalanceUpdateEvent;
import com.buuchezo.transactionservice.repository.OutboxEventRepository;
import com.buuchezo.transactionservice.service.OutboxEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final String BALANCE_UPDATE_TOPIC =
            "balance-update-events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void saveBalanceUpdateEvent(BalanceUpdateEvent event) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Balance update event must not be null"
            );
        }

        if (event.getEventId() == null) {
            throw new IllegalArgumentException(
                    "Balance update event must contain an eventId"
            );
        }

        try {
            String payload = objectMapper.writeValueAsString(event);

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventId(event.getEventId())
                    .topic(BALANCE_UPDATE_TOPIC)
                    .payload(payload)
                    .published(false)
                    .build();

            outboxEventRepository.save(outboxEvent);

            log.info(
                    "Balance update event stored in outbox. " +
                            "eventId={}, reference={}, account={}",
                    event.getEventId(),
                    event.getReference(),
                    event.getAccountNumber()
            );

        } catch (JsonProcessingException e) {

            log.error(
                    "Failed to serialize balance update event. " +
                            "eventId={}, reference={}",
                    event.getEventId(),
                    event.getReference(),
                    e
            );

            throw new IllegalStateException(
                    "Failed to serialize balance update event",
                    e
            );
        }
    }
}