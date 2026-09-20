package com.buuchezo.transactionservice.service.impl;

import com.buuchezo.transactionservice.dto.AccountDto;
import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TransactionDto;
import com.buuchezo.transactionservice.dto.TransactionRequest;
import com.buuchezo.transactionservice.entity.Transaction;
import com.buuchezo.transactionservice.enums.AccountStatus;
import com.buuchezo.transactionservice.enums.Channel;
import com.buuchezo.transactionservice.enums.Currency;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;
import com.buuchezo.transactionservice.enums.transaction.TransactionStatus;
import com.buuchezo.transactionservice.enums.transaction.TransactionType;
import com.buuchezo.transactionservice.exceptions.BadRequestException;
import com.buuchezo.transactionservice.exceptions.NotFoundException;
import com.buuchezo.transactionservice.feign.AccountFeignClient;
import com.buuchezo.transactionservice.kafka.dto.BalanceUpdateEvent;
import com.buuchezo.transactionservice.kafka.service.TransactionEventPublisher;
import com.buuchezo.transactionservice.repository.TransactionRepository;
import com.buuchezo.transactionservice.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountFeignClient accountFeignClient;
    private final ModelMapper modelMapper;
    private final TransactionEventPublisher transactionEventPublisher;


    // =========================================================
    // DEPOSIT
    // =========================================================

    @Override
    @Transactional
    public ApiResponse<TransactionDto> deposit(TransactionRequest request) {

        fetchAndValidateAccount(request.getToAccountNumber());

        Transaction deposit = Transaction.builder()
                .reference(
                        "DEP" +
                                UUID.randomUUID()
                                        .toString()
                                        .substring(0, 8)
                )
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("BUCHEZO")
                .currency(Currency.USD)
                .toAccountNumber(request.getToAccountNumber())
                .toBankCode("BUCHEZO")
                .amount(request.getAmount())
                .transactionDirection(TransactionDirection.CREDIT)
                .channel(Channel.API)
                .description(request.getDescription())
                .transactionType(TransactionType.DEPOSIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        Transaction savedTransaction =
                transactionRepository.save(deposit);

        /*
         * Every balance event gets its own unique eventId.
         */
        BalanceUpdateEvent balanceUpdateEvent =
                BalanceUpdateEvent.builder()
                        .eventId(UUID.randomUUID())
                        .accountNumber(
                                request.getToAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(
                                TransactionDirection.CREDIT
                        )
                        .transactionType(
                                TransactionType.DEPOSIT
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .reference(
                                savedTransaction.getReference()
                        )
                        .build();

        log.info(
                "OUTGOING BALANCE EVENT: eventId={}, type={}, direction={}, reference={}",
                balanceUpdateEvent.getEventId(),
                balanceUpdateEvent.getTransactionType(),
                balanceUpdateEvent.getTransactionDirection(),
                balanceUpdateEvent.getReference()
        );

        transactionEventPublisher.sendBalanceUpdate(
                balanceUpdateEvent
        );

        return new ApiResponse<>(
                201,
                "Deposit Successful",
                modelMapper.map(
                        savedTransaction,
                        TransactionDto.class
                )
        );
    }


    // =========================================================
    // TRANSFER
    // =========================================================

    @Override
    @Transactional
    public ApiResponse<TransactionDto> transfer(
            TransactionRequest request
    ) {

        if (request.getFromAccountNumber() == null ||
                request.getFromAccountNumber().isBlank()) {

            throw new BadRequestException(
                    "From Account is Needed"
            );
        }

        AccountDto sourceAccount =
                fetchAndValidateAccount(
                        request.getFromAccountNumber()
                );

        validateAccountOwnership(sourceAccount);

        if (sourceAccount.getAccountStatus()
                != AccountStatus.ACTIVE) {

            throw new BadRequestException(
                    "Transaction Failed: Your account is inactive, please contact customer support"
            );
        }

        if (sourceAccount.getBalance() == null) {

            throw new BadRequestException(
                    "Transaction Failed: Account balance is unavailable"
            );
        }

        if (sourceAccount.getBalance()
                .compareTo(request.getAmount()) < 0) {

            throw new BadRequestException(
                    "Insufficient Account Balance"
            );
        }

        if (request.getFromAccountNumber()
                .equals(request.getToAccountNumber())) {

            throw new BadRequestException(
                    "You cannot transfer to the same account number(Yourself)"
            );
        }

        fetchAndValidateAccount(
                request.getToAccountNumber()
        );

        Transaction transferTransaction =
                Transaction.builder()
                        .reference(
                                "TRF" +
                                        UUID.randomUUID()
                                                .toString()
                                                .substring(0, 8)
                        )
                        .fromAccountNumber(
                                request.getFromAccountNumber()
                        )
                        .fromBankCode("BUCHEZO")
                        .currency(Currency.USD)
                        .toAccountNumber(
                                request.getToAccountNumber()
                        )
                        .toBankCode("BUCHEZO")
                        .amount(request.getAmount())
                        .channel(Channel.API)
                        .description(request.getDescription())
                        .transactionType(
                                TransactionType.TRANSFER
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .createdAt(LocalDateTime.now())
                        .build();

        Transaction savedTransaction =
                transactionRepository.save(
                        transferTransaction
                );


        /*
         * IMPORTANT:
         *
         * A transfer creates TWO balance events.
         *
         * Event 1:
         * Sender gets DEBITED.
         *
         * Event 2:
         * Receiver gets CREDITED.
         *
         * Therefore they MUST have different event IDs.
         */

        UUID debitEventId = UUID.randomUUID();
        UUID creditEventId = UUID.randomUUID();


        // -----------------------------------------------------
        // DEBIT SENDER
        // -----------------------------------------------------

        BalanceUpdateEvent debitEvent =
                BalanceUpdateEvent.builder()
                        .eventId(debitEventId)
                        .accountNumber(
                                request.getFromAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(
                                TransactionDirection.DEBIT
                        )
                        .transactionType(
                                TransactionType.TRANSFER
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .reference(
                                savedTransaction.getReference()
                        )
                        .build();

        log.info(
                "OUTGOING DEBIT EVENT: eventId={}, account={}, reference={}",
                debitEvent.getEventId(),
                debitEvent.getAccountNumber(),
                debitEvent.getReference()
        );

        transactionEventPublisher.sendBalanceUpdate(
                debitEvent
        );


        // -----------------------------------------------------
        // CREDIT RECEIVER
        // -----------------------------------------------------

        BalanceUpdateEvent creditEvent =
                BalanceUpdateEvent.builder()
                        .eventId(creditEventId)
                        .accountNumber(
                                request.getToAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(
                                TransactionDirection.CREDIT
                        )
                        .transactionType(
                                TransactionType.TRANSFER
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .reference(
                                savedTransaction.getReference()
                        )
                        .build();

        log.info(
                "OUTGOING CREDIT EVENT: eventId={}, account={}, reference={}",
                creditEvent.getEventId(),
                creditEvent.getAccountNumber(),
                creditEvent.getReference()
        );

        transactionEventPublisher.sendBalanceUpdate(
                creditEvent
        );


        return new ApiResponse<>(
                201,
                "Transfer Successful",
                modelMapper.map(
                        savedTransaction,
                        TransactionDto.class
                )
        );
    }


    // =========================================================
    // WITHDRAW
    // =========================================================

    @Override
    @Transactional
    public ApiResponse<TransactionDto> withdraw(
            TransactionRequest request
    ) {

        AccountDto account =
                fetchAndValidateAccount(
                        request.getFromAccountNumber()
                );

        validateAccountOwnership(account);

        if (account.getAccountStatus()
                != AccountStatus.ACTIVE) {

            throw new BadRequestException(
                    "Inactive Account"
            );
        }

        if (account.getBalance() == null) {

            throw new BadRequestException(
                    "Transaction Failed: Account balance is unavailable"
            );
        }

        if (account.getBalance()
                .compareTo(request.getAmount()) < 0) {

            throw new BadRequestException(
                    "Insufficient Fund"
            );
        }

        Transaction withdrawal =
                Transaction.builder()
                        .reference(
                                "WTH" +
                                        UUID.randomUUID()
                                                .toString()
                                                .substring(0, 8)
                        )
                        .fromAccountNumber(
                                request.getFromAccountNumber()
                        )
                        .fromBankCode("BUCHEZO")
                        .currency(Currency.USD)
                        .toAccountNumber("VULT")
                        .toBankCode("BUCHEZO")
                        .amount(request.getAmount())
                        .channel(Channel.API)
                        .description(request.getDescription())
                        .transactionType(
                                TransactionType.WITHDRAWAL
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .createdAt(LocalDateTime.now())
                        .build();

        Transaction savedTransaction =
                transactionRepository.save(withdrawal);


        BalanceUpdateEvent withdrawalEvent =
                BalanceUpdateEvent.builder()
                        .eventId(UUID.randomUUID())
                        .accountNumber(
                                request.getFromAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(
                                TransactionDirection.DEBIT
                        )
                        .transactionType(
                                TransactionType.WITHDRAWAL
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .reference(
                                savedTransaction.getReference()
                        )
                        .build();

        log.info(
                "OUTGOING WITHDRAWAL EVENT: eventId={}, account={}, reference={}",
                withdrawalEvent.getEventId(),
                withdrawalEvent.getAccountNumber(),
                withdrawalEvent.getReference()
        );

        transactionEventPublisher.sendBalanceUpdate(
                withdrawalEvent
        );

        return new ApiResponse<>(
                201,
                "Withdrawal Successful",
                modelMapper.map(
                        savedTransaction,
                        TransactionDto.class
                )
        );
    }


    // =========================================================
    // GET TRANSACTION BY REFERENCE
    // =========================================================

    @Override
    public ApiResponse<TransactionDto> getTransactionByReference(
            String reference
    ) {

        Transaction transaction =
                transactionRepository
                        .findByReference(reference)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "Transaction not found"
                                )
                        );

        validateTransactionOwnership(transaction);

        return new ApiResponse<>(
                200,
                "Transaction found",
                modelMapper.map(
                        transaction,
                        TransactionDto.class
                )
        );
    }


    // =========================================================
    // TRANSACTION HISTORY
    // =========================================================

    @Override
    public ApiResponse<List<TransactionDto>> getTransactionHistory(
            String accountNumber,
            LocalDateTime startDate,
            LocalDateTime endDate
    ) {

        AccountDto account =
                fetchAndValidateAccount(accountNumber);

        validateAccountOwnership(account);

        List<Transaction> transactions =
                transactionRepository
                        .findAllAccountNumberAndDateRange(
                                accountNumber,
                                startDate,
                                endDate
                        );

        List<TransactionDto> transactionDtos =
                transactions.stream()
                        .map(transaction ->
                                mapTransactionForAccount(
                                        transaction,
                                        accountNumber
                                )
                        )
                        .toList();

        return new ApiResponse<>(
                200,
                "Transaction history retrieved successfully",
                transactionDtos
        );
    }


    // =========================================================
    // TRANSACTION HISTORY BY DIRECTION
    // =========================================================

    @Override
    public ApiResponse<List<TransactionDto>>
    getMyTransactionHistoryByDirection(
            String accountNumber,
            TransactionDirection direction
    ) {

        AccountDto account =
                fetchAndValidateAccount(accountNumber);

        validateAccountOwnership(account);

        List<Transaction> transactions;

        if (direction == TransactionDirection.DEBIT) {

            transactions =
                    transactionRepository
                            .findByFromAccountNumber(
                                    accountNumber
                            );

        } else {

            transactions =
                    transactionRepository
                            .findByToAccountNumber(
                                    accountNumber
                            );
        }

        List<TransactionDto> transactionDtos =
                transactions.stream()
                        .map(transaction ->
                                mapTransactionForAccount(
                                        transaction,
                                        accountNumber
                                )
                        )
                        .toList();

        return new ApiResponse<>(
                200,
                "Transaction history retrieved successfully",
                transactionDtos
        );
    }


    // =========================================================
    // MAP TRANSACTION FOR ACCOUNT
    // =========================================================

    private TransactionDto mapTransactionForAccount(
            Transaction transaction,
            String accountNumber
    ) {

        TransactionDto dto =
                modelMapper.map(
                        transaction,
                        TransactionDto.class
                );

        if (accountNumber.equals(
                transaction.getFromAccountNumber()
        )) {

            dto.setTransactionDirection(
                    TransactionDirection.DEBIT
            );

        } else if (accountNumber.equals(
                transaction.getToAccountNumber()
        )) {

            dto.setTransactionDirection(
                    TransactionDirection.CREDIT
            );
        }

        return dto;
    }


    // =========================================================
    // ACCOUNT VALIDATION
    // =========================================================

    private AccountDto fetchAndValidateAccount(
            String accountNumber
    ) {

        if (accountNumber == null ||
                accountNumber.isBlank()) {

            throw new BadRequestException(
                    "Account number is required"
            );
        }

        ApiResponse<AccountDto> response =
                accountFeignClient.getAccountByNumber(
                        accountNumber
                );

        if (response == null ||
                response.data() == null) {

            throw new NotFoundException(
                    "Account " +
                            accountNumber +
                            " not found"
            );
        }

        AccountDto account =
                response.data();

        if (account.getAccountStatus()
                == AccountStatus.CLOSED) {

            throw new BadRequestException(
                    "Transaction Denied: Account is Closed"
            );
        }

        return account;
    }


    // =========================================================
    // ACCOUNT OWNERSHIP
    // =========================================================

    private void validateAccountOwnership(
            AccountDto account
    ) {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated()) {

            throw new AccessDeniedException(
                    "Authentication required"
            );
        }

        String loggedInEmail =
                authentication.getName();

        if (account.getOwnerEmail() == null ||
                !account.getOwnerEmail()
                        .equalsIgnoreCase(
                                loggedInEmail
                        )) {

            throw new AccessDeniedException(
                    "You are not authorized to access this account"
            );
        }
    }


    // =========================================================
    // TRANSACTION OWNERSHIP
    // =========================================================

    private void validateTransactionOwnership(
            Transaction transaction
    ) {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated()) {

            throw new AccessDeniedException(
                    "Authentication required"
            );
        }

        String loggedInEmail =
                authentication.getName();

        boolean admin =
                authentication.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(authority ->
                                authority.equals("ROLE_ADMIN")
                                        ||
                                        authority.equals("ADMIN")
                        );

        if (admin) {
            return;
        }

        AccountDto fromAccount = null;

        if (transaction.getFromAccountNumber() != null) {

            try {

                fromAccount =
                        accountFeignClient
                                .getAccountByNumber(
                                        transaction
                                                .getFromAccountNumber()
                                )
                                .data();

            } catch (Exception ignored) {
            }
        }

        AccountDto toAccount = null;

        if (transaction.getToAccountNumber() != null &&
                !"VULT".equals(
                        transaction.getToAccountNumber()
                )) {

            try {

                toAccount =
                        accountFeignClient
                                .getAccountByNumber(
                                        transaction
                                                .getToAccountNumber()
                                )
                                .data();

            } catch (Exception ignored) {
            }
        }

        boolean ownsFromAccount =
                fromAccount != null &&
                        loggedInEmail.equalsIgnoreCase(
                                fromAccount.getOwnerEmail()
                        );

        boolean ownsToAccount =
                toAccount != null &&
                        loggedInEmail.equalsIgnoreCase(
                                toAccount.getOwnerEmail()
                        );

        if (!ownsFromAccount && !ownsToAccount) {

            throw new AccessDeniedException(
                    "You are not authorized to access this transaction"
            );
        }
    }

    // =========================================================
// ALL TRANSACTION HISTORY
// =========================================================

    @Override
    public ApiResponse<List<TransactionDto>>
    getAllTransactionHistoryOfAnAccountNumber(
            String accountNumber
    ) {

        AccountDto account =
                fetchAndValidateAccount(accountNumber);

        validateAccountOwnership(account);

        List<Transaction> transactions =
                transactionRepository.findAllByAccountNumber(
                        accountNumber
                );

        List<TransactionDto> transactionDtos =
                transactions.stream()
                        .map(transaction ->
                                mapTransactionForAccount(
                                        transaction,
                                        accountNumber
                                )
                        )
                        .toList();

        return new ApiResponse<>(
                200,
                "All transaction history retrieved successfully",
                transactionDtos
        );
    }
}