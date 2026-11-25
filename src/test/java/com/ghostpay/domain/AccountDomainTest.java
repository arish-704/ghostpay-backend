package com.ghostpay.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountDomainTest {

    @Test
    @DisplayName("Should successfully debit account when funds are sufficient")
    void shouldDebitWhenSufficientFunds() {
        Account account = Account.builder()
                .vpa("alice@ghostpay")
                .ownerName("Alice Sharma")
                .balance(new BigDecimal("500.00"))
                .build();

        account.debit(new BigDecimal("200.00"));
        assertEquals(0, new BigDecimal("300.00").compareTo(account.getBalance()));
    }

    @Test
    @DisplayName("Should reject debit when funds are insufficient")
    void shouldThrowWhenInsufficientFunds() {
        Account account = Account.builder()
                .vpa("alice@ghostpay")
                .ownerName("Alice Sharma")
                .balance(new BigDecimal("100.00"))
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                account.debit(new BigDecimal("150.00"))
        );
        assertTrue(ex.getMessage().contains("Insufficient balance"));
    }

    @Test
    @DisplayName("Should reject debit with negative or zero amounts")
    void shouldRejectInvalidDebitAmounts() {
        Account account = Account.builder()
                .vpa("alice@ghostpay")
                .ownerName("Alice Sharma")
                .balance(new BigDecimal("100.00"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> account.debit(new BigDecimal("0.00")));
        assertThrows(IllegalArgumentException.class, () -> account.debit(new BigDecimal("-50.00")));
        assertThrows(IllegalArgumentException.class, () -> account.debit(null));
    }

    @Test
    @DisplayName("Should successfully credit account with positive amount")
    void shouldCreditPositiveAmount() {
        Account account = Account.builder()
                .vpa("bob@ghostpay")
                .ownerName("Bob Verma")
                .balance(new BigDecimal("200.00"))
                .build();

        account.credit(new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("250.00").compareTo(account.getBalance()));
    }

    @Test
    @DisplayName("Should reject credit with negative or zero amounts")
    void shouldRejectInvalidCreditAmounts() {
        Account account = Account.builder()
                .vpa("bob@ghostpay")
                .ownerName("Bob Verma")
                .balance(new BigDecimal("200.00"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> account.credit(new BigDecimal("0.00")));
        assertThrows(IllegalArgumentException.class, () -> account.credit(new BigDecimal("-20.00")));
        assertThrows(IllegalArgumentException.class, () -> account.credit(null));
    }

    @Test
    @DisplayName("hasSufficientFunds returns true only when balance >= amount")
    void shouldCheckSufficientFundsAccurately() {
        Account account = Account.builder()
                .vpa("alice@ghostpay")
                .ownerName("Alice")
                .balance(new BigDecimal("100.00"))
                .build();

        assertTrue(account.hasSufficientFunds(new BigDecimal("100.00")));
        assertTrue(account.hasSufficientFunds(new BigDecimal("99.99")));
        assertFalse(account.hasSufficientFunds(new BigDecimal("100.01")));
        assertFalse(account.hasSufficientFunds(null));
    }
}
