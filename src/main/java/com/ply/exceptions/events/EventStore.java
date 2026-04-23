package com.ply.exceptions.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Component
public class EventStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public AppendResult append(EventEnvelope env) {
        String insert = """
                INSERT INTO events (source, external_id, type, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (source, external_id) DO NOTHING
                RETURNING id
                """;
        String payloadJson = serialize(env.payload());
        List<UUID> inserted = jdbc.query(
                insert,
                (rs, i) -> (UUID) rs.getObject("id"),
                env.source(),
                env.externalId(),
                env.type().name(),
                Timestamp.from(env.occurredAt()),
                payloadJson
        );
        if (!inserted.isEmpty()) {
            return new AppendResult(inserted.get(0), true);
        }
        UUID existing = jdbc.queryForObject(
                "SELECT id FROM events WHERE source = ? AND external_id = ?",
                UUID.class,
                env.source(),
                env.externalId()
        );
        return new AppendResult(existing, false);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        return c == null ? 0L : c;
    }

    private String serialize(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize event payload", e);
        }
    }
}
