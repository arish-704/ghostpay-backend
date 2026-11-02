# Security Specification & Cryptographic Threat Model — GhostPay

**Specification Level**: Financial Cryptography & Zero-Trust Mesh Protocol  
**Document Version**: 1.0.0  
**Security Standard**: FIPS / NIST Recommended Cryptographic Patterns  

---

## 1. Threat Modeling in an Offline Mesh

In a conventional payment system (e.g. standard UPI), the user’s device communicates directly with bank servers over TLS-encrypted cellular channels. 

In GhostPay, **the transmission medium is an ad-hoc mesh of untrusted strangers’ smartphones**. The system operates under a **Zero-Trust Network Model**: every intermediate node must be assumed to be potentially malicious, compromised, or running packet sniffers.

### The 4 Primary Attack Vectors

| Attack Vector | Threat Description | Architectural Mitigation |
|---|---|---|
| **Eavesdropping (Confidentiality Breach)** | An intermediate phone inspects the packet to read the sender, receiver, balance, or PIN. | **Hybrid Encryption (RSA-OAEP + AES-256-GCM)**: Intermediates see only opaque binary ciphertext. |
| **Tampering (Integrity Violation)** | A malicious node alters Bob's VPA to their own VPA or edits ₹500 to ₹50,000. | **AES-GCM AEAD Tag**: 128-bit authentication tag verification fails immediately upon any single-bit alteration. |
| **Duplicate Storm (Double-Spend / Multi-Debit)** | Multiple bridge nodes capture the same packet and upload it simultaneously. | **Atomic Ciphertext Hash Claiming**: `ConcurrentHashMap.putIfAbsent(SHA-256(ciphertext))` ensures exactly-once execution. |
| **Replay Attack (Delayed Re-Debit)** | An adversary saves a captured ciphertext and re-uploads it 2 weeks later. | **Temporal Freshness Window + Nonce**: Rejects packets older than 24 hours; unique UUID nonces prevent collisions. |

---

## 2. Cryptographic Scheme Specification

GhostPay implements **Hybrid Encryption**, combining asymmetric public-key cryptography (for secure key exchange) with symmetric authenticated encryption (for high-speed payload protection).

### 2.1 Asymmetric Cipher: RSA-2048 with OAEP
* **Algorithm**: `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`
* **Key Length**: 2048 bits (FIPS / NIST compliant)
* **Digest Algorithm**: SHA-256 for both OAEP hash and MGF1 mask generation.
* **Role**: Encrypts the ephemeral 32-byte AES key.
* **Security Rationale**: Standard `PKCS1Padding` is historically vulnerable to Bleichenbacher padding oracle attacks. OAEP (Optimal Asymmetric Encryption Padding) with SHA-256 guarantees provable plaintext-awareness and IND-CCA2 security.

### 2.2 Symmetric Cipher: AES-256 in GCM Mode
* **Algorithm**: `AES/GCM/NoPadding`
* **Key Length**: 256 bits (Single-use, freshly generated per payment instruction via `SecureRandom`).
* **Initialization Vector (IV)**: 12 bytes (96 bits) randomly generated per packet.
* **Authentication Tag**: 128 bits (16 bytes).
* **Role**: Encrypts and cryptographically authenticates the cleartext JSON `PaymentInstruction`.
* **Security Rationale**: GCM is an AEAD (Authenticated Encryption with Associated Data) mode. Decryption and authentication occur simultaneously in hardware-accelerated CPU instructions (AES-NI).

---

## 3. The Binary Wire Format Layout

The `MeshPacket.ciphertext` field is a Base64 string encoding a strict, concatenated binary structure:

```
0                   256                 268                                      N bytes
┌────────────────────┬───────────────────┬───────────────────────────────────────────┐
│ RSA-Encrypted Key  │ Initialization    │ AES-256-GCM Ciphertext                    │
│ (256 bytes)        │ Vector (IV)       │ + 128-bit Authentication Tag              │
│                    │ (12 bytes)        │ (Variable length JSON payload + 16 bytes) │
└────────────────────┴───────────────────┴───────────────────────────────────────────┘
```

### Validation Bounds:
Any incoming ciphertext shorter than `256 + 12 + 16 = 284 bytes` is discarded immediately at wire parsing before invoking cryptographic hardware.

---

## 4. Duplicate Storm & Atomic Hash Idempotency

### Why Hash the Ciphertext?
1. **Never Hash `packetId`**: `packetId` is in the unencrypted outer envelope. A malicious relay could alter `packetId` while forwarding the same ciphertext, causing naive deduplication to fail.
2. **Never Decrypt Before Deduplicating**: RSA decryption is CPU-intensive (~1–2 ms per operation). If 1,000 bridge nodes deliver the same packet, decrypting all 1,000 would cause a Denial of Service (DoS).
3. **Ciphertext is Immutable and Deterministic**: AES-GCM with identical key, IV, and plaintext produces identical ciphertext bytes. Two deliveries of the same payment are byte-identical.

```
Incoming Request ──▶ Base64 Decode ──▶ SHA-256 Fingerprint (64 hex characters)
                                               │
                                               ▼
                              Atomic CAS: putIfAbsent(hash, timestamp)
                                     /                   \
                           null (First claimer)       non-null (Already seen)
                                   │                              │
                                   ▼                              ▼
                             Proceed to Decrypt          Drop: DUPLICATE_DROPPED
```

---

## 5. Replay Attack & Freshness Protection

Every `PaymentInstruction` contains two defense mechanisms:
1. **Cryptographic Nonce (`UUID`)**:
   * Ensures that even if Alice pays Bob ₹100 twice within 30 seconds with identical PINs, both packets generate completely different AES keys, nonces, and ciphertexts.
2. **Freshness Timestamp (`signedAt`)**:
   * Backend check:
     $$\Delta t = t_{\text{server}} - t_{\text{signed}}$$
   * Rule: $0 \le \Delta t \le 24 \text{ hours}$. If $\Delta t > 24 \text{ hours}$ or $\Delta t < -5 \text{ minutes}$ (clock drift allowance), reject with `EXPIRED`.
   * Because `signedAt` is inside the AES-GCM authenticated payload, an attacker cannot modify the timestamp without invalidating the authentication tag.

---

## 6. PIN Authorization Architecture

* In GhostPay, the user’s 4-digit or 6-digit UPI PIN is never sent in cleartext.
* The sender’s mobile application calculates:
  $$\text{pinHash} = \text{SHA-256}(\text{rawPIN} \parallel \text{salt})$$
* This hash is embedded inside the encrypted `PaymentInstruction`.
* Intermediaries cannot read it. When decrypted on the backend, the server compares the hash against the bank's stored credential before executing the debit.
