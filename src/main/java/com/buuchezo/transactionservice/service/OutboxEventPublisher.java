package com.buuchezo.transactionservice.service;

public interface OutboxEventPublisher {

    void publishPendingEvents();
}