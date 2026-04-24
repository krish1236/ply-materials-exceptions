package com.ply.exceptions.resilience;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class CircuitBreaker {

    private final JdbcTemplate jdbc;
    private final CircuitBreakerProperties props;
    private final Clock clock;

    public CircuitBreaker(JdbcTemplate jdbc, CircuitBreakerProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public boolean isOpen(String source) {
        List<Instant> openedAts = jdbc.query(
                "SELECT opened_at FROM circuit_state WHERE source = ?",
                (rs, i) -> rs.getTimestamp("opened_at") == null ? null : rs.getTimestamp("opened_at").toInstant(),
                source);
        if (openedAts.isEmpty() || openedAts.get(0) == null) {
            return false;
        }
        Instant openedAt = openedAts.get(0);
        Instant cooldownEnd = openedAt.plus(Duration.ofSeconds(props.cooldownSeconds()));
        return clock.instant().isBefore(cooldownEnd);
    }

    public void recordSuccess(String source) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO circuit_state (source, consecutive_failures, opened_at, updated_at)
                VALUES (?, 0, NULL, ?)
                ON CONFLICT (source) DO UPDATE SET
                    consecutive_failures = 0,
                    opened_at = NULL,
                    updated_at = EXCLUDED.updated_at
                """, source, Timestamp.from(now));
    }

    public void recordFailure(String source) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO circuit_state (source, consecutive_failures, last_failure_at, updated_at)
                VALUES (?, 1, ?, ?)
                ON CONFLICT (source) DO UPDATE SET
                    consecutive_failures = circuit_state.consecutive_failures + 1,
                    last_failure_at = EXCLUDED.last_failure_at,
                    updated_at = EXCLUDED.updated_at,
                    opened_at = CASE
                        WHEN circuit_state.consecutive_failures + 1 >= ? THEN EXCLUDED.last_failure_at
                        ELSE circuit_state.opened_at
                    END
                """,
                source, Timestamp.from(now), Timestamp.from(now), props.failureThreshold());
    }

    public List<CircuitState> allStates() {
        return jdbc.query(
                "SELECT source, consecutive_failures, opened_at, last_failure_at, updated_at FROM circuit_state",
                (rs, i) -> new CircuitState(
                        rs.getString("source"),
                        rs.getInt("consecutive_failures"),
                        rs.getTimestamp("opened_at") == null ? null : rs.getTimestamp("opened_at").toInstant(),
                        rs.getTimestamp("last_failure_at") == null ? null : rs.getTimestamp("last_failure_at").toInstant(),
                        isOpen(rs.getString("source"))
                ));
    }

    public record CircuitState(
            String source,
            int consecutiveFailures,
            Instant openedAt,
            Instant lastFailureAt,
            boolean open
    ) {}

    @Configuration
    @EnableConfigurationProperties(CircuitBreakerProperties.class)
    public static class Config {}
}
