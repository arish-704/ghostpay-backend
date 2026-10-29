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


    private Integer hopCount; // An Integer in Java has a fixed, standard size in SQL that is why sql does not need any specific instructions for thats why we omitted the column annotation


    @Column(nullable = false, updatable = false)
    private Instant settledAt; // An Instant represents an instantaneous, unambiguous point on the universal timeline measured in UTC (Greenwich Mean Time).


    @PrePersist
    protected void onCreate() {
        if (this.settledAt == null) {
            this.settledAt = Instant.now();
        }
    }
}
