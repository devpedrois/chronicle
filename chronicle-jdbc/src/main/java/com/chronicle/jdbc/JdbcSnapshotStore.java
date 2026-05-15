
package com.chronicle.jdbc;

import com.chronicle.core.snapshot.Snapshot;
import com.chronicle.core.store.SnapshotStore;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link SnapshotStore}.
 * UPSERT semantics: one snapshot per aggregate.
 * On load, SHA-256 checksum is recomputed and compared to detect tampering.
 */
public final class JdbcSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSnapshotStore.class);

    // [SECURITY] Parameterized queries only — zero string concatenation in SQL
    private static final String UPSERT_SQL =
            "INSERT INTO snapshots (aggregate_id, aggregate_type, state, version, checksum, created_at) " +
            "VALUES (?, ?, ?::jsonb, ?, ?, ?) " +
            "ON CONFLICT (aggregate_id) DO UPDATE SET " +
            "aggregate_type = EXCLUDED.aggregate_type, " +
            "state = EXCLUDED.state, " +
            "version = EXCLUDED.version, " +
            "checksum = EXCLUDED.checksum, " +
            "created_at = EXCLUDED.created_at";

    private static final String LOAD_SQL =
            "SELECT aggregate_id, aggregate_type, state::text, version, checksum, created_at " +
            "FROM snapshots WHERE aggregate_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcSnapshotStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public void saveSnapshot(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        PGobject jsonbState = new PGobject();
        try {
            jsonbState.setType("jsonb");
            jsonbState.setValue(snapshot.state());
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create JSONB for snapshot state", e);
        }

        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(UPSERT_SQL,
                        snapshot.aggregateId(),
                        snapshot.aggregateType(),
                        jsonbState,
                        snapshot.version(),
                        snapshot.checksum(),
                        Timestamp.from(snapshot.timestamp())
                ));
    }

    @Override
    public Optional<Snapshot> loadLatest(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");

        List<Snapshot> results = jdbcTemplate.query(LOAD_SQL, (rs, rowNum) -> new Snapshot(
                UUID.fromString(rs.getString("aggregate_id")),
                rs.getString("aggregate_type"),
                rs.getString("state"),
                rs.getInt("version"),
                rs.getString("checksum"),
                rs.getTimestamp("created_at").toInstant()
        ), aggregateId);

        if (results.isEmpty()) {
            return Optional.empty();
        }

        Snapshot loaded = results.get(0);

        // [SECURITY] Snapshot Integrity — recompute checksum on every read to detect tampering or corruption
        String recomputed = sha256(loaded.state());
        if (!recomputed.equals(loaded.checksum())) {
            log.warn("[SECURITY] Snapshot checksum mismatch for aggregate {} at version {} — discarding corrupted snapshot",
                    aggregateId, loaded.version());
            return Optional.empty();
        }

        return Optional.of(loaded);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
