package com.ply.exceptions.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.events.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestBase.class)
class WebhookControllerTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired EventStore store;
    @Autowired DeadLetterStore dlq;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebhookProperties props;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM dead_letters");
    }

    @Test
    void valid_signed_payload_is_accepted_and_stored() {
        String body = validBody("evt_100");
        long t = Instant.now().getEpochSecond();

        ResponseEntity<Void> r = post("fsm", body, signed(body, t), t);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(store.count()).isEqualTo(1);
        assertThat(dlq.count()).isEqualTo(0);
    }

    @Test
    void duplicate_delivery_is_200_but_stores_one_row() {
        String body = validBody("evt_200");
        long t = Instant.now().getEpochSecond();
        String sig = signed(body, t);

        post("fsm", body, sig, t);
        ResponseEntity<Void> second = post("fsm", body, sig, t);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void bad_signature_returns_401() {
        String body = validBody("evt_300");
        long t = Instant.now().getEpochSecond();
        String header = "t=" + t + ", v1=deadbeefdeadbeefdeadbeefdeadbeef";

        ResponseEntity<Void> r = post("fsm", body, header, t);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(store.count()).isEqualTo(0);
    }

    @Test
    void expired_timestamp_returns_401() {
        String body = validBody("evt_400");
        long t = Instant.now().getEpochSecond() - props.maxClockSkewSeconds() - 60;

        ResponseEntity<Void> r = post("fsm", body, signed(body, t), t);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(store.count()).isEqualTo(0);
    }

    @Test
    void malformed_json_returns_400_and_writes_dlq() {
        String body = "{this-is-not-json";
        long t = Instant.now().getEpochSecond();

        ResponseEntity<Void> r = post("fsm", body, signed(body, t), t);

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(store.count()).isEqualTo(0);
        assertThat(dlq.count()).isEqualTo(1);
    }

    @Test
    void unknown_event_type_returns_400_and_writes_dlq() {
        String body = """
                {
                  "external_id": "evt_500",
                  "type": "DOES_NOT_EXIST",
                  "occurred_at": "2026-04-22T14:33:00Z",
                  "payload": {}
                }
                """;
        long t = Instant.now().getEpochSecond();

        ResponseEntity<Void> r = post("fsm", body, signed(body, t), t);

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(dlq.count()).isEqualTo(1);
    }

    private String validBody(String externalId) {
        return """
                {
                  "external_id": "%s",
                  "type": "STOCK_SCAN",
                  "occurred_at": "2026-04-22T14:33:00Z",
                  "payload": {"sku": "BRK-20A", "location": "truck_8", "qty": 2}
                }
                """.formatted(externalId);
    }

    private String signed(String body, long t) {
        String mac = HmacVerifier.hmacHex(props.signingSecret(), t + "." + body);
        return "t=" + t + ", v1=" + mac;
    }

    private ResponseEntity<Void> post(String source, String body, String sig, long t) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (sig != null) h.set("X-Ply-Signature", sig);
        return rest.exchange(
                "http://localhost:" + port + "/webhooks/" + source,
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                Void.class
        );
    }
}
