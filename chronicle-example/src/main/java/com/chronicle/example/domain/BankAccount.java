package com.chronicle.example.domain;

import com.chronicle.core.aggregate.Aggregate;
import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.event.DomainEvent;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;

import java.util.Objects;
import java.util.UUID;

/**
 * Bank account aggregate — encapsulates all business rules for account operations.
 * Command methods validate invariants BEFORE creating events — no invalid state ever enters the event stream.
 */
public class BankAccount extends Aggregate<BankAccountState> {

    @Override
    public BankAccountState initialState() {
        return new BankAccountState(null, "", 0L, false);
    }

    @Override
    public BankAccountState apply(BankAccountState state, DomainEvent event) {
        // apply() MUST be a pure function — no I/O, no mutation, no side effects
        return switch (event) {
            case AccountCreated e -> new BankAccountState(e.accountId(), e.ownerName(), 0L, true);
            case MoneyDeposited e -> new BankAccountState(state.id(), state.ownerName(),
                    state.balanceCents() + e.amountCents(), state.active());
            case MoneyWithdrawn e -> new BankAccountState(state.id(), state.ownerName(),
                    state.balanceCents() - e.amountCents(), state.active());
            case MoneyTransferred e -> new BankAccountState(state.id(), state.ownerName(),
                    state.balanceCents() - e.amountCents(), state.active());
            case MoneyReceived e -> new BankAccountState(state.id(), state.ownerName(),
                    state.balanceCents() + e.amountCents(), state.active());
            // [SECURITY] Explicit failure on unknown event — no silent ignore
            default -> throw new IllegalArgumentException(
                    "Unknown event type: " + event.getClass().getSimpleName());
        };
    }

    @Override
    public String aggregateType() {
        return "BankAccount";
    }

