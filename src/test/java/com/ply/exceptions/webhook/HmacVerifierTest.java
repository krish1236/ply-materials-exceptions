package com.ply.exceptions.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacVerifierTest {

    private static final String SECRET = "shhh";
    private static final String BODY = "{\"sku\":\"BRK-20A\",\"qty\":1}";
    private static final long TIMESTAMP = 1_745_000_000L;
    private static final long WINDOW = 300L;

    private final HmacVerifier verifier = new HmacVerifier(WINDOW);

    @Test
    void valid_signature_returns_valid() {
        String sig = HmacVerifier.hmacHex(SECRET, TIMESTAMP + "." + BODY);
        String header = "t=" + TIMESTAMP + ", v1=" + sig;

        HmacVerifier.Outcome outcome = verifier.verify(SECRET, BODY, header, TIMESTAMP);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.VALID);
    }

    @Test
    void tampered_body_returns_bad_signature() {
        String sig = HmacVerifier.hmacHex(SECRET, TIMESTAMP + "." + BODY);
        String header = "t=" + TIMESTAMP + ", v1=" + sig;
        String tampered = BODY.replace("BRK-20A", "BRK-30A");

        HmacVerifier.Outcome outcome = verifier.verify(SECRET, tampered, header, TIMESTAMP);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.BAD_SIGNATURE);
    }

    @Test
    void wrong_secret_returns_bad_signature() {
        String sig = HmacVerifier.hmacHex("other-secret", TIMESTAMP + "." + BODY);
        String header = "t=" + TIMESTAMP + ", v1=" + sig;

        HmacVerifier.Outcome outcome = verifier.verify(SECRET, BODY, header, TIMESTAMP);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.BAD_SIGNATURE);
    }

    @Test
    void timestamp_outside_window_returns_out_of_window() {
        String sig = HmacVerifier.hmacHex(SECRET, TIMESTAMP + "." + BODY);
        String header = "t=" + TIMESTAMP + ", v1=" + sig;
        long farFuture = TIMESTAMP + WINDOW + 10;

        HmacVerifier.Outcome outcome = verifier.verify(SECRET, BODY, header, farFuture);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.TIMESTAMP_OUT_OF_WINDOW);
    }

    @Test
    void missing_header_returns_malformed() {
        HmacVerifier.Outcome outcome = verifier.verify(SECRET, BODY, null, TIMESTAMP);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.MALFORMED_HEADER);
    }

    @Test
    void missing_v1_part_returns_malformed() {
        String header = "t=" + TIMESTAMP;

        HmacVerifier.Outcome outcome = verifier.verify(SECRET, BODY, header, TIMESTAMP);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.MALFORMED_HEADER);
    }

    @Test
    void non_numeric_timestamp_returns_malformed() {
        String header = "t=not-a-number, v1=deadbeef";

        HmacVerifier.Outcome outcome = verifier.verify(SECRET, BODY, header, TIMESTAMP);

        assertThat(outcome).isEqualTo(HmacVerifier.Outcome.MALFORMED_HEADER);
    }
}
