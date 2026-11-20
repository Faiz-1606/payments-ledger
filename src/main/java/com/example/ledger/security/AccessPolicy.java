package com.example.ledger.security;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.example.ledger.account.Account;
import com.example.ledger.common.error.ForbiddenException;

/**
 * Data-level authorization rules: decisions that need to know who owns what.
 * Every rule lives here, so they can be reviewed (and tested) in one place.
 */
@Component
public class AccessPolicy {

    /** Rule: users read only their own accounts; admins may read any account. */
    public void requireCanView(CurrentUser caller, Account account) {
        if (caller.admin() || isOwner(caller, account)) {
            return;
        }
        throw new ForbiddenException("Account " + account.getId() + " does not belong to you");
    }

    /**
     * Rule: money can only leave an account at its owner's request. This applies to admins
     * too: their extra power is deposits and reconciliation, not other people's wallets.
     */
    public void requireCanDebit(CurrentUser caller, Account account) {
        if (!isOwner(caller, account)) {
            throw new ForbiddenException("You can only move money out of your own accounts");
        }
    }

    public boolean isOwner(CurrentUser caller, Account account) {
        return Objects.equals(account.getOwnerId(), caller.userId());
    }
}
