package com.example.ledger.transfer.locking;

import com.example.ledger.account.Account;

/** The two accounts of a money movement, loaded by a {@link LockingStrategy}. */
public record AccountPair(Account from, Account to) {
}
