package com.buuchezo.transactionservice.controller;

import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TransactionDto;
import com.buuchezo.transactionservice.dto.TransactionRequest;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;
import com.buuchezo.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<TransactionDto>> transfer(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(transactionService.transfer(request));
    }


    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<TransactionDto>> withdraw(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(transactionService.withdraw(request));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<TransactionDto>>> getHistory(
            @RequestParam String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        //convert the srat date to LocalDateTime time if it is provided else assign January 2026 as the start day time
        LocalDateTime startDate = (start != null)
                ? start.atStartOfDay()
                : LocalDateTime.of(2026, 1, 1, 0, 0);


        LocalDateTime endDate = (end != null)
                ? end.atTime(LocalTime.MAX)
                : LocalDateTime.now();

        return ResponseEntity.ok(transactionService.getTransactionHistory(accountNumber, startDate, endDate));

    }

    @GetMapping("/history/direction")
    public ResponseEntity<ApiResponse<List<TransactionDto>>> getHistoryByDirection(
            @RequestParam String accountNumber,
            @RequestParam TransactionDirection direction
    ) {

        return ResponseEntity.ok(transactionService.getMyTransactionHistoryByDirection(accountNumber, direction));

    }


    @GetMapping("/reference/{reference}")
    public ResponseEntity<ApiResponse<TransactionDto>> getTransactionByReference(
            @PathVariable String reference
    ) {

        return ResponseEntity.ok(transactionService.getTransactionByReference(reference));

    }

}