package com.buuchezo.transactionservice.service.impl;

import com.buuchezo.transactionservice.entity.OutboxEvent;
import com.buuchezo.transactionservice.repository.OutboxEventRepository;
import com.buuchezo.transactionservice.service.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisherImpl implements OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay-ms:1000}"
    )
    @Transactional
    public void publishPendingEvents() {

        List<OutboxEvent> events =
                outboxEventRepository
                        .findTop100ByPublishedFalseOrderByCreatedAtAsc();

        if (events.isEmpty()) {
            return;
        }

        log.info(
                "Found {} unpublished outbox event(s) to publish",
                events.size()
        );

        for (OutboxEvent event : events) {

            try {

                kafkaTemplate
                        .send(
                                event.getTopic(),
                                event.getEventId().toString(),
                                event.getPayload()
                        )
                        .get();

                event.setPublished(true);
                event.setPublishedAt(LocalDateTime.now());

                outboxEventRepository.save(event);

                log.info(
                        "Outbox event published successfully. " +
                                "eventId={}, topic={}",
                        event.getEventId(),
                        event.getTopic()
                );

            } catch (Exception e) {

                log.error(
                        "Failed to publish outbox event. " +
                                "eventId={}, topic={}. " +
                                "Event will be retried.",
                        event.getEventId(),
                        event.getTopic(),
                        e
                );
            }
        }
    }
}