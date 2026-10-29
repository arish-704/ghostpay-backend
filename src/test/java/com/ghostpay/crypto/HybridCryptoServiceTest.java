package com.ghostpay.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostpay.domain.PaymentInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HybridCryptoServiceTest {

    private HybridCryptoService cryptoService;
    private ServerKeyHolder keyHolder;

    @BeforeEach
    void setUp() {
        keyHolder = new ServerKeyHolder();
        // Inject keySize = 2048 and trigger @PostConstruct initialization
        ReflectionTestUtils.setField(keyHolder, "keySize", 2048);
        keyHolder.init();

        cryptoService = new HybridCryptoService(new ObjectMapper());
    }

    @Test
    @DisplayName("Should encrypt and decrypt payment instruction matching original values")
    void shouldEncryptAndDecryptSuccessfully() {
        PaymentInstruction original = new PaymentInstruction(
                "alice@ghostpay",
                "bob@ghostpay",
                new BigDecimal("500.00"),
                "hash_of_secret_pin_1234",
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli()
        );

        // 1. Encrypt with server's public key
        String ciphertext = cryptoService.encrypt(original, keyHolder.getPublicKey());
        assertNotNull(ciphertext);
        assertFalse(ciphertext.isBlank());

        // 2. Decrypt with server's private key
        PaymentInstruction decrypted = cryptoService.decrypt(ciphertext, keyHolder.getPrivateKey());

        // 3. Verify all fields match
        assertEquals(original.senderVpa(), decrypted.senderVpa());
        assertEquals(original.receiverVpa(), decrypted.receiverVpa());
        assertEquals(original.amount(), decrypted.amount());
        assertEquals(original.pinHash(), decrypted.pinHash());
        assertEquals(original.nonce(), decrypted.nonce());
        assertEquals(original.signedAt(), decrypted.signedAt());
    }

    @Test
    @DisplayName("Tampered ciphertext must fail decryption due to AES-GCM tag mismatch")
    void shouldRejectTamperedCiphertext() {
        PaymentInstruction original = new PaymentInstruction(
                "alice@ghostpay",
                "bob@ghostpay",
                new BigDecimal("500.00"),
                "pin123",
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli()
        );

        String ciphertext = cryptoService.encrypt(original, keyHolder.getPublicKey());

        // Simulate a malicious intermediate flipping bits in transit
        byte[] rawBytes = Base64.getDecoder().decode(ciphertext);
        rawBytes[rawBytes.length - 1] ^= 0xFF; // Flip the very last bit (inside GCM auth tag)
        String tamperedCiphertext = Base64.getEncoder().encodeToString(rawBytes);

        // AES-GCM must detect the tampering and throw a SecurityException
        assertThrows(SecurityException.class, () -> 
                cryptoService.decrypt(tamperedCiphertext, keyHolder.getPrivateKey())
        );
    }

    @Test
    @DisplayName("Should produce valid 64-character lowercase SHA-256 hash")
    void shouldProduceValidSha256() {
        String sampleCiphertext = "dGVzdC1jaXBoZXJ0ZXh0LXZhbHVl";
        String hash = cryptoService.hashCiphertext(sampleCiphertext);

        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hash must be exactly 64 hex characters");
        assertTrue(hash.matches("^[a-f0-9]{64}$"), "Hash must be lowercase hex");
    }
}