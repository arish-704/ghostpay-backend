package com.ghostpay.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request payload for creating and injecting a payment packet into the mesh.
 */
public record SendPaymentRequest(
    @NotBlank(message = "senderVpa is required")
    String senderVpa,

    @NotBlank(message = "receiverVpa is required")
    String receiverVpa,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be at least ₹0.01")
    BigDecimal amount,

    @NotBlank(message = "pin is required")
    String pin
) {}
