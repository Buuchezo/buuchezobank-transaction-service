package com.buuchezo.transactionservice.dto;

import com.buuchezo.transactionservice.enums.Channel;
import com.buuchezo.transactionservice.enums.Currency;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;
import com.buuchezo.transactionservice.enums.transaction.TransactionStatus;
import com.buuchezo.transactionservice.enums.transaction.TransactionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionDto {

    private Long id;
    private String reference;
    private String fromAccountNumber;
    private String fromBankCode;
    private String toAccountNumber;
    private String toBankCode;
    private BigDecimal amount;
    private String description;
    private Currency currency;
    private TransactionType transactionType;
    private TransactionStatus transactionStatus;
    private TransactionDirection transactionDirection;
    private Channel channel;
    private LocalDateTime createdAt;

}
