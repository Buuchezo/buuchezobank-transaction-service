package com.buuchezo.transactionservice.service;

import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.TransactionDto;
import com.buuchezo.transactionservice.dto.TransactionRequest;
import com.buuchezo.transactionservice.enums.transaction.TransactionDirection;

import java.time.LocalDateTime;
import java.util.List;

public interface TransactionService {
    ApiResponse<TransactionDto> deposit(TransactionRequest request);

    ApiResponse<TransactionDto> transfer(TransactionRequest request);

    ApiResponse<TransactionDto> withdraw(TransactionRequest request);

    ApiResponse<TransactionDto> getTransactionByReference(String reference);

    ApiResponse<List<TransactionDto>> getAllTransactionHistoryOfAnAccountNumber(String accountNumber);

    ApiResponse<List<TransactionDto>> getTransactionHistory(String accountNumber, LocalDateTime start, LocalDateTime end);

    ApiResponse<List<TransactionDto>> getMyTransactionHistoryByDirection(String accountNumber, TransactionDirection direction);

    void validateAccountAccess(String accountNumber);
}
