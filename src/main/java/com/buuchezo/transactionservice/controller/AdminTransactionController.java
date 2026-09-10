package com.buuchezo.transactionservice.controller;

import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TransactionDto;
import com.buuchezo.transactionservice.dto.TransactionRequest;
import com.buuchezo.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/transactions/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTransactionController {

    private final TransactionService transactionService;

    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<TransactionDto>> deposit(
            @Valid
            @RequestBody
            TransactionRequest request) {
        return ResponseEntity.ok(transactionService.deposit(request));
    }

    @GetMapping("/history/{accountNumber}")
    public ResponseEntity<ApiResponse<List<TransactionDto>>> getTransactionByReference(
            @PathVariable String accountNumber
    ) {
        return ResponseEntity.ok(transactionService.getAllTransactionHistoryOfAnAccountNumber(accountNumber));

    }
}
