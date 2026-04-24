package com.ply.exceptions.web;

import com.ply.exceptions.resilience.CircuitBreaker;
import com.ply.exceptions.resilience.ReplayService;
import com.ply.exceptions.webhook.DeadLetterStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ReplayService replay;
    private final CircuitBreaker circuit;
    private final DeadLetterStore dlq;
    private final JdbcTemplate jdbc;

    public AdminController(ReplayService replay,
                           CircuitBreaker circuit,
                           DeadLetterStore dlq,
                           JdbcTemplate jdbc) {
        this.replay = replay;
        this.circuit = circuit;
        this.dlq = dlq;
        this.jdbc = jdbc;
    }

    @PostMapping("/replay")
    public ReplayService.ReplayStats runReplay() {
        return replay.replay();
    }

    @GetMapping("/circuit")
    public List<CircuitBreaker.CircuitState> circuitStates() {
        return circuit.allStates();
    }

    @GetMapping("/dlq")
    public List<Map<String, Object>> deadLetters() {
        return jdbc.query(
                "SELECT id, source, reason, raw_body, received_at FROM dead_letters"
                        + " ORDER BY received_at DESC LIMIT 100",
                (rs, i) -> Map.of(
                        "id", rs.getObject("id").toString(),
                        "source", rs.getString("source"),
                        "reason", rs.getString("reason"),
                        "raw_body", rs.getString("raw_body"),
                        "received_at", ((java.sql.Timestamp) rs.getObject("received_at")).toInstant().toString()
                ));
    }

    @GetMapping("/dlq/count")
    public Map<String, Long> dlqCount() {
        return Map.of("count", dlq.count(), "now", Instant.now().getEpochSecond());
    }
}
