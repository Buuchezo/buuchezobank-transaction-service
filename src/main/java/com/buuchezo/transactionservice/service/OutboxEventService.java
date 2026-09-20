package com.buuchezo.transactionservice.service;

import com.buuchezo.transactionservice.kafka.dto.BalanceUpdateEvent;

public interface OutboxEventService {

    void saveBalanceUpdateEvent(BalanceUpdateEvent event);
}