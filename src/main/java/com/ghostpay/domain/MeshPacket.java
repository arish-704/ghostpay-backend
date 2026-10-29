package com.ghostpay.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The public wire envelope carried across the offline mesh.
 */
public record MeshPacket(


    @NotBlank(message = "packetId is required")
    String packetId,

    /**
     * Time-To-Live (Hop count limit).
     * Starts at e.g. 5 or 10. Decrements by 1 every time a phone forwards it.
     * When it hits 0, phones drop it to prevent endless mesh looping.
     */
    @Min(value = 0, message = "TTL cannot be negative")
    int ttl,


    @NotNull(message = "createdAt is required")
    Long createdAt,

    /**
     * The encrypted payload (Base64 string).
     * Strangers and bridge nodes carry this, but cannot read or alter it.
     * Format: [256 bytes RSA key] + [12 bytes IV] + [AES-GCM ciphertext + 16 bytes tag]
     */
    @NotBlank(message = "ciphertext is required")
    String ciphertext
) {

    // These are helper methods ...  In records, since state is immutable, decrementing TTL returns a fresh record instance.
    public MeshPacket withDecrementedTtl() {
        return new MeshPacket(this.packetId, Math.max(0, this.ttl - 1), this.createdAt, this.ciphertext);
    }
    public boolean isExpired() {
        return this.ttl <= 0;
    }

}
