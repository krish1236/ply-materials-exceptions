package com.ply.exceptions.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterStore {

    private final JdbcTemplate jdbc;

    public DeadLetterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String source, String reason, String rawBody) {
        jdbc.update(
                "INSERT INTO dead_letters (source, reason, raw_body) VALUES (?, ?, ?)",
                source,
                reason,
                rawBody
        );
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letters", Long.class);
        return c == null ? 0L : c;
    }
}
