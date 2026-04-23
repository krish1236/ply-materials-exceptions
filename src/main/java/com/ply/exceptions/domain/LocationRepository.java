package com.ply.exceptions.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class LocationRepository {

    private static final RowMapper<Location> MAPPER = (rs, i) -> new Location(
            rs.getString("id"),
            rs.getString("kind"),
            rs.getString("label")
    );

    private final JdbcTemplate jdbc;

    public LocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Location> findAll() {
        return jdbc.query("SELECT id, kind, label FROM locations ORDER BY kind, id", MAPPER);
    }

    public Optional<Location> findById(String id) {
        return jdbc.query("SELECT id, kind, label FROM locations WHERE id = ?", MAPPER, id)
                .stream()
                .findFirst();
    }

    public void upsert(Location loc) {
        jdbc.update("""
                INSERT INTO locations (id, kind, label) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET kind = EXCLUDED.kind, label = EXCLUDED.label
                """, loc.id(), loc.kind(), loc.label());
    }
}
