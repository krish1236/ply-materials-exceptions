package com.ply.exceptions.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class Projector {

    private static final Logger log = LoggerFactory.getLogger(Projector.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final List<ProjectionUpdater> updaters;

    public Projector(JdbcTemplate jdbc,
                     ObjectMapper mapper,
                     TransactionTemplate tx,
                     List<ProjectionUpdater> updaters) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = tx;
        this.updaters = updaters;
    }

    @Scheduled(fixedDelayString = "${ply.projector.poll-ms:500}")
    public synchronized void pump() {
        int applied;
        do {
            applied = pumpBatch();
        } while (applied == BATCH_SIZE);
    }

    public synchronized int pumpOnce() {
        int total = 0;
        int applied;
        do {
            applied = pumpBatch();
            total += applied;
        } while (applied == BATCH_SIZE);
        return total;
    }

    private int pumpBatch() {
        Cursor cursor = readCursor();
        List<EventRow> batch = readBatch(cursor);
        if (batch.isEmpty()) {
            return 0;
        }
        for (EventRow row : batch) {
            tx.execute(status -> {
                try {
                    EventEnvelope env = toEnvelope(row);
                    for (ProjectionUpdater u : updaters) {
                        u.apply(env);
                    }
                    advanceCursor(row.ingestedAt, row.id);
                } catch (Exception e) {
                    log.error("projector failed on event id={} type={}: {}",
                            row.id, row.type, e.getMessage());
                    advanceCursor(row.ingestedAt, row.id);
                }
                return null;
            });
        }
        return batch.size();
    }

    private Cursor readCursor() {
        return jdbc.queryForObject(
                "SELECT last_ingested_at, last_event_id FROM projector_offset WHERE name = 'stock'",
                (rs, i) -> new Cursor(
                        rs.getTimestamp("last_ingested_at").toInstant(),
                        (UUID) rs.getObject("last_event_id")
                ));
    }

    private List<EventRow> readBatch(Cursor cursor) {
        String sql = """
                SELECT id, source, external_id, type, occurred_at, ingested_at, payload::text AS payload_text
                FROM events
                WHERE (ingested_at, id) > (?, ?)
                ORDER BY ingested_at, id
                LIMIT ?
                """;
        RowMapper<EventRow> mapperFn = (rs, i) -> new EventRow(
                (UUID) rs.getObject("id"),
                rs.getString("source"),
                rs.getString("external_id"),
                rs.getString("type"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("ingested_at").toInstant(),
                rs.getString("payload_text")
        );
        return jdbc.query(sql, mapperFn,
                Timestamp.from(cursor.lastIngestedAt),
                cursor.lastEventId,
                BATCH_SIZE);
    }

    private void advanceCursor(Instant ingestedAt, UUID id) {
        jdbc.update(
                "UPDATE projector_offset SET last_ingested_at = ?, last_event_id = ? WHERE name = 'stock'",
                Timestamp.from(ingestedAt), id);
    }

    private EventEnvelope toEnvelope(EventRow row) throws Exception {
        JsonNode payload = mapper.readTree(row.payloadText);
        return new EventEnvelope(
                row.source,
                row.externalId,
                EventType.valueOf(row.type),
                row.occurredAt,
                payload
        );
    }

    private record Cursor(Instant lastIngestedAt, UUID lastEventId) {}

    private record EventRow(
            UUID id,
            String source,
            String externalId,
            String type,
            Instant occurredAt,
            Instant ingestedAt,
            String payloadText
    ) {}
}
