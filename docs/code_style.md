# Code Style & Engineering Guidelines — GhostPay

**Language Version**: Java 21 / 23 (LTS / Modern Feature Set)  
**Framework**: Spring Boot 3.2.x+  
**Architecture Paradigm**: Domain-Driven Design (DDD), Clean Architecture  

---

## 1. Core Engineering Philosophy

GhostPay is engineered to production-grade standards. We reject "vibe coding" (copy-pasting unverified scripts, ignoring compiler warnings, and piling untyped logic into giant classes). Every line of code must demonstrate:
* **Intentionality**: Clear architectural purpose.
* **Type Safety & Immutability**: Preventing runtime bugs at compile time.
* **Separation of Concerns**: Controllers handle HTTP, services orchestrate, entities protect business invariants, crypto services do math.

---

## 2. Modern Java Idioms (Java 21 / 23)

### 2.1 Use Java `record` for DTOs and Data Packets
* **Rule**: All request/response payloads, wire packets, and cleartext transfer objects must be Java `record`s.
* **Why**: Records provide compiler-enforced immutability, zero boilerplate, and native Jackson JSON mapping.
* **Example**:
  ```java
  // CORRECT: Modern record
  public record PaymentInstruction(
      @NotBlank String senderVpa,
      @NotBlank String receiverVpa,
      @NotNull @DecimalMin("0.01") BigDecimal amount,
      @NotBlank String pinHash,
      @NotBlank String nonce,
      @NotNull Long signedAt
  ) {}
  ```

### 2.2 Functional Copying in Records
* When updating state on an immutable record, provide descriptive factory methods that return a fresh instance:
  ```java
  public MeshPacket withDecrementedTtl() {
      return new MeshPacket(this.packetId, Math.max(0, this.ttl - 1), this.createdAt, this.ciphertext);
  }
  ```

### 2.3 Strict Floating-Point Prohibition
* **Rule**: Never use `double` or `float` for currencies. Use `java.math.BigDecimal`.
* Always use `compareTo()` for comparisons (`amount.compareTo(BigDecimal.ZERO) > 0`), never `.equals()` (which incorrectly checks scale: `0.0` vs `0.00`).

---

## 3. Lombok Conventions & Boundaries

Lombok reduces boilerplate, but misusing it causes subtle bugs in JPA and testing.

| Annotation | Usage Guideline |
|---|---|
| `@Getter` / `@Setter` | Apply at class level on JPA entities (`Account`, `Transaction`). |
| `@NoArgsConstructor` | Mandatory on `@Entity` for Hibernate reflection. |
| `@AllArgsConstructor` | Mandatory when using `@Builder` alongside `@NoArgsConstructor`. |
| `@Builder` | Highly encouraged on entities and complex service return models. |
| `@RequiredArgsConstructor` | Avoid combining with `@NoArgsConstructor` on entities. Use for constructor injection on services. |
| `@Data` | **STRICTLY PROHIBITED on JPA Entities**. Generates `.equals()` and `.hashCode()` that break Hibernate lazy-loading and set-based entity tracking. |

---

## 4. Spring Boot & Architecture Best Practices

### 4.1 Constructor Injection (No `@Autowired` on Fields)
* **Rule**: Never use field injection (`@Autowired private AccountRepository repo;`).
* **Standard**: Use explicit constructor injection or `@RequiredArgsConstructor`:
  ```java
  @Service
  @RequiredArgsConstructor
  public class SettlementService {
      private final AccountRepository accountRepository;
      private final TransactionRepository transactionRepository;
  }
  ```
  *Why*: Enables pure unit testing without starting the Spring context.

### 4.2 Explicit Transaction Boundaries (`@Transactional`)
* Annotate write operations with `@Transactional`.
* Keep `@Transactional` methods focused and fast: **perform slow hashing and RSA decryption outside the transaction**, entering `@Transactional` only for the atomic database write.

### 4.3 Input Validation (`jakarta.validation`)
* All controller endpoints accepting DTOs must use `@Valid`:
  ```java
  @PostMapping("/ingest")
  public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody MeshPacket packet) { ... }
  ```

---

## 5. Naming Conventions

| Component | Convention | Example |
|---|---|---|
| **Classes / Records** | PascalCase | `HybridCryptoService`, `MeshPacket` |
| **Methods / Variables** | camelCase | `hashCiphertext()`, `bridgeNodeId` |
| **Constants** | UPPER_SNAKE_CASE | `AES_KEY_SIZE_BITS`, `RSA_TRANSFORMATION` |
| **Database Tables** | lowercase_snake_case plural | `accounts`, `transactions` |
| **Database Columns** | lowercase_snake_case | `packet_hash`, `hop_count`, `settled_at` |
| **REST API Paths** | lowercase kebab-case | `/api/bridge/ingest`, `/api/mesh/state` |

---

## 6. Git & Commit Guidelines

GhostPay follows the **Conventional Commits** specification:

```
<type>(<scope>): <short description in present tense>
```

### Approved Types:
* `feat`: A new user-facing feature or architectural capability.
  * *Example*: `feat(crypto): implement RSA-OAEP + AES-GCM hybrid encryption`
* `fix`: A bug fix or correction.
  * *Example*: `fix(domain): correct JPA imports to jakarta.persistence`
* `test`: Adding or updating unit/integration tests.
  * *Example*: `test(crypto): add tamper rejection and roundtrip tests`
* `docs`: Documentation updates.
  * *Example*: `docs(arch): add 5-stage ingestion pipeline sequence diagram`
* `refactor`: Code changes that neither fix a bug nor add a feature.
  * *Example*: `refactor(domain): convert DTOs to Java records`
