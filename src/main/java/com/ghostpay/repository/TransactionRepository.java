package com.ghostpay.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ghostpay.domain.Transaction;

@Repository 
public interface TransactionRepository extends JpaRepository<Transaction,Long>{

    /**
     * Checks if a transaction with this exact ciphertext SHA-256 hash was ever settled.
     * Hits the unique B-Tree index (idx_tx_packet_hash) in sub-milliseconds.
     */
    boolean existsByPacketHash(String packetHash);


    Optional<Transaction> findByPacketHash(String packetHash);

    
    /**
     * Fetches the latest 20 settled or failed transactions ordered by settlement time.
     * Used for the real-time live dashboard and audit feed.
     * Translates to: SELECT * FROM transactions ORDER BY settled_at DESC LIMIT 20
     */
    List<Transaction> findTop20ByOrderBySettledAtDesc();

}
