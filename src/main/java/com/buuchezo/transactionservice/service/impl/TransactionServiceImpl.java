package com.buuchezo.transactionservice.service.impl;

import com.buuchezo.transactionservice.dto.AccountDto;
import com.buuchezo.transactionservice.dto.BusinessMembershipDto;
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
import com.buuchezo.transactionservice.service.TanService;
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



import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.apache.commons.codec.digest.DigestUtils.sha256;


@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountFeignClient accountFeignClient;
    private final ModelMapper modelMapper;
    private final TransactionEventPublisher transactionEventPublisher;
    private final TanService tanService;


    // =========================================================
    // DEPOSIT
    // =========================================================

    @Override
    @Transactional
    public ApiResponse<TransactionDto> deposit(TransactionRequest request) {

        AccountDto destinationAccount =
                fetchAndValidateAccount(
                        request.getToAccountNumber()
                );

        Transaction deposit = Transaction.builder()
                .reference(
                        "DEP" +
                                UUID.randomUUID()
                                        .toString()
                                        .substring(0, 8)
                )
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("BUCHEZO")
                .currency(destinationAccount.getCurrency())
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
                        .currency(destinationAccount.getCurrency())
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

        if (request.getToAccountNumber() == null ||
                request.getToAccountNumber().isBlank()) {

            throw new BadRequestException(
                    "To Account is Needed"
            );
        }

        if (request.getAmount() == null ||
                request.getAmount().signum() <= 0) {

            throw new BadRequestException(
                    "Transfer amount must be greater than zero"
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

        // Validate destination account.

        AccountDto destinationAccount =
                fetchAndValidateAccount(
                        request.getToAccountNumber()
                );

        if (sourceAccount.getCurrency() == null ||
                destinationAccount.getCurrency() == null) {

            throw new BadRequestException(
                    "Transaction Failed: Account currency is unavailable"
            );
        }

        if (sourceAccount.getCurrency()
                != destinationAccount.getCurrency()) {

            throw new BadRequestException(
                    "Currency mismatch: source and destination accounts must use the same currency"
            );
        }

        /*
         * =========================================================
         * AUTHENTICATED USER
         * =========================================================
         */

        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated()) {

            throw new BadRequestException(
                    "Authenticated user is required"
            );
        }

        String loggedInUserEmail =
                authentication.getName();

        /*
         * =========================================================
         * TAN TRANSACTION FINGERPRINT
         * =========================================================
         *
         * The TAN is tied to:
         *
         * user
         * from account
         * destination account
         * amount
         * description
         *
         * Therefore a TAN generated for one transfer cannot
         * authorize a modified transfer.
         */

        String transactionFingerprint =
                createTransferFingerprint(
                        loggedInUserEmail,
                        request
                );

        /*
         * =========================================================
         * TAN VERIFICATION
         * =========================================================
         *
         * This happens BEFORE:
         *
         * - transaction persistence
         * - debit event
         * - credit event
         *
         * If TAN verification fails, the transfer stops here.
         */

        tanService.verifyAndConsume(
                loggedInUserEmail,
                request.getTanChallengeId(),
                request.getTan(),
                "TRANSFER",
                transactionFingerprint
        );

        /*
         * =========================================================
         * CREATE TRANSACTION
         * =========================================================
         */

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
                        .currency(sourceAccount.getCurrency())
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
         * =========================================================
         * DEBIT SENDER
         * =========================================================
         *
         * IMPORTANT:
         *
         * The sender and receiver must have different event IDs.
         */

        UUID debitEventId =
                UUID.randomUUID();

        BalanceUpdateEvent debitEvent =
                BalanceUpdateEvent.builder()
                        .eventId(debitEventId)
                        .accountNumber(
                                request.getFromAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(sourceAccount.getCurrency())
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

        /*
         * =========================================================
         * CREDIT RECEIVER
         * =========================================================
         */

        UUID creditEventId =
                UUID.randomUUID();

        BalanceUpdateEvent creditEvent =
                BalanceUpdateEvent.builder()
                        .eventId(creditEventId)
                        .accountNumber(
                                request.getToAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(destinationAccount.getCurrency())
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

        if (request.getFromAccountNumber() == null ||
                request.getFromAccountNumber().isBlank()) {

            throw new BadRequestException(
                    "From Account is Needed"
            );
        }

        if (request.getAmount() == null ||
                request.getAmount().signum() <= 0) {

            throw new BadRequestException(
                    "Withdrawal amount must be greater than zero"
            );
        }

        /*
         * =========================================================
         * LOAD ACCOUNT
         * =========================================================
         */

        AccountDto account =
                fetchAndValidateAccount(
                        request.getFromAccountNumber()
                );

        /*
         * =========================================================
         * ACCOUNT VALIDATION
         * =========================================================
         */

        validateAccountOwnership(account);

        if (account.getAccountStatus()
                != AccountStatus.ACTIVE) {

            throw new BadRequestException(
                    "Transaction Failed: Your account is inactive, please contact customer support"
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

        /*
         * =========================================================
         * AUTHENTICATED USER
         * =========================================================
         */

        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated()) {

            throw new BadRequestException(
                    "Authenticated user is required"
            );
        }

        String loggedInUserEmail =
                authentication.getName();

        /*
         * =========================================================
         * TAN FINGERPRINT
         * =========================================================
         */

        String transactionFingerprint =
                createWithdrawalFingerprint(
                        loggedInUserEmail,
                        request
                );

        /*
         * =========================================================
         * TAN VERIFICATION
         * =========================================================
         *
         * TAN MUST BE VALID BEFORE WE create the withdrawal.
         */

        tanService.verifyAndConsume(
                loggedInUserEmail,
                request.getTanChallengeId(),
                request.getTan(),
                "WITHDRAWAL",
                transactionFingerprint
        );

        /*
         * =========================================================
         * CREATE WITHDRAWAL TRANSACTION
         * =========================================================
         */

        Transaction withdrawalTransaction =
                Transaction.builder()
                        .reference(
                                "WID" +
                                        UUID.randomUUID()
                                                .toString()
                                                .substring(0, 8)
                        )
                        .fromAccountNumber(
                                request.getFromAccountNumber()
                        )
                        .fromBankCode("BUCHEZO")
                        .currency(account.getCurrency())
                        .toAccountNumber("VULT")
                        .toBankCode("VULT")
                        .amount(request.getAmount())
                        .channel(Channel.API)
                        .description(request.getDescription())
                        .transactionType(
                                TransactionType.WITHDRAWAL
                        )
                        .transactionStatus(
                                TransactionStatus.SUCCESS
                        )
                        .transactionDirection(
                                TransactionDirection.DEBIT
                        )
                        .createdAt(LocalDateTime.now())
                        .build();

        Transaction savedWithdrawalTransaction =
                transactionRepository.save(
                        withdrawalTransaction
                );

        /*
         * =========================================================
         * DEBIT ACCOUNT
         * =========================================================
         */

        UUID debitEventId =
                UUID.randomUUID();

        BalanceUpdateEvent debitEvent =
                BalanceUpdateEvent.builder()
                        .eventId(debitEventId)
                        .accountNumber(
                                request.getFromAccountNumber()
                        )
                        .amount(request.getAmount())
                        .currency(account.getCurrency())
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
                                savedWithdrawalTransaction
                                        .getReference()
                        )
                        .build();

        log.info(
                "OUTGOING WITHDRAWAL DEBIT EVENT: eventId={}, account={}, reference={}",
                debitEvent.getEventId(),
                debitEvent.getAccountNumber(),
                debitEvent.getReference()
        );

        transactionEventPublisher.sendBalanceUpdate(
                debitEvent
        );

        return new ApiResponse<>(
                201,
                "Withdrawal Successful",
                modelMapper.map(
                        savedWithdrawalTransaction,
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


    @Override
    public void validateAccountAccess(
            String accountNumber
    ) {

        AccountDto account =
                fetchAndValidateAccount(accountNumber);

        validateAccountOwnership(account);
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

        /*
         * PERSONAL ACCOUNT
         */
        if ("PERSONAL".equalsIgnoreCase(account.getOwnershipType())) {

            if (account.getOwnerEmail() == null ||
                    !account.getOwnerEmail()
                            .equalsIgnoreCase(loggedInEmail)) {

                throw new AccessDeniedException(
                        "You are not authorized to access this account"
                );
            }

            return;
        }

        /*
         * BUSINESS ACCOUNT
         */
        if ("BUSINESS".equalsIgnoreCase(account.getOwnershipType())) {

            if (account.getBusinessId() == null) {
                throw new AccessDeniedException(
                        "Business account is missing business information"
                );
            }

            List<BusinessMembershipDto> memberships =
                    accountFeignClient.getBusinessMembers(
                            account.getBusinessId()
                    );

            boolean authorized = memberships.stream()
                    .anyMatch(membership ->
                            membership.isActive()
                                    && loggedInEmail.equalsIgnoreCase(
                                            membership.getUserEmail()
                                    )
                                    && (
                                        "OWNER".equalsIgnoreCase(
                                                membership.getRole()
                                        )
                                        || "ADMIN".equalsIgnoreCase(
                                                membership.getRole()
                                        )
                                        || "ACCOUNTANT".equalsIgnoreCase(
                                                membership.getRole()
                                        )
                                    )
                    );

            if (!authorized) {
                throw new AccessDeniedException(
                        "You are not authorized to access this business account"
                );
            }

            return;
        }

        throw new AccessDeniedException(
                "Unknown account ownership type"
        );
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
                                        transaction.getFromAccountNumber()
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
                                        transaction.getToAccountNumber()
                                )
                                .data();
            } catch (Exception ignored) {
            }
        }

        boolean ownsFromAccount = false;

        if (fromAccount != null) {
            try {
                validateAccountOwnership(fromAccount);
                ownsFromAccount = true;
            } catch (AccessDeniedException ignored) {
            }
        }

        boolean ownsToAccount = false;

        if (toAccount != null) {
            try {
                validateAccountOwnership(toAccount);
                ownsToAccount = true;
            } catch (AccessDeniedException ignored) {
            }
        }

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

    private String createTransferFingerprint(
            String email,
            TransactionRequest request
    ) {

        String raw =
                "TRANSFER|" +
                        email + "|" +
                        request.getFromAccountNumber() + "|" +
                        request.getToAccountNumber() + "|" +
                        request.getAmount().toPlainString() + "|" +
                        (
                                request.getDescription() == null
                                        ? ""
                                        : request.getDescription()
                        );

        return sha256(raw) ;
    }
    private String sha256(String value) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
    private String createWithdrawalFingerprint(
            String email,
            TransactionRequest request
    ) {

        String raw =
                "WITHDRAWAL|" +
                        email + "|" +
                        request.getFromAccountNumber() + "|" +
                        request.getAmount().toPlainString() + "|" +
                        (
                                request.getDescription() == null
                                        ? ""
                                        : request.getDescription()
                        );

        return sha256(raw);
    }

}
