package com.ghostpay.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostpay.domain.PaymentInstruction;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class HybridCryptoService {

    // Modern cryptographic standard constants
    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE_BITS = 256;
    private static final int GCM_IV_LENGTH_BYTES = 12; // 96-bit IV standard for GCM
    private static final int GCM_TAG_LENGTH_BITS = 128; // 128-bit authentication tag
    private static final int RSA_KEY_SIZE_BYTES = 256; // 2048-bit RSA produces a 256-byte encrypted key

    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;


    // A mapper that converts pojo to json and then generates random number for cryptographic use.
    public HybridCryptoService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.secureRandom = new SecureRandom();
    }

    /**
     * SIMULATES SENDER PHONE:
     * 1. Generates a fresh AES-256 key and 12-byte IV(Initialization Vector).
     * 2. Encrypts the JSON payment instruction with AES-256-GCM.
     * 3. Wraps (encrypts) the AES key with the server's RSA Public Key using OAEP.
     * 4. Packs [256-byte RSA key] + [12-byte IV] + [AES ciphertext + tag] into Base64.
     */
    public String encrypt(PaymentInstruction instruction, PublicKey serverPublicKey) {
        try {
            // Convert the payment instruction to JSON and store the value as bytes
            byte[] plaintextBytes = objectMapper.writeValueAsBytes(instruction);

            // 2. Generate a random AES-256 key
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE_BITS, secureRandom);
            SecretKey aesKey = keyGen.generateKey();

            // 3. Generate a random 12-byte IV for AES-GCM
            // The code creates a 12-byte array and fills it with random bytes --> This is called Initialization Vector
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            // 4. Encrypt plaintext with AES-256-GCM
            Cipher aesCipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
            byte[] aesCiphertextWithTag = aesCipher.doFinal(plaintextBytes);

            // 5. Wrap (encrypt) the AES key using the Server's RSA Public Key (OAEP padding)
            Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
            rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            // 6. Concatenate into standard binary wire format: [RSA key][IV][AES payload]
            // 	ByteBuffer Combines binary data and extracts it later.
            ByteBuffer buffer = ByteBuffer.allocate(encryptedAesKey.length + iv.length + aesCiphertextWithTag.length);
            buffer.put(encryptedAesKey);
            buffer.put(iv);
            buffer.put(aesCiphertextWithTag);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new SecurityException("Failed to encrypt payment instruction", e);
        }
    }

    /**
     * SERVER-SIDE DECRYPTION:
     * Unpacks the Base64 wire format, unwraps the AES key with RSA Private Key,
     * decrypts and cryptographically verifies the payload with AES-GCM.
     * 
     * NOTE: If even one single bit was tampered with by an intermediate node,
     * AES-GCM throws AEADBadTagException, preventing tampered data from being parsed!
     */
    public PaymentInstruction decrypt(String base64Ciphertext, PrivateKey serverPrivateKey) {
        try {
            byte[] wireBytes = Base64.getDecoder().decode(base64Ciphertext);

            // Sanity check: must contain at least [256-byte RSA key] + [12-byte IV] + [16-byte GCM tag]
            if (wireBytes.length < RSA_KEY_SIZE_BYTES + GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
                throw new IllegalArgumentException("Malformed ciphertext: payload is too short");
            }

            ByteBuffer buffer = ByteBuffer.wrap(wireBytes);

            // 1. Extract the 256-byte RSA-encrypted AES key
            byte[] encryptedAesKey = new byte[RSA_KEY_SIZE_BYTES];
            buffer.get(encryptedAesKey);

            // 2. Extract the 12-byte IV
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);

            // 3. Extract the remaining AES-GCM ciphertext + authentication tag
            byte[] aesCiphertextWithTag = new byte[buffer.remaining()];
            buffer.get(aesCiphertextWithTag);

            // 4. Unwrap the AES key using the server's RSA Private Key
            Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
            rsaCipher.init(Cipher.DECRYPT_MODE, serverPrivateKey);
            byte[] rawAesKey = rsaCipher.doFinal(encryptedAesKey);
            SecretKey aesKey = new SecretKeySpec(rawAesKey, "AES");

            // 5. Decrypt and verify payload using AES-GCM
            Cipher aesCipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
            byte[] cleartextBytes = aesCipher.doFinal(aesCiphertextWithTag);

            // 6. Deserialize JSON into our immutable PaymentInstruction record
            return objectMapper.readValue(cleartextBytes, PaymentInstruction.class);
        } catch (Exception e) {
            throw new SecurityException("Decryption or integrity verification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Computes the SHA-256 fingerprint of the raw Base64 ciphertext.
     * This 64-character hex string is used for atomic deduplication (Idempotency).
     */
    public String hashCiphertext(String base64Ciphertext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(base64Ciphertext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}