package com.chronicle.jdbc;

import com.chronicle.core.projection.ProjectionPosition;
import com.chronicle.core.projection.ProjectionPositionStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link ProjectionPositionStore}.
 * UPSERT semantics via ON CONFLICT DO UPDATE.
 */
public final class JdbcProjectionPositionStore implements ProjectionPositionStore {

    // [SECURITY] Parameterized queries only — zero string concatenation in SQL
    private static final String UPSERT_SQL =
            "INSERT INTO projection_positions (projection_name, last_event_id, last_version, updated_at) " +
            "VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (projection_name) DO UPDATE SET " +
            "last_event_id = EXCLUDED.last_event_id, " +
            "last_version = EXCLUDED.last_version, " +
            "updated_at = EXCLUDED.updated_at";

    private static final String SELECT_SQL =
            "SELECT projection_name, last_event_id, last_version, updated_at " +
            "FROM projection_positions WHERE projection_name = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcProjectionPositionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public Optional<ProjectionPosition> getPosition(String projectionName) {
        Objects.requireNonNull(projectionName, "projectionName must not be null");
        List<ProjectionPosition> results = jdbcTemplate.query(SELECT_SQL, (rs, rowNum) ->
                new ProjectionPosition(
                        rs.getString("projection_name"),
                        UUID.fromString(rs.getString("last_event_id")),
                        rs.getLong("last_version"),
                        rs.getTimestamp("updated_at").toInstant()
                ), projectionName);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void savePosition(ProjectionPosition position) {
        Objects.requireNonNull(position, "position must not be null");
        jdbcTemplate.update(UPSERT_SQL,
                position.projectionName(),
                position.lastEventId(),
                position.lastVersion(),
                Timestamp.from(position.updatedAt())
        );
    }
}
