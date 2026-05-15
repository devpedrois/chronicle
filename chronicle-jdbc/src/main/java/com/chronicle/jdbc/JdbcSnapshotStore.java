
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

    // [SECURITY] Checksum binds aggregateId + version + normalizedState so tampering any one field
    // invalidates the checksum. Formula: sha256(aggregateId::text || '|' || version::text || '|' || state::jsonb::text)
    // — computed by PostgreSQL to guarantee canonical JSONB normalization matches what state::text returns on read.
    // sha256(text::bytea) encodes as UTF-8, matching Java's sha256(str.getBytes(UTF_8)).
    // Binding aggregateId prevents cross-aggregate snapshot substitution.
    // Binding version prevents snapshot version-rollback attacks (injecting old/future version).
    // [SECURITY] Parameterized queries only — zero string concatenation in SQL
    private static final String UPSERT_SQL =
            "INSERT INTO snapshots (aggregate_id, aggregate_type, state, version, checksum, created_at) " +
            "VALUES (?, ?, ?::jsonb, ?, " +
            "  encode(sha256((CAST(? AS TEXT) || '|' || CAST(? AS TEXT) || '|' || (?::jsonb::text))::bytea), 'hex'), " +
            "  ?) " +
            "ON CONFLICT (aggregate_id) DO UPDATE SET " +
            "  aggregate_type = EXCLUDED.aggregate_type, " +
            "  state = EXCLUDED.state, " +
            "  version = EXCLUDED.version, " +
            "  checksum = encode(sha256((CAST(EXCLUDED.aggregate_id AS TEXT) || '|' || " +
            "            CAST(EXCLUDED.version AS TEXT) || '|' || (EXCLUDED.state::text))::bytea), 'hex'), " +
            "  created_at = EXCLUDED.created_at";

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

        // [SECURITY] Checksum bound to aggregateId + version + normalizedState — all three passed
        // as separate parameters so PostgreSQL computes sha256(id|ver|state) inline.
        // aggregateId and version passed again (5th and 6th ?) for the sha256 expression.
        // state passed again (7th ?) so PostgreSQL normalizes via ::jsonb::text before hashing.
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(UPSERT_SQL,
                        snapshot.aggregateId(),             // 1: aggregate_id column
                        snapshot.aggregateType(),           // 2: aggregate_type column
                        jsonbState,                         // 3: state column (jsonb)
                        snapshot.version(),                 // 4: version column
                        snapshot.aggregateId().toString(),  // 5: aggregateId for sha256 (as text)
                        snapshot.version(),                 // 6: version for sha256 (as int → cast to text)
                        snapshot.state(),                   // 7: state for sha256 (normalized via ::jsonb::text)
                        Timestamp.from(snapshot.timestamp()) // 8: created_at
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

        // [SECURITY] Snapshot Integrity — recompute checksum on every read using same formula as save:
        // sha256(aggregateId + "|" + version + "|" + normalizedState).
        // Binding aggregateId detects cross-aggregate injection.
        // Binding version detects version-rollback attacks.
        // Binding normalizedState detects state tampering.
        String checksumInput = loaded.aggregateId() + "|" + loaded.version() + "|" + loaded.state();
        String recomputed = sha256(checksumInput);
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
