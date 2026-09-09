package com.buuchezo.transactionservice.repository;

import com.buuchezo.transactionservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReference(String reference);

    @Query("SELECT t FROM Transaction t WHERE t.fromAccountNumber = :accountNumber OR t.toAccountNumber = :accountNumber ORDER BY t.createdAt DESC")
    List<Transaction> findallByAccountNumber(@Param("accountNumber") String accountNumber);

    @Query("SELECT t FROM Transaction t WHERE t.fromAccountNumber = :accountNumber OR t.toAccountNumber = :accountNumber AND t.createdAt BETWEEN :start AND :end ORDER BY t.createdAt DESC")
    List<Transaction> findallByAccountNumberAndDateRange(
            @Param("accountNumber") String accountNumber
            , @Param("start") LocalDateTime start
            , @Param("end") LocalDateTime end
    );

    List<Transaction> findallByFromAccountNumber(String fromAccountNumber);

    List<Transaction> findByToAccountNumber(String toAccountNumber);
}
