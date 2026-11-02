package com.ghostpay.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ghostpay.domain.Account;


@Repository 
public interface AccountRepository extends JpaRepository<Account, Long> {


    /**
     * Finds an account by its unique UPI Virtual Payment Address (e.g., "alice@ghostpay").
     * Returns Optional<Account> to force safe handling and prevent NullPointerExceptions.
     */
    Optional<Account> findByVpa(String vpa);


    /**
     * Fast existence check without loading the entire entity into memory.
     * Translates to: SELECT 1 FROM accounts WHERE vpa = ? LIMIT 1
     */
    boolean existsByVpa(String vpa);

}
