package com.buuchezo.transactionservice.feign;

import com.buuchezo.transactionservice.dto.AccountDto;
import com.buuchezo.transactionservice.dto.ApiResponse;
import com.buuchezo.transactionservice.dto.BusinessMembershipDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "user-account-service")
public interface AccountFeignClient {

    @GetMapping("/api/accounts/{accountNumber}")
    ApiResponse<AccountDto> getAccountByNumber(
            @PathVariable("accountNumber") String accountNumber
    );

    @GetMapping("/api/businesses/{businessId}/members")
    List<BusinessMembershipDto> getBusinessMembers(
            @PathVariable("businessId") Long businessId
    );
}
