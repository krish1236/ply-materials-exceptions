package com.ply.exceptions.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.alerts.AlertService;
import com.ply.exceptions.rules.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestBase.class)
class AlertApiControllerTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired AlertService service;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM alerts");
    }

    @Test
    void get_alerts_returns_open_alerts_only_by_default() {
        service.processEvaluation(List.of(
                candidate("A", "high", "k:1"),
                candidate("B", "low", "k:2")
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = rest.getForObject(url("/api/alerts"), List.class);

        assertThat(body).hasSize(2);
    }

    @Test
    void acknowledge_transitions_and_returns_ok() {
        service.processEvaluation(List.of(candidate("C", "medium", "k:ack")));
        var id = service.findByDedupeKey("k:ack").orElseThrow().id();

        var response = rest.postForEntity(url("/api/alerts/" + id + "/acknowledge"), null, Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(service.findById(id).orElseThrow().status()).isEqualTo("acknowledged");
    }

    @Test
    void resolve_transitions_and_returns_ok() {
        service.processEvaluation(List.of(candidate("D", "medium", "k:res")));
        var id = service.findByDedupeKey("k:res").orElseThrow().id();

        var response = rest.postForEntity(url("/api/alerts/" + id + "/resolve"), null, Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(service.findById(id).orElseThrow().status()).isEqualTo("resolved");
    }

    @Test
    void acknowledge_returns_409_if_already_resolved() {
        service.processEvaluation(List.of(candidate("E", "medium", "k:done")));
        var a = service.findByDedupeKey("k:done").orElseThrow();
        service.resolve(a.id());

        var response = rest.postForEntity(url("/api/alerts/" + a.id() + "/acknowledge"), null, Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Candidate candidate(String type, String severity, String dedupeKey) {
        var evidence = mapper.createObjectNode().put("k", "v");
        return new Candidate(type, severity, "cause", "action", "summary",
                "job_1", "SKU", "loc", evidence, dedupeKey);
    }
}