    /**
     * Creates a new bank account aggregate root with an AccountCreated event.
     *
     * @param ownerName the account owner name
     * @return populated AggregateRoot with one uncommitted event
     */
    public static AggregateRoot<BankAccountState> create(String ownerName) {
        Objects.requireNonNull(ownerName, "ownerName must not be null");
        // [SECURITY] Blank/oversized ownerName rejected — blank names are semantically invalid
        // and oversized names could cause storage bloat or exceed VARCHAR constraints downstream
        if (ownerName.isBlank()) {
            throw new IllegalArgumentException("ownerName must not be blank");
        }
        if (ownerName.length() > 100) {
            throw new IllegalArgumentException("ownerName must not exceed 100 characters");
        }
        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());
        UUID id = UUID.randomUUID();
        root.setId(id);
        root.handleEvent(new AccountCreated(id, ownerName));
        return root;
    }

    /**
     * Deposits money into the account.
     *
     * @param root        the account aggregate root
     * @param amountCents amount in cents, must be positive and <= 1,000,000.00
     * @param description human-readable description
     */
    public void deposit(AggregateRoot<BankAccountState> root, long amountCents, String description) {
        Objects.requireNonNull(root, "root must not be null");
        // [SECURITY] Null description rejected — null would cause NPE during Jackson serialization,
        // producing a misleading error that leaks internal structure
        Objects.requireNonNull(description, "description must not be null");
        if (description.length() > 255) {
            throw new IllegalArgumentException("description must not exceed 255 characters");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (amountCents > 100_000_000) {
            throw new IllegalArgumentException("Amount exceeds maximum of 1,000,000.00");
        }
        if (!root.getState().active()) {
            throw new IllegalStateException("Account is inactive");
        }
        root.handleEvent(new MoneyDeposited(amountCents, description));
    }

    /**
     * Withdraws money from the account.
     * // [SECURITY] Balance check BEFORE creating event — prevents negative balance invariant violation.
     * Checking after handleEvent() would allow a race condition to produce a withdrawal event
     * with insufficient funds, corrupting the event stream.
     *
     * @param root        the account aggregate root
     * @param amountCents amount in cents, must be positive and <= 1,000,000.00
     * @param description human-readable description
     */
    public void withdraw(AggregateRoot<BankAccountState> root, long amountCents, String description) {
        Objects.requireNonNull(root, "root must not be null");
        // [SECURITY] Null description rejected — null would cause NPE during Jackson serialization
        Objects.requireNonNull(description, "description must not be null");
        if (description.length() > 255) {
            throw new IllegalArgumentException("description must not exceed 255 characters");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (amountCents > 100_000_000) {
            throw new IllegalArgumentException("Amount exceeds maximum of 1,000,000.00");
        }
        if (!root.getState().active()) {
            throw new IllegalStateException("Account is inactive");
        }
        // [SECURITY] Balance check BEFORE creating event — prevents negative balance
        if (root.getState().balanceCents() < amountCents) {
            throw new InsufficientFundsException(root.getId(), root.getState().balanceCents(), amountCents);
        }
        root.handleEvent(new MoneyWithdrawn(amountCents, description));
    }

    /**
     * Transfers money from this account to another.
     *
     * <p>// [SECURITY] Balance check BEFORE creating event — same reasoning as withdraw.
     *
     * <p><b>ARCHITECTURE WARNING — non-atomic cross-aggregate transfer:</b>
     * The caller MUST also call {@link #receiveTransfer} on the destination aggregate and save it.
     * If the JVM crashes after {@code engine.save(sourceRoot)} but before {@code engine.save(destRoot)},
     * the debit is recorded but the credit is not — money is permanently destroyed.
     * The service layer MUST implement a saga or outbox pattern to compensate for this failure window.
     * Do NOT call this method without a corresponding {@code receiveTransfer} + save on the destination.
     *
     * @param root          the source account aggregate root
     * @param toAccountId   destination account UUID (existence is NOT validated here — service layer responsibility)
     * @param amountCents   amount in cents, must be positive and &lt;= 1,000,000.00
     * @param description   human-readable description
     */
    public void transfer(AggregateRoot<BankAccountState> root, UUID toAccountId, long amountCents, String description) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(toAccountId, "toAccountId must not be null");
        // [SECURITY] Null description rejected — null would cause NPE during Jackson serialization
        Objects.requireNonNull(description, "description must not be null");
        if (description.length() > 255) {
            throw new IllegalArgumentException("description must not exceed 255 characters");
        }
        // [SECURITY] Self-transfer rejected — transferring to self reduces balance without any corresponding credit,
        // creating a money-destruction vulnerability exploitable via the API
        if (toAccountId.equals(root.getId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (amountCents > 100_000_000) {
            throw new IllegalArgumentException("Amount exceeds maximum of 1,000,000.00");
        }
        if (!root.getState().active()) {
            throw new IllegalStateException("Account is inactive");
        }
        // [SECURITY] Balance check BEFORE creating event — prevents negative balance
        if (root.getState().balanceCents() < amountCents) {
            throw new InsufficientFundsException(root.getId(), root.getState().balanceCents(), amountCents);
        }
        root.handleEvent(new MoneyTransferred(toAccountId, amountCents, description));
    }

    /**
     * Credits the destination account as the receiving side of a transfer.
     * Must be called on the destination aggregate after calling {@link #transfer} on the source.
     *
     * @param root          the destination account aggregate root
     * @param fromAccountId source account UUID
     * @param amountCents   amount in cents, must be positive and <= 1,000,000.00
     * @param description   human-readable description
     */
    public void receiveTransfer(AggregateRoot<BankAccountState> root, UUID fromAccountId, long amountCents, String description) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(fromAccountId, "fromAccountId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (description.length() > 255) {
            throw new IllegalArgumentException("description must not exceed 255 characters");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (amountCents > 100_000_000) {
            throw new IllegalArgumentException("Amount exceeds maximum of 1,000,000.00");
        }
        if (!root.getState().active()) {
            throw new IllegalStateException("Account is inactive");
        }
        root.handleEvent(new MoneyReceived(fromAccountId, amountCents, description));
    }
}
