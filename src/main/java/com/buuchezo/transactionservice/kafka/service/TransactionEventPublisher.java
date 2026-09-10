package com.buuchezo.transactionservice.kafka.service;


import com.buuchezo.transactionservice.kafka.dto.BalanceUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    public void sendBalanceUpdate(BalanceUpdateEvent balanceUpdateEvent) {
        kafkaTemplate.send(
                "balance-update-events",
                balanceUpdateEvent.getAccountNumber(),
                balanceUpdateEvent
        ).whenComplete((result, ex) -> {
            if (ex == null) {
                String topicResult = result.getRecordMetadata().topic();
                Long topicOffset = result.getRecordMetadata().offset();
                log.info("Sent message to topic {} offset {}", topicResult, topicOffset);
            } else {
                log.error("Failed to send balance update event to Kafka: {}", ex.getMessage());
            }


        });
    }
}
