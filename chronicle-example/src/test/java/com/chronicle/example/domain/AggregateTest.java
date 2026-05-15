package com.chronicle.example.domain;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.event.DomainEvent;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateTest {

    private BankAccount bankAccount;

    @BeforeEach
    void setUp() {
        bankAccount = new BankAccount();
    }

    @Test
    @DisplayName("create account sets correct initial state")
    void shouldCreateAccountWithCorrectInitialState() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Alice");

        BankAccountState state = root.getState();
        assertThat(state.id()).isNotNull();
        assertThat(state.ownerName()).isEqualTo("Alice");
        assertThat(state.balanceCents()).isZero();
        assertThat(state.active()).isTrue();
        assertThat(root.getVersion()).isEqualTo(1);
        assertThat(root.getUncommittedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("deposit increases balance")
    void shouldIncreaseBalanceOnDeposit() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Bob");

        bankAccount.deposit(root, 500L, "first");
        bankAccount.deposit(root, 300L, "second");

        assertThat(root.getState().balanceCents()).isEqualTo(800L);
    }

    @Test
    @DisplayName("withdraw decreases balance")
    void shouldDecreaseBalanceOnWithdraw() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Carol");
        bankAccount.deposit(root, 500L, "deposit");

        bankAccount.withdraw(root, 200L, "withdraw");

        assertThat(root.getState().balanceCents()).isEqualTo(300L);
    }

    @Test
    @DisplayName("withdraw with insufficient funds throws InsufficientFundsException")
    void shouldThrowOnInsufficientFunds() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Dave");
        bankAccount.deposit(root, 100L, "deposit");

        assertThatThrownBy(() -> bankAccount.withdraw(root, 200L, "overdraft"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("withdraw exact balance succeeds")
    void shouldWithdrawExactBalance() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Eve");
        bankAccount.deposit(root, 500L, "deposit");

        bankAccount.withdraw(root, 500L, "all");

        assertThat(root.getState().balanceCents()).isZero();
    }

    @Test
    @DisplayName("loadFromHistory reconstructs state correctly")
    void shouldReconstructStateFromHistory() {
        UUID accountId = UUID.randomUUID();
        List<DomainEvent> history = List.of(
                new AccountCreated(accountId, "Frank"),
                new MoneyDeposited(1000L, "initial"),
                new MoneyWithdrawn(400L, "atm")
        );

        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());
        root.setId(accountId);
        root.loadFromHistory(history);

        assertThat(root.getState().balanceCents()).isEqualTo(600L);
        assertThat(root.getState().ownerName()).isEqualTo("Frank");
        assertThat(root.getVersion()).isEqualTo(3);
        assertThat(root.getUncommittedEvents()).isEmpty();
    }

    @Test
    @DisplayName("uncommitted events tracked correctly")
    void shouldTrackAndClearUncommittedEvents() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Grace");
        bankAccount.deposit(root, 100L, "d1");

        assertThat(root.getUncommittedEvents()).hasSize(2);

        root.clearUncommittedEvents();

        assertThat(root.getUncommittedEvents()).isEmpty();
    }

    @Test
    @DisplayName("inactive account rejects deposit")
    void shouldRejectDepositOnInactiveAccount() {
        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());
        root.setId(UUID.randomUUID());
        // State starts inactive (initialState returns active=false, id=null)

        assertThatThrownBy(() -> bankAccount.deposit(root, 100L, "blocked"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    @DisplayName("inactive account rejects withdraw")
    void shouldRejectWithdrawOnInactiveAccount() {
        AggregateRoot<BankAccountState> root = new AggregateRoot<>(new BankAccount());
        root.setId(UUID.randomUUID());

        assertThatThrownBy(() -> bankAccount.withdraw(root, 100L, "blocked"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    @DisplayName("deposit amount must be positive")
    void shouldRejectNonPositiveDepositAmount() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Hank");

        assertThatThrownBy(() -> bankAccount.deposit(root, 0L, "zero"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> bankAccount.deposit(root, -1L, "negative"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deposit amount must not exceed maximum")
    void shouldRejectDepositExceedingMaximum() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Iris");

        assertThatThrownBy(() -> bankAccount.deposit(root, 100_000_001L, "too much"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
    }
}
