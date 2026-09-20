package com.buuchezo.transactionservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TanVerificationRequest {

    @NotBlank(message = "Challenge ID is required")
    private String challengeId;

    @NotBlank(message = "TAN is required")
    @Pattern(
            regexp = "\\d{6}",
            message = "TAN must contain exactly 6 digits"
    )
    private String tan;
}