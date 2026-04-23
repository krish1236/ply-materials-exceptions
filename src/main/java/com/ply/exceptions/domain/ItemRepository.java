package com.ply.exceptions.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ItemRepository {

    private static final RowMapper<Item> MAPPER = (rs, i) -> new Item(
            rs.getString("sku"),
            rs.getString("name"),
            rs.getBigDecimal("unit_cost")
    );

    private final JdbcTemplate jdbc;

    public ItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Item> findAll() {
        return jdbc.query("SELECT sku, name, unit_cost FROM items ORDER BY sku", MAPPER);
    }

    public Optional<Item> findBySku(String sku) {
        return jdbc.query("SELECT sku, name, unit_cost FROM items WHERE sku = ?", MAPPER, sku)
                .stream()
                .findFirst();
    }

    public void upsert(Item item) {
        jdbc.update("""
                INSERT INTO items (sku, name, unit_cost) VALUES (?, ?, ?)
                ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, unit_cost = EXCLUDED.unit_cost
                """, item.sku(), item.name(), item.unitCost());
    }
}
