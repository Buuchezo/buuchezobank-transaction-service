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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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


    @Override
    @Transactional
    public ApiResponse<TransactionDto> deposit(TransactionRequest request) {

        fetchAndValidateAccount(request.getToAccountNumber());

        Transaction deposit = Transaction.builder()
                .reference("DEP" + UUID.randomUUID().toString().substring(0, 8))
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("BUUCHEZO")
                .currency(Currency.USD)
                .toAccountNumber(request.getToAccountNumber())
                .toBankCode("BUUCHEZO")
                .amount(request.getAmount())
                .transactionDirection(TransactionDirection.CREDIT)
                .channel(Channel.API)
                .description(request.getDescription())
                .transactionType(TransactionType.DEPOSIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        Transaction savedTransaction = transactionRepository.save(deposit);

        // Notify the account service to update the user account balance
        BalanceUpdateEvent balanceUpdateEvent = BalanceUpdateEvent.builder()
                .accountNumber(request.getToAccountNumber())
                .amount(request.getAmount())
                .currency(Currency.USD)
                .description(request.getDescription())
                .transactionDirection(TransactionDirection.CREDIT)
                .transactionType(TransactionType.DEPOSIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .reference(savedTransaction.getReference())
                .build();

        log.info(
                "OUTGOING BALANCE EVENT: type={}, direction={}, reference={}",
                balanceUpdateEvent.getTransactionType(),
                balanceUpdateEvent.getTransactionDirection(),
                balanceUpdateEvent.getReference()
        );
        transactionEventPublisher.sendBalanceUpdate(balanceUpdateEvent);

        return new ApiResponse<>(
                201,
                "Deposit Successful",
                modelMapper.map(savedTransaction, TransactionDto.class)
        );
    }


    @Override
    public ApiResponse<TransactionDto> transfer(TransactionRequest request) {

        if (request.getFromAccountNumber() == null ||
                request.getFromAccountNumber().isEmpty()) {

            throw new BadRequestException("From Account is Needed");
        }

        AccountDto sourceAccount =
                fetchAndValidateAccount(request.getFromAccountNumber());


        String loggedInUserEmail = null;

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null &&
                authentication.isAuthenticated()) {

            loggedInUserEmail = authentication.getName();
        }

        log.info("Auth email is: {}", loggedInUserEmail);
        log.info("Account email is: {}", sourceAccount.getOwnerEmail());


        if (!sourceAccount.getOwnerEmail().equals(loggedInUserEmail)) {

            throw new BadRequestException(
                    "Access Denied: You are not authorized to perform a transfer on behalf of another person"
            );
        }


        if (sourceAccount.getAccountStatus() != AccountStatus.ACTIVE) {

            throw new BadRequestException(
                    "Transaction Failed: Your account is inactive, please contact customer support"
            );
        }


        if (sourceAccount.getBalance().compareTo(request.getAmount()) < 0) {

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


        // Validate the destination bank account
        fetchAndValidateAccount(request.getToAccountNumber());


        Transaction transferTnx = Transaction.builder()
                .reference("TRF" + UUID.randomUUID().toString().substring(0, 8))
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("BUUCHEZO")
                .currency(Currency.USD)
                .toAccountNumber(request.getToAccountNumber())
                .toBankCode("BUUCHEZO")
                .amount(request.getAmount())
                .channel(Channel.API)
                .description(request.getDescription())
                .transactionType(TransactionType.TRANSFER)
                .transactionStatus(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();


        Transaction savedTransaction =
                transactionRepository.save(transferTnx);


        // Notify account service to DEBIT the sender
        transactionEventPublisher.sendBalanceUpdate(
                BalanceUpdateEvent.builder()
                        .accountNumber(request.getFromAccountNumber())
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(TransactionDirection.DEBIT)
                        .transactionType(TransactionType.TRANSFER)
                        .transactionStatus(TransactionStatus.SUCCESS)
                        .reference(savedTransaction.getReference())
                        .build()
        );


        // Notify account service to CREDIT the receiver
        transactionEventPublisher.sendBalanceUpdate(
                BalanceUpdateEvent.builder()
                        .accountNumber(request.getToAccountNumber())
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(TransactionDirection.CREDIT)
                        .transactionType(TransactionType.TRANSFER)
                        .transactionStatus(TransactionStatus.SUCCESS)
                        .reference(savedTransaction.getReference())
                        .build()
        );


        return new ApiResponse<>(
                201,
                "Transfer Successful",
                modelMapper.map(savedTransaction, TransactionDto.class)
        );
    }


    @Override
    public ApiResponse<TransactionDto> withdraw(TransactionRequest request) {

        AccountDto account =
                fetchAndValidateAccount(request.getFromAccountNumber());


        if (account.getAccountStatus() != AccountStatus.ACTIVE) {

            throw new BadRequestException(
                    "Inactive Account"
            );
        }


        if (account.getBalance().compareTo(request.getAmount()) < 0) {

            throw new BadRequestException(
                    "Insufficient Fund"
            );
        }


        Transaction withdrawalTxn = Transaction.builder()
                .reference("WID" + UUID.randomUUID().toString().substring(0, 8))
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("BUUCHEZO")
                .currency(Currency.USD)
                .toAccountNumber("VULT")
                .toBankCode("VULT")
                .amount(request.getAmount())
                .channel(Channel.API)
                .description(request.getDescription())

                // Withdrawal is a WITHDRAWAL, not a TRANSFER
                .transactionType(TransactionType.WITHDRAWAL)

                .transactionStatus(TransactionStatus.SUCCESS)
                .transactionDirection(TransactionDirection.DEBIT)
                .createdAt(LocalDateTime.now())
                .build();


        Transaction savedWithdrawalTnx =
                transactionRepository.save(withdrawalTxn);


        // Notify account service to DEBIT the account
        transactionEventPublisher.sendBalanceUpdate(
                BalanceUpdateEvent.builder()
                        .accountNumber(request.getFromAccountNumber())
                        .amount(request.getAmount())
                        .currency(Currency.USD)
                        .description(request.getDescription())
                        .transactionDirection(TransactionDirection.DEBIT)
                        .transactionType(TransactionType.WITHDRAWAL)
                        .transactionStatus(TransactionStatus.SUCCESS)
                        .reference(savedWithdrawalTnx.getReference())
                        .build()
        );


        return new ApiResponse<>(
                201,
                "Withdrawal Successful",
                modelMapper.map(
                        savedWithdrawalTnx,
                        TransactionDto.class
                )
        );
    }


    @Override
    public ApiResponse<TransactionDto> getTransactionByReference(
            String reference
    ) {

        log.info("reference is: {}", reference);


        Transaction txn =
                transactionRepository.findByReference(reference)
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Transaction Not Found"
                                )
                        );


        TransactionDto dto =
                modelMapper.map(
                        txn,
                        TransactionDto.class
                );


        return new ApiResponse<>(
                201,
                "Transaction Retrieved",
                dto
        );
    }


    @Override
    public ApiResponse<List<TransactionDto>>
    getAllTransactionHistoryOfAnAccountNumber(
            String accountNumber
    ) {

        List<Transaction> transactionList =
                transactionRepository.findAllByAccountNumber(
                        accountNumber
                );


        log.info(
                "transaction history count is {}",
                (long) transactionList.size()
        );


        List<TransactionDto> transactionDtos =
                transactionList.stream()
                        .map(transaction ->
                                mapTransactionForAccount(
                                        transaction,
                                        accountNumber
                                )
                        )
                        .toList();


        return new ApiResponse<>(
                201,
                "Transaction History Retrieved for the Account",
                transactionDtos
        );
    }


    @Override
    public ApiResponse<List<TransactionDto>>
    getTransactionHistory(
            String accountNumber,
            LocalDateTime start,
            LocalDateTime end
    ) {

        List<Transaction> history =
                transactionRepository.findAllAccountNumberAndDateRange(
                        accountNumber,
                        start,
                        end
                );


        List<TransactionDto> transactionDtos =
                history.stream()
                        .map(transaction ->
                                mapTransactionForAccount(
                                        transaction,
                                        accountNumber
                                )
                        )
                        .toList();


        return new ApiResponse<>(
                201,
                "Transaction History Retrieved for the Account",
                transactionDtos
        );
    }


    @Override
    public ApiResponse<List<TransactionDto>>
    getMyTransactionHistoryByDirection(
            String accountNumber,
            TransactionDirection direction
    ) {

        List<Transaction> transactions =
                direction.equals(TransactionDirection.DEBIT)
                        ? transactionRepository.findByFromAccountNumber(
                        accountNumber
                )
                        : transactionRepository.findByToAccountNumber(
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
                201,
                "Transaction History Retrieved by direction for the Account",
                transactionDtos
        );
    }


    /**
     * Maps a transaction from the perspective of the account
     * whose transaction history is being requested.
     *
     * For example:
     *
     * Sender:
     * 0033143527 -> DEBIT
     *
     * Receiver:
     * 0008265619 -> CREDIT
     *
     * This is important because a transfer is stored as one
     * transaction containing both the sender and receiver.
     */
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


    private AccountDto fetchAndValidateAccount(
            String accountNumber
    ) {

        ApiResponse<AccountDto> response =
                accountFeignClient.getAccountByNumber(
                        accountNumber
                );


        if (response == null ||
                response.data() == null) {

            throw new NotFoundException(
                    "Account " + accountNumber + "not found"
            );
        }


        AccountDto account = response.data();


        if (account.getAccountStatus()
                .equals(AccountStatus.CLOSED)) {

            throw new BadRequestException(
                    "Transaction Denied: Account is Closed"
            );
        }


        return account;
    }
}