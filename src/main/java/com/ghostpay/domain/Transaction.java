package com.ghostpay.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable transaction ledger entry.
 * Enforces database-level idempotency via a unique index on packetHash.
 */
@Entity 
@Table(
    name = "transactions",
    indexes = {
        @Index(name = "idx_tx_packet_hash", columnList = "packetHash", unique = true)
    }
) 
@Getter 
@Setter 
@AllArgsConstructor 
@NoArgsConstructor 
@Builder 
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SHA-256 of the raw ciphertext.
     * UNIQUE constraint = The secondary defense-in-depth shield!
     * Even if memory caches fail, the database physically rejects duplicate records.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String packetHash;

    @Column(nullable = false, length = 100)
    private String senderVpa;

    @Column(nullable = false, length = 100)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionStatus status;

    @Column(length = 255)
    private String failureReason;

    // Metadata recorded from the bridge ingestion headers
    @Column(length = 100)
    private String bridgeNodeId;

    private Integer hopCount;

    @Column(nullable = false, updatable = false)
    private Instant settledAt;

    @PrePersist
    protected void onCreate() {
        if (this.settledAt == null) {
            this.settledAt = Instant.now();
        }
    }

    // --- Explicit Getters and Setters for IDE / JDT Compatibility ---

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPacketHash() {
        return this.packetHash;
    }

    public void setPacketHash(String packetHash) {
        this.packetHash = packetHash;
    }

    public String getSenderVpa() {
        return this.senderVpa;
    }

    public void setSenderVpa(String senderVpa) {
        this.senderVpa = senderVpa;
    }

    public String getReceiverVpa() {
        return this.receiverVpa;
    }

    public void setReceiverVpa(String receiverVpa) {
        this.receiverVpa = receiverVpa;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public TransactionStatus getStatus() {
        return this.status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return this.failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getBridgeNodeId() {
        return this.bridgeNodeId;
    }

    public void setBridgeNodeId(String bridgeNodeId) {
        this.bridgeNodeId = bridgeNodeId;
    }

    public Integer getHopCount() {
        return this.hopCount;
    }

    public void setHopCount(Integer hopCount) {
        this.hopCount = hopCount;
    }

    public Instant getSettledAt() {
        return this.settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
