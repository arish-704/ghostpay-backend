package com.ghostpay.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Account entity representing a user's UPI wallet in GhostPay.
 * Uses JPA @Version for optimistic locking during concurrent settlements.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // VPA is the unique handle, e.g., "alice@ghostpay"
    @Column(nullable = false, unique = true, length = 100)
    private String vpa;

    @Column(nullable = false, length = 100)
    private String ownerName;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    // SHA-256 hash of user's UPI PIN (optional, for PIN validation)
    @Column(length = 64)
    private String pinHash;

    /**
     * Optimistic Locking:
     * When two bridge nodes upload packets at the exact same millisecond,
     * Hibernate checks if the version matches before committing.
     * If a collision occurs, Hibernate safely rejects the conflicting update
     * instead of corrupting the ledger.
     */
    @Version
    private Long version;

    // --- Domain Business Logic (Encapsulation) ---

    public boolean hasSufficientFunds(BigDecimal amount) {
        return this.balance != null && amount != null && this.balance.compareTo(amount) >= 0;
    }

    public void debit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be strictly greater than zero");
        }
        if (!hasSufficientFunds(amount)) {
            throw new IllegalStateException("Insufficient balance for VPA: " + this.vpa);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly greater than zero");
        }
        this.balance = this.balance.add(amount);
    }

    // --- Explicit Getters and Setters for IDE / JDT Compatibility ---

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVpa() {
        return this.vpa;
    }

    public void setVpa(String vpa) {
        this.vpa = vpa;
    }

    public String getOwnerName() {
        return this.ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public BigDecimal getBalance() {
        return this.balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getPinHash() {
        return this.pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public Long getVersion() {
        return this.version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
