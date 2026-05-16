package com.chronicle.example.projection;

import com.chronicle.core.event.DomainEvent;
import com.chronicle.core.event.EventSerializer;
import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.projection.Projection;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Builds and maintains the {@code balances} read model from domain events.
 *
 * <p>Exactly-once semantics: each event is marked in {@code processed_projection_events}
 * atomically with the balance update. Re-delivery after a crash is safe — the duplicate
 * INSERT ON CONFLICT DO NOTHING causes the handler to skip the already-applied event.
 *
 * <p>Idempotency guarantees per event type:
 * <ul>
 *   <li>AccountCreated: ON CONFLICT DO NOTHING on balances + deduplication table</li>
 *   <li>MoneyDeposited/Withdrawn/Transferred/Received: deduplication table prevents double-apply</li>
 * </ul>
 */
public class AccountBalanceProjection implements Projection {

    private static final Logger log = LoggerFactory.getLogger(AccountBalanceProjection.class);
    private static final String NAME = "account-balance";

    // [SECURITY] Parameterized queries only — zero string concatenation in SQL
    // [SECURITY] ON CONFLICT DO NOTHING — idempotent insert for AccountCreated
    private static final String INSERT_BALANCE_SQL =
            "INSERT INTO balances (account_id, owner_name, balance, updated_at) " +
            "VALUES (?, ?, 0, NOW()) " +
            "ON CONFLICT (account_id) DO NOTHING";

    // [SECURITY] Atomic update (balance = balance + ?) — no read-then-write race condition
    private static final String CREDIT_SQL =
            "UPDATE balances SET balance = balance + ?, updated_at = NOW() WHERE account_id = ?";

    // [SECURITY] Atomic update — same reasoning as CREDIT_SQL
    private static final String DEBIT_SQL =
            "UPDATE balances SET balance = balance - ?, updated_at = NOW() WHERE account_id = ?";

    // [SECURITY] Exactly-once deduplication — INSERT ON CONFLICT DO NOTHING is atomic.
    // Returns 1 if newly processed, 0 if already seen. Prevents double-credit/double-debit
    // when at-least-once delivery re-delivers an event after a partial failure.
    private static final String MARK_PROCESSED_SQL =
            "INSERT INTO processed_projection_events (event_id, projection_name) " +
            "VALUES (?, ?) ON CONFLICT DO NOTHING";

    private static final String DELETE_ALL_SQL = "DELETE FROM balances";
    private static final String DELETE_PROCESSED_SQL =
            "DELETE FROM processed_projection_events WHERE projection_name = ?";

    private final JdbcTemplate jdbcTemplate;
    private final EventSerializer<?> serializer;
    private final TransactionTemplate transactionTemplate;

    public AccountBalanceProjection(
            JdbcTemplate jdbcTemplate,
            EventSerializer<?> serializer,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        // [SECURITY] TransactionTemplate wraps markProcessed() + balance update in one transaction.
        // If the transaction rolls back, the event is not marked as processed → safe to retry.
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void handle(StoredEvent event) {
        // [SECURITY] Exactly-once semantics: markProcessed() and balance update execute in one transaction.
        // Crash after transaction commits → event_id in processed_projection_events → re-delivery skipped.
        // Crash before transaction commits → nothing persisted → re-delivery processes normally.
        transactionTemplate.executeWithoutResult(status -> handleInTransaction(event));
    }

    private void handleInTransaction(StoredEvent event) {
        // [SECURITY] Atomic deduplication check — if event_id already in processed table, skip entirely.
        // This is the fix for at-least-once re-delivery producing double-credit/double-debit.
        if (!markProcessed(event)) {
            log.debug("Event {} already processed by '{}' — skipping (idempotency guard)",
                    event.eventId(), NAME);
            return;
        }

        DomainEvent domainEvent;
        try {
            domainEvent = serializer.deserialize(event.payload(), event.eventType());
        } catch (IllegalArgumentException e) {
            // Event type not in whitelist — projection ignores unregistered types without crashing.
            // [SECURITY] Registry rejects unknown types; log at debug to avoid log-spam on replay of mixed streams.
            log.debug("AccountBalanceProjection ignoring unregistered event type '{}'", event.eventType());
            return;
        }
        switch (domainEvent) {
            case AccountCreated e -> {
                // [SECURITY] ON CONFLICT DO NOTHING — idempotent; deduplication table is the primary guard
                jdbcTemplate.update(INSERT_BALANCE_SQL, event.aggregateId(), e.ownerName());
            }
            case MoneyDeposited e -> {
                // [SECURITY] Atomic credit — balance = balance + amountCents
                int rows = jdbcTemplate.update(CREDIT_SQL, e.amountCents(), event.aggregateId());
                // [SECURITY] Zero rows = balance row missing = AccountCreated was skipped or position corrupt
                if (rows == 0) {
                    log.warn("[SECURITY] MoneyDeposited event {} for account {} found no balance row — " +
                            "credit of {} cents LOST. AccountCreated may have been skipped or projection position is corrupt.",
                            event.eventId(), event.aggregateId(), e.amountCents());
                }
            }
            case MoneyWithdrawn e -> {
                // [SECURITY] Atomic debit — balance = balance - amountCents
                int rows = jdbcTemplate.update(DEBIT_SQL, e.amountCents(), event.aggregateId());
                if (rows == 0) {
                    log.warn("[SECURITY] MoneyWithdrawn event {} for account {} found no balance row — " +
                            "debit of {} cents LOST. Projection position may be corrupt.",
                            event.eventId(), event.aggregateId(), e.amountCents());
                }
            }
            case MoneyTransferred e -> {
                // [SECURITY] Atomic debit on source account only — destination handled via MoneyReceived
                int rows = jdbcTemplate.update(DEBIT_SQL, e.amountCents(), event.aggregateId());
                if (rows == 0) {
                    log.warn("[SECURITY] MoneyTransferred event {} for account {} found no balance row — " +
                            "debit of {} cents LOST.",
                            event.eventId(), event.aggregateId(), e.amountCents());
                }
            }
            case MoneyReceived e -> {
                // [SECURITY] Atomic credit on destination account
                int rows = jdbcTemplate.update(CREDIT_SQL, e.amountCents(), event.aggregateId());
                if (rows == 0) {
                    log.warn("[SECURITY] MoneyReceived event {} for account {} found no balance row — " +
                            "credit of {} cents LOST.",
                            event.eventId(), event.aggregateId(), e.amountCents());
                }
            }
            default -> log.debug("AccountBalanceProjection ignoring unhandled event type: {}", event.eventType());
        }
    }

    // [SECURITY] Atomic deduplication — INSERT ON CONFLICT DO NOTHING.
    // Returns true if this is the first time the event is processed; false if already seen.
    // Must be called inside an active transaction so the mark and the balance update are atomic.
    private boolean markProcessed(StoredEvent event) {
        int rows = jdbcTemplate.update(MARK_PROCESSED_SQL, event.eventId(), NAME);
        return rows > 0;
    }

    @Override
    public void reset() {
        // [SECURITY] Both read model AND deduplication table are cleared in a single transaction.
        // Non-atomic reset (two separate updates) risks crash between them leaving balances deleted
        // but processed_projection_events intact → all events skipped on replay → empty read model forever.
        // projection_positions is cleared separately via ProjectionEngine.resetProjection().
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(DELETE_ALL_SQL);
            jdbcTemplate.update(DELETE_PROCESSED_SQL, NAME);
        });
    }
}
