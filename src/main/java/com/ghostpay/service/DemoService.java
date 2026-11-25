package com.ghostpay.service;

import com.ghostpay.crypto.HybridCryptoService;
import com.ghostpay.crypto.ServerKeyHolder;
import com.ghostpay.domain.Account;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.domain.PaymentInstruction;
import com.ghostpay.repository.AccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Helper service for the simulation and interactive demo:
 * 1. Seeds default demo accounts on startup.
 * 2. Simulates an offline sender phone creating and hybrid-encrypting payment packets.
 */
@Service
@RequiredArgsConstructor
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final AccountRepository accountRepository;
    private final HybridCryptoService cryptoService;
    private final ServerKeyHolder serverKeyHolder;

    /**
     * Seeds initial demo accounts on startup if the database is empty.
     */
    @PostConstruct
    public void seedInitialAccounts() {
        if (accountRepository.count() == 0) {
            log.info("Seeding initial demo accounts: Alice (₹1000), Bob (₹500), Charlie (₹250)...");

            String defaultPinHash = hashPin("1234");

            Account alice = Account.builder()
                    .vpa("alice@ghostpay")
                    .ownerName("Alice Sharma")
                    .balance(new BigDecimal("1000.00"))
                    .pinHash(defaultPinHash)
                    .build();

            Account bob = Account.builder()
                    .vpa("bob@ghostpay")
                    .ownerName("Bob Verma")
                    .balance(new BigDecimal("500.00"))
                    .pinHash(defaultPinHash)
                    .build();

            Account charlie = Account.builder()
                    .vpa("charlie@ghostpay")
                    .ownerName("Charlie Patel")
                    .balance(new BigDecimal("250.00"))
                    .pinHash(defaultPinHash)
                    .build();

            accountRepository.saveAll(List.of(alice, bob, charlie));
            log.info("Demo accounts seeded successfully.");
        }
    }

    /**
     * Simulates a sender's phone creating and encrypting an offline UPI payment.
     *
     * @param senderVpa   UPI handle of sender (e.g. alice@ghostpay)
     * @param receiverVpa UPI handle of recipient (e.g. bob@ghostpay)
     * @param amount      Amount in INR
     * @param rawPin      User's 4-digit PIN (e.g. "1234")
     * @param initialTtl  Initial hop limit (e.g. 5)
     * @return Fully encrypted wire-ready MeshPacket
     */
    public MeshPacket createPaymentPacket(String senderVpa, String receiverVpa,
                                         BigDecimal amount, String rawPin, int initialTtl) {
        String pinHash = hashPin(rawPin);
        String nonce = UUID.randomUUID().toString();
        long nowMillis = Instant.now().toEpochMilli();

        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                pinHash,
                nonce,
                nowMillis
        );

        // Encrypt with server's RSA public key (Hybrid AES-256-GCM + RSA-OAEP)
        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());

        return new MeshPacket(
                UUID.randomUUID().toString(),
                initialTtl,
                nowMillis,
                ciphertext
        );
    }

    /**
     * Resets demo account balances back to their initial state.
     */
    @Transactional
    public void resetBalances() {
        accountRepository.findByVpa("alice@ghostpay").ifPresent(a -> {
            a.setBalance(new BigDecimal("1000.00"));
            accountRepository.save(a);
        });
        accountRepository.findByVpa("bob@ghostpay").ifPresent(b -> {
            b.setBalance(new BigDecimal("500.00"));
            accountRepository.save(b);
        });
        accountRepository.findByVpa("charlie@ghostpay").ifPresent(c -> {
            c.setBalance(new BigDecimal("250.00"));
            accountRepository.save(c);
        });
    }

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    public static String hashPin(String rawPin) {
        if (rawPin == null) rawPin = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawPin.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
