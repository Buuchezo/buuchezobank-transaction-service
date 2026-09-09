package com.buuchezo.transactionservice.feign;

import com.buuchezo.transactionservice.dto.AccountDto;
import com.buuchezo.transactionservice.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-account-service")
public interface AccountFeignClient {

    @GetMapping("/api/accounts/{accountNumber}")
    ApiResponse<AccountDto> getAccountByNumber(@PathVariable("accountNumber") String accountNumber);
}
