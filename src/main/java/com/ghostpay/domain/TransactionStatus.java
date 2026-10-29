package com.ghostpay.domain;

public enum TransactionStatus {
    SETTLED,
    DUPLICATE_DROPPED, // Dropped because the cipher hash was already claimed
    INVALID_SIGNATURE, // Verification failed
    EXPIRED,
    INSUFFICIENT_FUNDS,
    INVALID_PIN
}
