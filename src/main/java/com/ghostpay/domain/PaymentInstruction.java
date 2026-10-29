package com.ghostpay.domain;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentInstruction(


    @NotBlank(message = "senderVpa is required")
    String senderVpa,


    @NotBlank(message = "receiverVpa is required")
    String receiverVpa,


    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    BigDecimal amount,

    // SHA-256 hash of sender's UPI PIN (authorized on sender's phone)
    @NotBlank(message = "pinHash is required")
    String pinHash,

    /**
     * Unique cryptographic Nonce (UUID).
     * Ensures that even if Alice sends ₹100 to Bob twice in a row,
     * the two packets produce completely different ciphertexts and hashes.
     */
    @NotBlank(message = "nonce is required")
    String nonce,

    // The backend checks whether the instruction was signed within the last 24 hours.
    @NotNull(message = "signedAt is required")
    Long signedAt // Stores the timestamp when the sender signed the instruction.
) {

}
