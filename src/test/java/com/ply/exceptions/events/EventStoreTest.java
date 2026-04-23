package com.ply.exceptions.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class EventStoreTest {

    @Autowired
    EventStore store;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM events");
    }

    @Test
    void first_append_inserts_and_returns_new() {
        EventEnvelope env = sample("evt_001");

        AppendResult result = store.append(env);

        assertThat(result.wasNew()).isTrue();
        assertThat(result.id()).isNotNull();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void duplicate_append_is_noop_and_returns_existing_id() {
        EventEnvelope env = sample("evt_002");
        AppendResult first = store.append(env);

        AppendResult second = store.append(env);

        assertThat(second.wasNew()).isFalse();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void ten_identical_appends_produce_one_row() {
        EventEnvelope env = sample("evt_003");

        for (int i = 0; i < 10; i++) {
            store.append(env);
        }

        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void different_external_ids_produce_distinct_rows() {
        store.append(sample("evt_100"));
        store.append(sample("evt_101"));
        store.append(sample("evt_102"));

        assertThat(store.count()).isEqualTo(3);
    }

    private EventEnvelope sample(String externalId) {
        return new EventEnvelope(
                "fsm",
                externalId,
                EventType.STOCK_SCAN,
                Instant.parse("2026-04-22T14:33:00Z"),
                mapper.createObjectNode()
                        .put("sku", "BRK-20A")
                        .put("location", "truck_8")
                        .put("qty", 2)
        );
    }
}
