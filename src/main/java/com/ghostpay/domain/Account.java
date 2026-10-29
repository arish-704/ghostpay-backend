package com.ghostpay.domain;

import java.math.BigDecimal;

import jakarta.persistence.Id;
import jakarta.persistence.Version;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Table(name = "account")
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
    //the Account entity itself enforces the rule that balances can never be negative. In Domain-Driven Design (DDD), entities should not be "dumb data bags" — they should guard their own business invariants.
    // Business logic is written in the entity to keep data
    // and its related rules together, instead of putting
    // all the logic in the service layer.


    public boolean hasSufficientFunds(BigDecimal amount) {
        return this.balance != null && this.balance.compareTo(amount) >= 0;
    }
    public void debit(BigDecimal amount) {
        if (!hasSufficientFunds(amount)) {
            throw new IllegalStateException("Insufficient balance for VPA: " + this.vpa);
        }
        this.balance = this.balance.subtract(amount); // using .subtract .add as we cannot use + , - directly with big decimal
    }
    public void credit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        this.balance = this.balance.add(amount);
    }

}
