package com.ply.exceptions.resilience;

import com.ply.exceptions.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class CircuitBreakerTest {

    @Autowired CircuitBreaker breaker;
    @Autowired CircuitBreakerProperties props;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM circuit_state");
    }

    @Test
    void closed_by_default() {
        assertThat(breaker.isOpen("fsm")).isFalse();
    }

    @Test
    void opens_after_threshold_consecutive_failures() {
        for (int i = 0; i < props.failureThreshold(); i++) {
            breaker.recordFailure("fsm");
        }

        assertThat(breaker.isOpen("fsm")).isTrue();
    }

    @Test
    void single_failure_below_threshold_does_not_open() {
        breaker.recordFailure("fsm");

        assertThat(breaker.isOpen("fsm")).isFalse();
    }

    @Test
    void success_resets_failure_counter_and_closes_circuit() {
        for (int i = 0; i < props.failureThreshold(); i++) {
            breaker.recordFailure("fsm");
        }
        assertThat(breaker.isOpen("fsm")).isTrue();

        breaker.recordSuccess("fsm");

        assertThat(breaker.isOpen("fsm")).isFalse();
    }

    @Test
    void per_source_isolation() {
        for (int i = 0; i < props.failureThreshold(); i++) {
            breaker.recordFailure("fsm");
        }

        assertThat(breaker.isOpen("fsm")).isTrue();
        assertThat(breaker.isOpen("acc")).isFalse();
    }

    @Test
    void all_states_reflects_current_breaker_state() {
        for (int i = 0; i < props.failureThreshold(); i++) {
            breaker.recordFailure("fsm");
        }
        breaker.recordSuccess("acc");

        var states = breaker.allStates();

        assertThat(states).hasSize(2);
        CircuitBreaker.CircuitState fsm = states.stream()
                .filter(s -> s.source().equals("fsm")).findFirst().orElseThrow();
        CircuitBreaker.CircuitState acc = states.stream()
                .filter(s -> s.source().equals("acc")).findFirst().orElseThrow();
        assertThat(fsm.open()).isTrue();
        assertThat(fsm.consecutiveFailures()).isEqualTo(props.failureThreshold());
        assertThat(acc.open()).isFalse();
    }
}
