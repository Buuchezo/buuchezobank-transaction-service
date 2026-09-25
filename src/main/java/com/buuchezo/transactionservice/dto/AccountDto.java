package com.buuchezo.transactionservice.dto;

import com.buuchezo.transactionservice.enums.AccountStatus;
import com.buuchezo.transactionservice.enums.AccountType;
import com.buuchezo.transactionservice.enums.Currency;
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
public class AccountDto {

    private Long id;

    private String accountNumber;

    private BigDecimal balance;

    private Currency currency;

    private AccountType accountType;

    private AccountStatus accountStatus;

    /*
     * Personal account ownership.
     */
    private String ownerEmail;

    /*
     * PERSONAL or BUSINESS.
     */
    private String ownershipType;

    /*
     * Business account fields.
     */
    private Long businessId;

    private String businessName;

    private LocalDateTime createdAt;
}
