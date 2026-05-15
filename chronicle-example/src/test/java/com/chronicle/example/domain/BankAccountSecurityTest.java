package com.chronicle.example.domain;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.event.DomainEvent;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.core.snapshot.Snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial tests for BankAccount domain and AggregateRoot.
 * Each test is named after the attack vector it closes.
 */
class BankAccountSecurityTest {

    private BankAccount bankAccount;

    @BeforeEach
    void setUp() {
        bankAccount = new BankAccount();
    }

    // ─── Input Validation ────────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] blank ownerName bypasses requireNonNull — must be rejected")
    void shouldRejectBlankOwnerName() {
        assertThatThrownBy(() -> BankAccount.create("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BankAccount.create(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[SECURITY] null ownerName must be rejected at domain boundary")
    void shouldRejectNullOwnerName() {
        assertThatThrownBy(() -> BankAccount.create(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("[SECURITY] oversized ownerName (>100 chars) rejected — prevents storage bloat and VARCHAR overflow")
    void shouldRejectOversizedOwnerName() {
        String tooLong = "A".repeat(101);

        assertThatThrownBy(() -> BankAccount.create(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("[SECURITY] null description in deposit rejected — prevents silent NPE during serialization")
    void shouldRejectNullDescriptionOnDeposit() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Alice");

        assertThatThrownBy(() -> bankAccount.deposit(root, 100L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("[SECURITY] null description in withdraw rejected — prevents silent NPE during serialization")
    void shouldRejectNullDescriptionOnWithdraw() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Bob");
        bankAccount.deposit(root, 500L, "init");

        assertThatThrownBy(() -> bankAccount.withdraw(root, 100L, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ─── Self-Transfer ────────────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] self-transfer destroys money — must be rejected at domain level")
    void shouldRejectSelfTransfer() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Charlie");
        bankAccount.deposit(root, 1000L, "init");

        assertThatThrownBy(() -> bankAccount.transfer(root, root.getId(), 500L, "to self"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
    }

    // ─── Negative Balance Invariant ───────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] sequential withdrawal attacks cannot drive balance negative")
    void shouldPreventNegativeBalanceThroughSequentialWithdrawals() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Dave");
        bankAccount.deposit(root, 1000L, "init");

        bankAccount.withdraw(root, 600L, "first");

        assertThatThrownBy(() -> bankAccount.withdraw(root, 500L, "second — exceeds remaining 400"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(root.getState().balanceCents()).isEqualTo(400L);
    }

    @Test
    @DisplayName("[SECURITY] zero-balance account cannot be withdrawn from")
    void shouldRejectWithdrawFromZeroBalance() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Eve");

        assertThatThrownBy(() -> bankAccount.withdraw(root, 1L, "from empty"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("[SECURITY] InsufficientFundsException carries correct diagnostic fields")
    void shouldExposeCorrectFieldsInInsufficientFundsException() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Frank");
        bankAccount.deposit(root, 100L, "deposit");
        UUID accountId = root.getId();

        assertThatThrownBy(() -> bankAccount.withdraw(root, 300L, "overdraft"))
                .isInstanceOf(InsufficientFundsException.class)
                .satisfies(e -> {
                    InsufficientFundsException ife = (InsufficientFundsException) e;
                    assertThat(ife.getAccountId()).isEqualTo(accountId);
                    assertThat(ife.getCurrentBalance()).isEqualTo(100L);
                    assertThat(ife.getRequestedAmount()).isEqualTo(300L);
                });
    }

    // ─── AggregateRoot Null Safety ────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] null event to handleEvent causes version/state desync — must be rejected")
    void shouldRejectNullEventInHandleEvent() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Grace");

        assertThatThrownBy(() -> root.handleEvent(null))
                .isInstanceOf(NullPointerException.class);

        // version must remain unchanged — no partial state corruption
        assertThat(root.getVersion()).isEqualTo(1);
        assertThat(root.getUncommittedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("[SECURITY] null history list to loadFromHistory must be rejected cleanly")
    void shouldRejectNullHistoryList() {
        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());
        root.setId(UUID.randomUUID());

        assertThatThrownBy(() -> root.loadFromHistory(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("[SECURITY] null element inside history list corrupts version counter — must be rejected")
    void shouldRejectNullElementInHistoryList() {
        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());
        UUID id = UUID.randomUUID();
        root.setId(id);

        List<DomainEvent> historyWithNull = new ArrayList<>();
        historyWithNull.add(new AccountCreated(id, "Hank"));
        historyWithNull.add(null);

        assertThatThrownBy(() -> root.loadFromHistory(historyWithNull))
                .isInstanceOf(NullPointerException.class);
    }

    // ─── Unknown Event in apply() ─────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] unknown event type in apply() fails fast — no silent state corruption")
    void shouldFailFastOnUnknownEventInApply() {
        record UnknownEvent() implements DomainEvent {}

        BankAccountState state = new BankAccountState(UUID.randomUUID(), "Iris", 0L, true);

        assertThatThrownBy(() -> bankAccount.apply(state, new UnknownEvent()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    // ─── Boundary Amounts ─────────────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] deposit exactly at maximum boundary succeeds")
    void shouldAcceptMaximumDepositAmount() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Jack");

        bankAccount.deposit(root, 100_000_000L, "max deposit");

        assertThat(root.getState().balanceCents()).isEqualTo(100_000_000L);
    }

    @Test
    @DisplayName("[SECURITY] deposit one cent above maximum is rejected")
    void shouldRejectDepositOneAboveMaximum() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Karen");

        assertThatThrownBy(() -> bankAccount.deposit(root, 100_000_001L, "over max"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[SECURITY] getUncommittedEvents returns unmodifiable list — caller cannot inject events")
    void shouldReturnUnmodifiableUncommittedEvents() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Leo");

        assertThatThrownBy(() -> root.getUncommittedEvents().add(new MoneyDeposited(999L, "injected")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ─── Description Length Validation ───────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] oversized description on deposit rejected — domain-level enforcement, not just DTO")
    void shouldRejectOversizedDescriptionOnDeposit() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Mike");
        String tooLong = "x".repeat(256);

        assertThatThrownBy(() -> bankAccount.deposit(root, 100L, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("[SECURITY] oversized description on withdraw rejected — domain-level enforcement")
    void shouldRejectOversizedDescriptionOnWithdraw() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Nina");
        bankAccount.deposit(root, 500L, "init");
        String tooLong = "x".repeat(256);

        assertThatThrownBy(() -> bankAccount.withdraw(root, 100L, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("[SECURITY] oversized description on transfer rejected — domain-level enforcement")
    void shouldRejectOversizedDescriptionOnTransfer() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Oscar");
        bankAccount.deposit(root, 500L, "init");
        String tooLong = "x".repeat(256);

        assertThatThrownBy(() -> bankAccount.transfer(root, UUID.randomUUID(), 100L, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("description at max boundary (255 chars) accepted")
    void shouldAcceptDescriptionAtMaxBoundary() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Paula");
        String maxLen = "x".repeat(255);

        bankAccount.deposit(root, 100L, maxLen);

        assertThat(root.getState().balanceCents()).isEqualTo(100L);
    }

    // ─── receiveTransfer ──────────────────────────────────────────────────────

    @Test
    @DisplayName("receiveTransfer credits destination account with correct amount")
    void receiveTransferCreditsDestionation() {
        AggregateRoot<BankAccountState> source = BankAccount.create("Quinn");
        bankAccount.deposit(source, 1000L, "init");
        AggregateRoot<BankAccountState> dest = BankAccount.create("Rachel");

        bankAccount.transfer(source, dest.getId(), 300L, "payment");
        bankAccount.receiveTransfer(dest, source.getId(), 300L, "payment received");

        assertThat(source.getState().balanceCents()).isEqualTo(700L);
        assertThat(dest.getState().balanceCents()).isEqualTo(300L);
    }

    @Test
    @DisplayName("[SECURITY] receiveTransfer on inactive account rejected")
    void receiveTransferRejectedOnInactiveAccount() {
        AggregateRoot<BankAccountState> dest = new AggregateRoot<>(new BankAccount());
        dest.setId(UUID.randomUUID());

        assertThatThrownBy(() -> bankAccount.receiveTransfer(dest, UUID.randomUUID(), 100L, "credit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    @DisplayName("[SECURITY] receiveTransfer null fromAccountId rejected")
    void receiveTransferRejectsNullFromAccountId() {
        AggregateRoot<BankAccountState> dest = BankAccount.create("Sam");

        assertThatThrownBy(() -> bankAccount.receiveTransfer(dest, null, 100L, "credit"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("[SECURITY] receiveTransfer with oversized description rejected")
    void receiveTransferRejectsOversizedDescription() {
        AggregateRoot<BankAccountState> dest = BankAccount.create("Tina");
        String tooLong = "x".repeat(256);

        assertThatThrownBy(() -> bankAccount.receiveTransfer(dest, UUID.randomUUID(), 100L, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    // ─── AggregateRoot Setter Validation ─────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] setVersion with negative value rejected — prevents expectedVersion corruption in save()")
    void aggregateRootRejectsNegativeVersion() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Uma");

        assertThatThrownBy(() -> root.setVersion(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be >= 0");
    }

    @Test
    @DisplayName("[SECURITY] setId with null rejected — prevents null aggregate_id corrupting event stream")
    void aggregateRootRejectsNullId() {
        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());

        assertThatThrownBy(() -> root.setId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("[SECURITY] setState with null rejected — null state bypasses apply() invariant")
    void aggregateRootRejectsNullState() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Victor");

        assertThatThrownBy(() -> root.setState(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ─── EventTypeRegistry Duplicate Class ───────────────────────────────────

    @Test
    @DisplayName("[SECURITY] registering same class under two type names rejected — prevents typeNameFor() aliasing")
    void eventTypeRegistryRejectsDuplicateClass() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register("MoneyReceived", MoneyReceived.class);

        assertThatThrownBy(() -> registry.register("MoneyReceivedAlias", MoneyReceived.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered as");
    }

    // ─── Snapshot Checksum Format ─────────────────────────────────────────────

    @Test
    @DisplayName("[SECURITY] Snapshot rejects malformed checksum (not 64 lowercase hex chars)")
    void snapshotRejectsMalformedChecksum() {
        assertThatThrownBy(() -> new Snapshot(
                UUID.randomUUID(), "BankAccount", "{}", 1, "not-a-sha256", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256 hex");

        assertThatThrownBy(() -> new Snapshot(
                UUID.randomUUID(), "BankAccount", "{}", 1, "ABCD".repeat(16), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256 hex");
    }
}
