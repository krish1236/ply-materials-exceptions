package com.ply.exceptions.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class PurchaseOrderRepository {

    private final JdbcTemplate jdbc;

    public PurchaseOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PurchaseOrder> findAll() {
        return withLines(jdbc.query("""
                SELECT id, vendor, placed_at, eta, status, linked_job_id
                FROM purchase_orders
                ORDER BY placed_at DESC
                """, headerMapper()));
    }

    public Optional<PurchaseOrder> findById(String id) {
        List<PurchaseOrder> rows = jdbc.query("""
                SELECT id, vendor, placed_at, eta, status, linked_job_id
                FROM purchase_orders
                WHERE id = ?
                """, headerMapper(), id);
        return withLines(rows).stream().findFirst();
    }

    public List<PurchaseOrder> findByLinkedJobId(String jobId) {
        return withLines(jdbc.query("""
                SELECT id, vendor, placed_at, eta, status, linked_job_id
                FROM purchase_orders
                WHERE linked_job_id = ?
                ORDER BY eta
                """, headerMapper(), jobId));
    }

    private RowMapper<PurchaseOrder> headerMapper() {
        return (rs, i) -> new PurchaseOrder(
                rs.getString("id"),
                rs.getString("vendor"),
                rs.getTimestamp("placed_at").toInstant(),
                rs.getTimestamp("eta").toInstant(),
                rs.getString("status"),
                rs.getString("linked_job_id"),
                new ArrayList<>()
        );
    }

    private List<PurchaseOrder> withLines(List<PurchaseOrder> headers) {
        List<PurchaseOrder> result = new ArrayList<>(headers.size());
        for (PurchaseOrder h : headers) {
            List<PurchaseOrder.Line> lines = jdbc.query(
                    "SELECT sku, qty, unit_cost FROM purchase_order_lines WHERE po_id = ? ORDER BY sku",
                    (rs, i) -> new PurchaseOrder.Line(
                            rs.getString("sku"),
                            rs.getInt("qty"),
                            rs.getBigDecimal("unit_cost")),
                    h.id());
            result.add(new PurchaseOrder(
                    h.id(), h.vendor(), h.placedAt(), h.eta(),
                    h.status(), h.linkedJobId(), lines
            ));
        }
        return result;
    }
}
