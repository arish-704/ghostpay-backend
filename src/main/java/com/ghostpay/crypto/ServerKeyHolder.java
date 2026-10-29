package com.ghostpay.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Getter;

@Component
@Getter
public class ServerKeyHolder {

    @Value("${ghostpay.crypto.key-size:2048}")
    private int keySize;

    private PublicKey publicKey;
    private PrivateKey privateKey;

    /**
     * @PostConstruct runs automatically right after Spring creates this bean.
     * Generates a fresh RSA-2048 keypair for the server.
     */
    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(keySize);
            KeyPair keyPair = keyGen.generateKeyPair();
            this.publicKey = keyPair.getPublic();
            this.privateKey = keyPair.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize RSA keypair", e);
        }
    }

    /**
     * Returns the Base64-encoded X.509 representation of the public key.
     * This is what mobile phones fetch so they can encrypt payment packets.
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
    }

    public PublicKey getPublicKey() {
        return this.publicKey;
    }

    public PrivateKey getPrivateKey() {
        return this.privateKey;
    }

    public int getKeySize() {
        return this.keySize;
    }
}
