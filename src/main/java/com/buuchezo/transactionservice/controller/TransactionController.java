package com.buuchezo.transactionservice.controller;

import com.buuchezo.transactionservice.dto.AccountDto;
import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TanChallengeRequest;
import com.buuchezo.transactionservice.dto.TanChallengeResponse;
import com.buuchezo.transactionservice.dto.TransactionDto;
import com.buuchezo.transactionservice.dto.TransactionRequest;
import com.buuchezo.transactionservice.enums.AccountStatus;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;
import com.buuchezo.transactionservice.exceptions.BadRequestException;
import com.buuchezo.transactionservice.feign.AccountFeignClient;
import com.buuchezo.transactionservice.service.TanService;
import com.buuchezo.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    private final TanService tanService;

    private final AccountFeignClient accountFeignClient;

    // =========================================================
    // TRANSFER
    // =========================================================

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<TransactionDto>> transfer(
            @Valid @RequestBody TransactionRequest request
    ) {

        return ResponseEntity.ok(
                transactionService.transfer(request)
        );
    }

    // =========================================================
    // WITHDRAWAL
    // =========================================================

    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<TransactionDto>> withdraw(
            @Valid @RequestBody TransactionRequest request
    ) {

        return ResponseEntity.ok(
                transactionService.withdraw(request)
        );
    }

    // =========================================================
    // TRANSACTION HISTORY
    // =========================================================

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<TransactionDto>>> getHistory(
            @RequestParam String accountNumber,

            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE
            )
            LocalDate start,

            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE
            )
            LocalDate end
    ) {

        LocalDateTime startDate =
                start != null
                        ? start.atStartOfDay()
                        : LocalDateTime.of(
                        2026,
                        1,
                        1,
                        0,
                        0
                );

        LocalDateTime endDate =
                end != null
                        ? end.atTime(LocalTime.MAX)
                        : LocalDateTime.now();

        return ResponseEntity.ok(
                transactionService.getTransactionHistory(
                        accountNumber,
                        startDate,
                        endDate
                )
        );
    }

    // =========================================================
    // TRANSACTION HISTORY BY DIRECTION
    // =========================================================

    @GetMapping("/history/direction")
    public ResponseEntity<ApiResponse<List<TransactionDto>>>
    getHistoryByDirection(
            @RequestParam String accountNumber,

            @RequestParam TransactionDirection direction
    ) {

        return ResponseEntity.ok(
                transactionService
                        .getMyTransactionHistoryByDirection(
                                accountNumber,
                                direction
                        )
        );
    }

    // =========================================================
    // TRANSACTION BY REFERENCE
    // =========================================================

    @GetMapping("/reference/{reference}")
    public ResponseEntity<ApiResponse<TransactionDto>>
    getTransactionByReference(
            @PathVariable String reference
    ) {

        return ResponseEntity.ok(
                transactionService
                        .getTransactionByReference(reference)
        );
    }

    // =========================================================
    // CREATE TAN CHALLENGE
    // =========================================================

    @PostMapping("/tan/challenge")
    public ResponseEntity<ApiResponse<TanChallengeResponse>>
    createTanChallenge(
            @Valid @RequestBody TanChallengeRequest request
    ) {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated()) {

            throw new BadRequestException(
                    "Authenticated user is required"
            );
        }

        String email =
                authentication.getName();

        String operation =
                request.getOperation()
                        .trim()
                        .toUpperCase();

        /*
         * IMPORTANT:
         *
         * Validate the actual transaction BEFORE
         * generating the TAN.
         */
        validateTanTransaction(
                email,
                operation,
                request
        );

        String transactionFingerprint;

        switch (operation) {

            case "TRANSFER":

                transactionFingerprint =
                        createTransferChallengeFingerprint(
                                email,
                                request
                        );

                break;

            case "WITHDRAWAL":

                transactionFingerprint =
                        createWithdrawalChallengeFingerprint(
                                email,
                                request
                        );

                break;

            default:

                throw new BadRequestException(
                        "Unsupported TAN operation"
                );
        }

        return ResponseEntity.ok(
                tanService.createChallenge(
                        email,
                        operation,
                        transactionFingerprint
                )
        );
    }

    // =========================================================
    // VALIDATE TAN TRANSACTION
    // =========================================================

    private void validateTanTransaction(
            String email,
            String operation,
            TanChallengeRequest request
    ) {

        if (request.getFromAccountNumber() == null ||
                request.getFromAccountNumber().isBlank()) {

            throw new BadRequestException(
                    "Source account number is required"
            );
        }

        if (request.getAmount() == null ||
                request.getAmount().signum() <= 0) {

            throw new BadRequestException(
                    "Amount must be greater than zero"
            );
        }

        AccountDto sourceAccount =
                getAccount(
                        request.getFromAccountNumber()
                );

        /*
         * The authenticated user must have access
         * to the source account.
         *
         * This supports both PERSONAL and BUSINESS
         * account ownership.
         */
        transactionService.validateAccountAccess(
                request.getFromAccountNumber()
        );

        /*
         * Account must be active.
         */
        if (sourceAccount.getAccountStatus()
                != AccountStatus.ACTIVE) {

            throw new BadRequestException(
                    "Transaction failed: your account is inactive"
            );
        }

        /*
         * Balance must exist.
         */
        if (sourceAccount.getBalance() == null) {

            throw new BadRequestException(
                    "Account balance is unavailable"
            );
        }

        /*
         * Sufficient balance.
         */
        if (sourceAccount.getBalance()
                .compareTo(request.getAmount()) < 0) {

            throw new BadRequestException(
                    "Insufficient Account Balance"
            );
        }

        // -----------------------------------------------------
        // TRANSFER
        // -----------------------------------------------------

        if ("TRANSFER".equals(operation)) {

            if (request.getToAccountNumber() == null ||
                    request.getToAccountNumber().isBlank()) {

                throw new BadRequestException(
                        "Destination account is required for transfer"
                );
            }

            if (request.getFromAccountNumber()
                    .equals(
                            request.getToAccountNumber()
                    )) {

                throw new BadRequestException(
                        "You cannot transfer to the same account"
                );
            }

            /*
             * Destination must exist and must not be closed.
             */
            getAccount(
                    request.getToAccountNumber()
            );

            return;
        }

        // -----------------------------------------------------
        // WITHDRAWAL
        // -----------------------------------------------------

        if ("WITHDRAWAL".equals(operation)) {

            return;
        }

        throw new BadRequestException(
                "Unsupported TAN operation"
        );
    }

    // =========================================================
    // GET ACCOUNT
    // =========================================================

    private AccountDto getAccount(
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

            throw new BadRequestException(
                    "Account not found"
            );
        }

        AccountDto account =
                response.data();

        if (account.getAccountStatus()
                == AccountStatus.CLOSED) {

            throw new BadRequestException(
                    "Transaction denied: account is closed"
            );
        }

        return account;
    }

    // =========================================================
    // TRANSFER FINGERPRINT
    // =========================================================

    private String createTransferChallengeFingerprint(
            String email,
            TanChallengeRequest request
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

        return sha256(raw);
    }

    // =========================================================
    // WITHDRAWAL FINGERPRINT
    // =========================================================

    private String createWithdrawalChallengeFingerprint(
            String email,
            TanChallengeRequest request
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

    // =========================================================
    // SHA-256
    // =========================================================

    private String sha256(
            String value
    ) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

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
}