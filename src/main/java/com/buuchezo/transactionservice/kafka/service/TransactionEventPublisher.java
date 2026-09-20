package com.buuchezo.transactionservice.kafka.service;

import com.buuchezo.transactionservice.kafka.dto.BalanceUpdateEvent;
import com.buuchezo.transactionservice.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEventPublisher {

    private final OutboxEventService outboxEventService;

    public void sendBalanceUpdate(
            BalanceUpdateEvent balanceUpdateEvent
    ) {

        if (balanceUpdateEvent.getEventId() == null) {
            balanceUpdateEvent.setEventId(UUID.randomUUID());
        }

        outboxEventService.saveBalanceUpdateEvent(
                balanceUpdateEvent
        );

        log.info(
                "Balance update event added to outbox. eventId={}, account={}, reference={}",
                balanceUpdateEvent.getEventId(),
                balanceUpdateEvent.getAccountNumber(),
                balanceUpdateEvent.getReference()
        );
    }
}