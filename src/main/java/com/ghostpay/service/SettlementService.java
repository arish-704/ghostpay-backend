package com.ghostpay.service;

import com.ghostpay.domain.Account;
import com.ghostpay.domain.PaymentInstruction;
import com.ghostpay.domain.Transaction;
import com.ghostpay.domain.TransactionStatus;
import com.ghostpay.repository.AccountRepository;
import com.ghostpay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Executes atomic financial settlements.
 * Runs inside an isolated database transaction to debit sender and credit receiver.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Executes atomic debit, credit, and ledger recording.
     *
     * @param instruction Decrypted and freshness-verified payment instruction.
     * @param packetHash  SHA-256 fingerprint of the ciphertext (unique index).
     * @param bridgeNodeId Identifier of the bridge device that uploaded this packet.
     * @param hopCount    Number of mesh hops the packet traversed.
     * @return Saved Transaction ledger entry reflecting settlement outcome.
     */
    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash, String bridgeNodeId, Integer hopCount) {
        log.info("Settling transaction: sender={}, receiver={}, amount=₹{}, hash={}",
                instruction.senderVpa(), instruction.receiverVpa(), instruction.amount(), packetHash);

        // 1. Prevent Self-Transfer Loophole
        if (instruction.senderVpa().equalsIgnoreCase(instruction.receiverVpa())) {
            log.warn("Self-transfer rejected: sender and receiver are identical ({})", instruction.senderVpa());
            return recordFailure(instruction, packetHash, bridgeNodeId, hopCount,
                    TransactionStatus.INSUFFICIENT_FUNDS, "Self-transfer not permitted: sender and receiver cannot be identical");
        }

        // 2. Validate Amount
        BigDecimal amount = instruction.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid payment amount: {}", amount);
            return recordFailure(instruction, packetHash, bridgeNodeId, hopCount,
                    TransactionStatus.INSUFFICIENT_FUNDS, "Transaction amount must be strictly greater than zero");
        }
        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);

        // 3. Locate Sender Account
        Optional<Account> senderOpt = accountRepository.findByVpa(instruction.senderVpa());
        if (senderOpt.isEmpty()) {
            return recordFailure(instruction, packetHash, bridgeNodeId, hopCount,
                    TransactionStatus.ACCOUNT_NOT_FOUND, "Sender account does not exist: " + instruction.senderVpa());
        }
        Account sender = senderOpt.get();

        // 4. Locate Receiver Account
        Optional<Account> receiverOpt = accountRepository.findByVpa(instruction.receiverVpa());
        if (receiverOpt.isEmpty()) {
            return recordFailure(instruction, packetHash, bridgeNodeId, hopCount,
                    TransactionStatus.ACCOUNT_NOT_FOUND, "Receiver account does not exist: " + instruction.receiverVpa());
        }
        Account receiver = receiverOpt.get();

        // 5. Verify PIN Hash (if account has a configured PIN)
        if (sender.getPinHash() != null && !sender.getPinHash().isBlank()) {
            if (!sender.getPinHash().equalsIgnoreCase(instruction.pinHash())) {
                log.warn("PIN verification failed for VPA: {}", sender.getVpa());
                return recordFailure(instruction, packetHash, bridgeNodeId, hopCount,
                        TransactionStatus.INVALID_PIN, "UPI PIN verification failed");
            }
        }

        // 6. Verify Funds
        if (!sender.hasSufficientFunds(normalizedAmount)) {
            log.warn("Insufficient funds for VPA: {}. Required: {}, Available: {}",
                    sender.getVpa(), normalizedAmount, sender.getBalance());
            return recordFailure(instruction, packetHash, bridgeNodeId, hopCount,
                    TransactionStatus.INSUFFICIENT_FUNDS, "Insufficient balance in sender account");
        }

        // 7. Atomic Debit & Credit
        sender.debit(normalizedAmount);
        receiver.credit(normalizedAmount);

        accountRepository.save(sender);
        accountRepository.save(receiver);

        // 8. Record Settled Transaction Ledger
        Transaction transaction = Transaction.builder()
                .packetHash(packetHash)
                .senderVpa(instruction.senderVpa())
                .receiverVpa(instruction.receiverVpa())
                .amount(normalizedAmount)
                .status(TransactionStatus.SETTLED)
                .bridgeNodeId(bridgeNodeId)
                .hopCount(hopCount)
                .settledAt(Instant.now())
                .build();

        Transaction saved = transactionRepository.save(transaction);
        log.info("Transaction settled successfully. Transaction ID: {}", saved.getId());
        return saved;
    }

    private Transaction recordFailure(PaymentInstruction instruction, String packetHash,
                                      String bridgeNodeId, Integer hopCount,
                                      TransactionStatus status, String reason) {
        Transaction failedTx = Transaction.builder()
                .packetHash(packetHash)
                .senderVpa(instruction.senderVpa())
                .receiverVpa(instruction.receiverVpa())
                .amount(instruction.amount() != null ? instruction.amount().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .status(status)
                .failureReason(reason)
                .bridgeNodeId(bridgeNodeId)
                .hopCount(hopCount)
                .settledAt(Instant.now())
                .build();

        return transactionRepository.save(failedTx);
    }
}
