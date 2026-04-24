package com.ply.exceptions.web;

import com.ply.exceptions.alerts.Alert;
import com.ply.exceptions.alerts.AlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/alerts")
public class AlertApiController {

    private final AlertService alerts;

    public AlertApiController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<Alert> list(@RequestParam(name = "status", defaultValue = "open") String status) {
        if ("all".equalsIgnoreCase(status)) {
            return alerts.findAll();
        }
        return alerts.findOpen();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Alert> get(@PathVariable UUID id) {
        return alerts.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable UUID id) {
        boolean ok = alerts.acknowledge(id);
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of("ok", false, "reason", "not in open state"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable UUID id) {
        boolean ok = alerts.resolve(id);
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of("ok", false, "reason", "not in resolvable state"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
