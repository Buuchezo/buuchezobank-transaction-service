package com.buuchezo.transactionservice.kafka.dto;

import com.buuchezo.transactionservice.enums.Currency;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;
import com.buuchezo.transactionservice.enums.transaction.TransactionStatus;
import com.buuchezo.transactionservice.enums.transaction.TransactionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BalanceUpdateEvent {
    // core transaction used by account service
    private String accountNumber;
    private BigDecimal amount;
    private TransactionDirection transactionDirection;
    private TransactionStatus transactionStatus;
    private TransactionType transactionType;
    private String reference;
    private Currency currency;


    // core transaction used by notification service
    private String email;
    private String firstName;
    private String lastName;
    private BigDecimal currentBalance;
    private String description;

}
