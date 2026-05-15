package com.chronicle.jdbc;

import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.store.ConcurrentModificationException;
import com.chronicle.core.store.EventStore;
import org.postgresql.util.PGobject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.NonNull;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link EventStore}.
 * All SQL uses prepared statements — zero string concatenation.
 * Payload is stored as JSONB via PGobject — never string-interpolated into SQL.
 * Class is final to prevent unsafe subclassing; uses TransactionTemplate instead of @Transactional.
 */
public final class JdbcEventStore implements EventStore {

    // [SECURITY] Parameterized queries only — zero string concatenation in SQL
    // Each ? is bound as a typed parameter — SQL injection is structurally impossible
    private static final String INSERT_SQL =
            "INSERT INTO events (event_id, aggregate_id, aggregate_type, event_type, payload, version, created_at) " +
            "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)";

    // [SECURITY] Phase 1 version check — parameterized, no string interpolation
    private static final String MAX_VERSION_SQL =
            "SELECT COALESCE(MAX(version), 0) FROM events WHERE aggregate_id = ?";

    private static final String LOAD_SQL =
            "SELECT event_id, aggregate_id, aggregate_type, event_type, payload::text, version, created_at " +
            "FROM events WHERE aggregate_id = ? ORDER BY version ASC";

    private static final String LOAD_AFTER_SQL =
            "SELECT event_id, aggregate_id, aggregate_type, event_type, payload::text, version, created_at " +
            "FROM events WHERE aggregate_id = ? AND version > ? ORDER BY version ASC";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcEventStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public void save(UUID aggregateId, String aggregateType, List<StoredEvent> events, int expectedVersion) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events list must not be empty");
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // [SECURITY] Phase 1: explicit version check before INSERT batch
                // Catches conflicts early and surfaces the actual version to the caller.
                // Phase 2 (UNIQUE constraint) remains as safety net for concurrent transactions
                // that both pass Phase 1 simultaneously — defense in depth.
                Integer currentVersion = jdbcTemplate.queryForObject(MAX_VERSION_SQL, Integer.class, aggregateId);
                if (currentVersion != null && currentVersion > expectedVersion) {
                    throw new ConcurrentModificationException(aggregateId, expectedVersion, currentVersion);
                }

                for (StoredEvent event : events) {
                    // [SECURITY] Payload size re-validated before DB insert — defense in depth
                    if (event.payload().length() > 65536) {
                        throw new IllegalArgumentException(
                                "Event payload exceeds 64KB limit: " + event.payload().length() + " chars");
                    }
                    // [SECURITY] JSON structure validated before DB insert — prevents raw DB cast errors
                    // from propagating with internal PostgreSQL error details
                    validateJson(event.payload());

                    // [SECURITY] Payload inserted as PGobject(type="jsonb") — not as interpolated string
                    // This prevents any possibility of SQL injection through event payload content
                    PGobject jsonbPayload = new PGobject();
                    try {
                        jsonbPayload.setType("jsonb");
                        jsonbPayload.setValue(event.payload());
                    } catch (SQLException e) {
                        throw new IllegalArgumentException("Failed to create JSONB payload for event", e);
                    }

                    jdbcTemplate.update(INSERT_SQL,
                            event.eventId(),
                            event.aggregateId(),
                            event.aggregateType(),
                            event.eventType(),
                            jsonbPayload,
                            event.version(),
                            Timestamp.from(event.timestamp())
                    );
                }
            });
        } catch (DataIntegrityViolationException e) {
            // [SECURITY] UNIQUE constraint violation (aggregate_id, version) = concurrent write detected
            // Never expose internal DB error details to the caller
            throw new ConcurrentModificationException(aggregateId, expectedVersion, -1);
        }
    }

    @Override
    public List<StoredEvent> load(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        // [SECURITY] Prepared statement — parameterized query, aggregateId bound as typed parameter
        return jdbcTemplate.query(LOAD_SQL, new StoredEventRowMapper(), aggregateId);
    }

    @Override
    public List<StoredEvent> loadAfterVersion(UUID aggregateId, int afterVersion) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        // [SECURITY] Negative afterVersion rejected — value of -1 would return the entire event stream
        // effectively bypassing snapshot optimization and causing information disclosure
        if (afterVersion < 0) {
            throw new IllegalArgumentException("afterVersion must be >= 0, got: " + afterVersion);
        }
        // [SECURITY] Prepared statement — parameterized query
        return jdbcTemplate.query(LOAD_AFTER_SQL, new StoredEventRowMapper(), aggregateId, afterVersion);
    }

    // [SECURITY] JSON validation before DB insert — fail-fast with a clean IllegalArgumentException
    // Prevents raw PostgreSQL cast errors (which may expose internal details) from reaching the caller
    private static void validateJson(String payload) {
        try (JsonParser parser = JSON_FACTORY.createParser(payload)) {
            while (parser.nextToken() != null) { /* exhaust tokens to validate */ }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "payload is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static final class StoredEventRowMapper implements RowMapper<StoredEvent> {
        @Override
        public StoredEvent mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            return new StoredEvent(
                    UUID.fromString(rs.getString("event_id")),
                    UUID.fromString(rs.getString("aggregate_id")),
                    rs.getString("aggregate_type"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("version"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }
    }
}
