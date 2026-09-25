package com.buuchezo.transactionservice.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BusinessMembershipDto {

    private Long businessId;

    private String userEmail;

    private String role;

    private boolean active;
}
