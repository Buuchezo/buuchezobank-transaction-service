package com.buuchezo.transactionservice.entity;

import com.buuchezo.transactionservice.enums.Channel;
import com.buuchezo.transactionservice.enums.Currency;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;
import com.buuchezo.transactionservice.enums.transaction.TransactionStatus;
import com.buuchezo.transactionservice.enums.transaction.TransactionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;


    private String fromAccountNumber;

    private String fromBankCode;

    @Column(nullable = false)
    private String toAccountNumber;

    private String toBankCode;

    private BigDecimal amount;

    private String description;

    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    private TransactionStatus transactionStatus;

    @Enumerated(EnumType.STRING)
    private TransactionDirection transactionDirection;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
