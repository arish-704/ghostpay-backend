package com.ghostpay.domain;

/**
 * Status lifecycle of a mesh payment transaction.
 */
public enum TransactionStatus {
    SETTLED,            // Successfully debited sender and credited receiver
    DUPLICATE_DROPPED,  // Dropped because the ciphertext SHA-256 hash was already claimed
    INVALID_SIGNATURE,  // Cryptographic decryption or AES-GCM AEAD authentication tag failed
    EXPIRED,            // Packet signedAt timestamp exceeds 24-hour freshness window
    INSUFFICIENT_FUNDS, // Sender does not have enough balance
    INVALID_PIN,        // Sender UPI PIN hash mismatch
    ACCOUNT_NOT_FOUND   // Sender or receiver VPA does not exist
}
