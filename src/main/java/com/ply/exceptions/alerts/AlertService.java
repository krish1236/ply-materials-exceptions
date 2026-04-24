package com.ply.exceptions.alerts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.rules.Candidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AlertService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AlertService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void processEvaluation(List<Candidate> candidates) {
        Instant runStart = clock.instant();
        for (Candidate c : candidates) {
            upsert(c, runStart);
        }
        autoResolveStale(runStart);
    }

    public void upsert(Candidate c, Instant seenAt) {
        String evidenceJson = serialize(c.evidence());
        jdbc.update("""
                INSERT INTO alerts (
                    type, severity, probable_cause, suggested_action, summary,
                    affected_job_id, affected_sku, affected_location,
                    evidence, status, created_at, updated_at, last_seen_at, dedupe_key
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'open', ?, ?, ?, ?)
                ON CONFLICT (dedupe_key) DO UPDATE SET
                    severity = EXCLUDED.severity,
                    probable_cause = EXCLUDED.probable_cause,
                    suggested_action = EXCLUDED.suggested_action,
                    summary = EXCLUDED.summary,
                    evidence = EXCLUDED.evidence,
                    updated_at = EXCLUDED.updated_at,
                    last_seen_at = EXCLUDED.last_seen_at,
                    status = CASE
                        WHEN alerts.status = 'auto_resolved' THEN 'open'
                        ELSE alerts.status
                    END,
                    resolved_at = CASE
                        WHEN alerts.status = 'auto_resolved' THEN NULL
                        ELSE alerts.resolved_at
                    END
                """,
                c.type(), c.severity(), c.probableCause(), c.suggestedAction(), c.summary(),
                c.jobId(), c.sku(), c.locationId(),
                evidenceJson,
                Timestamp.from(seenAt), Timestamp.from(seenAt), Timestamp.from(seenAt),
                c.dedupeKey());
    }

    public int autoResolveStale(Instant runStart) {
        return jdbc.update("""
                UPDATE alerts
                SET status = 'auto_resolved',
                    resolved_at = ?,
                    updated_at = ?
                WHERE status IN ('open', 'acknowledged')
                  AND last_seen_at < ?
                """,
                Timestamp.from(runStart),
                Timestamp.from(runStart),
                Timestamp.from(runStart));
    }

    public boolean acknowledge(UUID id) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE alerts SET status = 'acknowledged', updated_at = ?
                WHERE id = ? AND status = 'open'
                """,
                Timestamp.from(now), id) > 0;
    }

    public boolean resolve(UUID id) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE alerts SET status = 'resolved', resolved_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('open', 'acknowledged')
                """,
                Timestamp.from(now), Timestamp.from(now), id) > 0;
    }

    public List<Alert> findOpen() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM alerts"
                        + " WHERE status IN ('open', 'acknowledged')"
                        + " ORDER BY CASE severity WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END,"
                        + "          created_at DESC",
                rowMapper());
    }

    public List<Alert> findByStatus(String status) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM alerts WHERE status = ?"
                        + " ORDER BY CASE severity WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END,"
                        + "          updated_at DESC",
                rowMapper(), status);
    }

    public List<Alert> findAll() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM alerts ORDER BY created_at DESC",
                rowMapper());
    }

    public Optional<Alert> findById(UUID id) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM alerts WHERE id = ?",
                rowMapper(), id).stream().findFirst();
    }

    public Optional<Alert> findByDedupeKey(String dedupeKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM alerts WHERE dedupe_key = ?",
                rowMapper(), dedupeKey).stream().findFirst();
    }

    private static final String COLUMNS =
            "id, type, severity, probable_cause, suggested_action, summary, "
                    + "affected_job_id, affected_sku, affected_location, evidence::text AS evidence_text, "
                    + "status, created_at, updated_at, last_seen_at, resolved_at, dedupe_key";

    private RowMapper<Alert> rowMapper() {
        return (rs, i) -> new Alert(
                (UUID) rs.getObject("id"),
                rs.getString("type"),
                rs.getString("severity"),
                rs.getString("probable_cause"),
                rs.getString("suggested_action"),
                rs.getString("summary"),
                rs.getString("affected_job_id"),
                rs.getString("affected_sku"),
                rs.getString("affected_location"),
                parseJson(rs.getString("evidence_text")),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("dedupe_key")
        );
    }

    private String serialize(JsonNode node) {
        try {
            return node == null ? "{}" : mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize evidence", e);
        }
    }

    private JsonNode parseJson(String s) {
        try {
            return mapper.readTree(s == null ? "{}" : s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot parse evidence json", e);
        }
    }
}
