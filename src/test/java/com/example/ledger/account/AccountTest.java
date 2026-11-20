package com.example.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Plain unit tests of the Account entity: no Spring, no database, milliseconds to run. */
class AccountTest {

    @Test
    void creditThenDebitChangesBalance() {
        Account account = Account.openUserWallet(1L);
        account.credit(1_000);
        account.debit(400);
        assertThat(account.getBalancePaise()).isEqualTo(600);
    }

    @Test
    void userWalletCannotGoNegative() {
        Account account = Account.openUserWallet(1L);
        account.credit(100);
        assertThatThrownBy(() -> account.debit(101)).isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalancePaise()).as("balance unchanged after a refused debit").isEqualTo(100);
    }

    @Test
    void debitOfExactBalanceIsAllowed() {
        Account account = Account.openUserWallet(1L);
        account.credit(100);
        account.debit(100);
        assertThat(account.getBalancePaise()).isZero();
    }

    @Test
    void amountsMustBePositive() {
        Account account = Account.openUserWallet(1L);
        assertThatThrownBy(() -> account.credit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowThrowsInsteadOfWrappingAround() {
        Account account = Account.openUserWallet(1L);
        account.credit(Long.MAX_VALUE);
        assertThatThrownBy(() -> account.credit(1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void canDebitReflectsTheNoNegativeRule() {
        Account account = Account.openUserWallet(1L);
        account.credit(50);
        assertThat(account.canDebit(50)).isTrue();
        assertThat(account.canDebit(51)).isFalse();
    }
}
