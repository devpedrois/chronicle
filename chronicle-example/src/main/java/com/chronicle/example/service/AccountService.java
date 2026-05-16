package com.chronicle.example.service;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.store.ConcurrentModificationException;
import com.chronicle.core.store.EventStore;
import com.chronicle.core.event.StoredEvent;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.dto.AccountResponse;
import com.chronicle.example.dto.CreateAccountRequest;
import com.chronicle.example.dto.DepositRequest;
import com.chronicle.example.dto.EventResponse;
import com.chronicle.example.dto.TransferRequest;
import com.chronicle.example.dto.WithdrawRequest;
import com.chronicle.example.exception.NotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AccountService {

    private static final int MAX_RETRIES = 3;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChronicleEngine<BankAccountState> engine;
    private final EventStore eventStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AccountService(
            ChronicleEngine<BankAccountState> engine,
            EventStore eventStore,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.engine = engine;
        this.eventStore = eventStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AccountResponse createAccount(CreateAccountRequest req) {
        AggregateRoot<BankAccountState> root = BankAccount.create(req.ownerName());
        engine.save(root);
        // Projection has eventual lag — build response from aggregate state directly
        return toResponse(root);
    }

    public AccountResponse deposit(UUID accountId, DepositRequest req) {
        return saveWithRetry(() -> {
            AggregateRoot<BankAccountState> root = engine.load(accountId)
                    .orElseThrow(() -> new NotFoundException(accountId));
            new BankAccount().deposit(root, req.amountCents(), req.description());
            return root;
        });
    }

    public AccountResponse withdraw(UUID accountId, WithdrawRequest req) {
        return saveWithRetry(() -> {
            AggregateRoot<BankAccountState> root = engine.load(accountId)
                    .orElseThrow(() -> new NotFoundException(accountId));
            new BankAccount().withdraw(root, req.amountCents(), req.description());
            return root;
        });
    }

    public AccountResponse transfer(UUID fromId, TransferRequest req) {
        // [SECURITY] Validate destination exists BEFORE debiting source.
        // Without this check: debit commits, credit throws NotFoundException → funds destroyed permanently.
        // This is still non-atomic (saga required for full atomicity) but prevents fund destruction for non-existent accounts.
        engine.load(req.toAccountId()).orElseThrow(() -> new NotFoundException(req.toAccountId()));

        AggregateRoot<BankAccountState> savedFromRoot = saveWithRetryRoot(() -> {
            AggregateRoot<BankAccountState> fromRoot = engine.load(fromId)
                    .orElseThrow(() -> new NotFoundException(fromId));
            new BankAccount().transfer(fromRoot, req.toAccountId(), req.amountCents(), req.description());
            return fromRoot;
        });
        saveWithRetryRoot(() -> {
            AggregateRoot<BankAccountState> toRoot = engine.load(req.toAccountId())
                    .orElseThrow(() -> new NotFoundException(req.toAccountId()));
            new BankAccount().receiveTransfer(toRoot, fromId, req.amountCents(), req.description());
            return toRoot;
        });
        return toResponse(savedFromRoot);
    }

    public AccountResponse getAccount(UUID accountId) {
        List<AccountResponse> results = jdbcTemplate.query(
                "SELECT account_id, owner_name, balance, created_at FROM balances WHERE account_id = ?",
                (rs, rowNum) -> new AccountResponse(
                        (UUID) rs.getObject("account_id"),
                        rs.getString("owner_name"),
                        rs.getLong("balance"),
                        rs.getTimestamp("created_at").toInstant()),
                accountId);
        if (results.isEmpty()) {
            throw new NotFoundException(accountId);
        }
        return results.get(0);
    }

    public List<EventResponse> getEvents(UUID accountId, Integer afterVersion) {
        // [SECURITY] Validate afterVersion client input — internal param name must not leak in error message
        if (afterVersion != null && afterVersion < 0) {
            throw new IllegalArgumentException("Invalid 'after' parameter: must be >= 0");
        }
        List<StoredEvent> events = afterVersion != null
                ? eventStore.loadAfterVersion(accountId, afterVersion)
                : eventStore.load(accountId);
        if (events.isEmpty()) {
            // Distinguish "account exists with no events after version" from "account not found"
            engine.load(accountId).orElseThrow(() -> new NotFoundException(accountId));
            return List.of();
        }
        return events.stream().map(this::toEventResponse).toList();
    }

    // Loads, mutates, saves with retry. Returns AccountResponse after successful save.
    private AccountResponse saveWithRetry(Supplier<AggregateRoot<BankAccountState>> loadAndMutate) {
        return toResponse(saveWithRetryRoot(loadAndMutate));
    }

    // Loads, mutates, saves with retry. Returns the root after successful save.
    private AggregateRoot<BankAccountState> saveWithRetryRoot(
            Supplier<AggregateRoot<BankAccountState>> loadAndMutate) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                AggregateRoot<BankAccountState> root = loadAndMutate.get();
                engine.save(root);
                return root;
            } catch (ConcurrentModificationException e) {
                // [SECURITY] Retry on optimistic lock conflict — transparent to client
                if (attempt == MAX_RETRIES) throw e;
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private AccountResponse toResponse(AggregateRoot<BankAccountState> root) {
        BankAccountState state = root.getState();
        // createdAt approximation — mutations return aggregate state, not projection
        return new AccountResponse(state.id(), state.ownerName(), state.balanceCents(), Instant.now());
    }

    // [SECURITY] Filtered summary — raw JSONB payload never returned to client
    private EventResponse toEventResponse(StoredEvent event) {
        Map<String, Object> summary;
        try {
            summary = objectMapper.readValue(event.payload(), MAP_TYPE);
        } catch (Exception e) {
            summary = Map.of("error", "Unparseable payload");
        }
        return new EventResponse(event.eventType(), event.version(), event.timestamp(), summary);
    }
}
