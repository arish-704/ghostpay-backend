# Database Schema & Persistence Architecture — GhostPay

**Module**: Persistence Layer (`com.ghostpay.domain`, `com.ghostpay.repository`)  
**ORM / Data Provider**: Spring Data JPA / Hibernate 6  
**Engines Supported**: H2 Database (Embedded Local), PostgreSQL 15+ (Production Cloud)  

---

## 1. Entity-Relationship (ER) Overview

The GhostPay data model is designed for strict financial accounting: **mutable accounts** guarded by optimistic locking, paired with an **append-only, immutable transaction ledger**.

```mermaid
erDiagram
    ACCOUNTS {
        bigint id PK "Auto Increment IDENTITY"
        varchar(100) vpa UK "Unique UPI Handle e.g. alice@ghostpay"
        varchar(100) owner_name "User Full Name"
        numeric(19,4) balance "Current Settled Balance"
        bigint version "Optimistic Locking Counter"
    }

    TRANSACTIONS {
        bigint id PK "Auto Increment IDENTITY"
        varchar(64) packet_hash UK "SHA-256 Ciphertext Fingerprint"
        varchar(100) sender_vpa "Sender UPI Handle"
        varchar(100) receiver_vpa "Receiver UPI Handle"
        numeric(19,4) amount "Transaction Amount"
        varchar(30) status "SETTLED, DUPLICATE_DROPPED, etc."
        varchar(255) failure_reason "Optional Diagnostic String"
        varchar(100) bridge_node_id "ID of uploading bridge phone"
        integer hop_count "Relay hops traversed"
        timestamp settled_at "UTC Timestamp (@PrePersist)"
    }

    ACCOUNTS ||--o{ TRANSACTIONS : "participates in"
```

---

## 2. Table Specifications

### 2.1 Table: `accounts`
Stores user balances and handles optimistic version tracking.

| Column | SQL Type | Nullable | Unique | Description |
|---|---|---|---|---|
| `id` | `BIGINT` | No | Yes (PK) | Surrogate primary key (auto-increment). |
| `vpa` | `VARCHAR(100)` | No | Yes | Virtual Payment Address (e.g., `alice@ghostpay`). |
| `owner_name` | `VARCHAR(100)` | No | No | Legal name of account holder. |
| `balance` | `NUMERIC(19, 4)`| No | No | Account balance in Indian Rupees (₹). |
| `version` | `BIGINT` | No | No | Hibernate optimistic locking version number. |

#### Invariant Protection:
* In `Account.java`, business invariant checks (`debit`, `credit`, `hasSufficientFunds`) prevent balances from becoming negative at the domain entity boundary.

---

### 2.2 Table: `transactions`
The immutable, append-only financial ledger. Entries in this table are never updated or deleted.

| Column | SQL Type | Nullable | Unique | Description |
|---|---|---|---|---|
| `id` | `BIGINT` | No | Yes (PK) | Ledger sequence identifier. |
| `packet_hash` | `VARCHAR(64)` | No | Yes | Lowercase hexadecimal SHA-256 hash of raw ciphertext. |
| `sender_vpa` | `VARCHAR(100)` | No | No | Sender's UPI ID. |
| `receiver_vpa` | `VARCHAR(100)` | No | No | Recipient's UPI ID. |
| `amount` | `NUMERIC(19, 4)`| No | No | Payment value in Rupees. |
| `status` | `VARCHAR(30)` | No | No | `TransactionStatus` string enum value. |
| `failure_reason`| `VARCHAR(255)` | Yes | No | Detailed message if transaction rejected. |
| `bridge_node_id`| `VARCHAR(100)` | Yes | No | Identity of the bridge device delivering packet. |
| `hop_count` | `INTEGER` | Yes | No | Number of mesh hops taken before cloud ingest. |
| `settled_at` | `TIMESTAMP` | No | No | Exact UTC timestamp populated via `@PrePersist`. |

---

## 3. Database Indexes & Query Optimizations

### 3.1 Primary B-Tree Index: `idx_tx_packet_hash`
```sql
CREATE UNIQUE INDEX idx_tx_packet_hash ON transactions(packet_hash);
```
* **Purpose**: Serves as the ultimate defense-in-depth barrier against duplicate settlement.
* **Performance**: Ingestion services invoke `TransactionRepository.existsByPacketHash(hash)`. A B-Tree index resolves existence in $O(\log N)$ time (under 1 millisecond on 10M rows), avoiding full-table scans.

### 3.2 Audit Log Index: `settled_at`
* Spring Data JPA query:
  ```java
  List<Transaction> findTop20ByOrderBySettledAtDesc();
  ```
* Generates:
  ```sql
  SELECT * FROM transactions ORDER BY settled_at DESC LIMIT 20;
  ```
* Provides real-time event streaming to the administrative and demo dashboards.

---

## 4. Monetary Precision: Why `NUMERIC(19, 4)` and `BigDecimal`?

### The Floating-Point Flaw
In standard IEEE 754 floating-point arithmetic (`float`, `double`):
```java
double a = 0.1;
double b = 0.2;
System.out.println(a + b); // Prints: 0.30000000000000004
```
In banking systems, binary rounding errors compound across millions of transactions, creating untraceable accounting discrepancies and regulatory violations.

### The GhostPay Standard:
1. All monetary fields are typed as **`java.math.BigDecimal`**.
2. SQL columns use **`NUMERIC(19, 4)`**:
   * Up to 15 integer digits (hundreds of trillions of Rupees).
   * 4 fractional decimal places (supporting micro-cents and fee calculations without rounding loss).

---

## 5. Optimistic Locking Mechanics

GhostPay relies on JPA `@Version` to handle concurrent account access:

```sql
-- Step 1: Read current account
SELECT id, vpa, balance, version FROM accounts WHERE vpa = 'alice@ghostpay';
-- Returns: balance = 500.00, version = 1

-- Step 2: Attempt debit in transaction
UPDATE accounts 
SET balance = 200.00, version = 2 
WHERE id = 1 AND version = 1;
```

* If two bridge nodes try to settle payments from Alice's account at the exact same millisecond:
  * Thread A updates row and sets `version = 2`.
  * Thread B executes `UPDATE ... WHERE id = 1 AND version = 1` ➔ 0 rows updated!
  * Hibernate throws `OptimisticLockException`. Thread B rolls back cleanly.

---

## 6. Multi-Environment Portability (H2 vs PostgreSQL)

GhostPay uses an environment-driven configuration template:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:h2:mem:ghostpay;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}
    driver-class-name: ${SPRING_DATASOURCE_DRIVER:org.h2.Driver}
    username: ${SPRING_DATASOURCE_USERNAME:sa}
    password: ${SPRING_DATASOURCE_PASSWORD:}
```

* **Local Machine**: Runs zero-setup in RAM with embedded H2 web console at `/h2-console`.
* **Production Cloud (Render / AWS / Supabase)**: The hosting platform sets `SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/ghostpay`. Spring Boot automatically boots PostgreSQL with no code changes.
