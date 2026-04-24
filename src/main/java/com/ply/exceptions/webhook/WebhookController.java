package com.ply.exceptions.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventStore;
import com.ply.exceptions.resilience.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final HmacVerifier verifier;
    private final WebhookProperties props;
    private final EventStore store;
    private final DeadLetterStore dlq;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CircuitBreaker circuit;

    public WebhookController(HmacVerifier verifier,
                             WebhookProperties props,
                             EventStore store,
                             DeadLetterStore dlq,
                             ObjectMapper mapper,
                             Clock clock,
                             CircuitBreaker circuit) {
        this.verifier = verifier;
        this.props = props;
        this.store = store;
        this.dlq = dlq;
        this.mapper = mapper;
        this.clock = clock;
        this.circuit = circuit;
    }

    @PostMapping("/{source}")
    public ResponseEntity<Void> ingest(
            @PathVariable String source,
            @RequestHeader(name = "X-Ply-Signature", required = false) String signature,
            @RequestBody String body) {

        if (circuit.isOpen(source)) {
            return ResponseEntity.status(503).build();
        }

        long now = clock.instant().getEpochSecond();
        HmacVerifier.Outcome outcome = verifier.verify(props.signingSecret(), body, signature, now);
        if (outcome != HmacVerifier.Outcome.VALID) {
            return ResponseEntity.status(401).build();
        }

        WebhookRequest req;
        try {
            req = mapper.readValue(body, WebhookRequest.class);
        } catch (Exception e) {
            dlq.write(source, "malformed json: " + e.getClass().getSimpleName(), body);
            return ResponseEntity.badRequest().build();
        }
        if (req.externalId() == null || req.type() == null || req.occurredAt() == null || req.payload() == null) {
            dlq.write(source, "missing required field", body);
            return ResponseEntity.badRequest().build();
        }

        EventEnvelope envelope = new EventEnvelope(
                source,
                req.externalId(),
                req.type(),
                req.occurredAt(),
                req.payload()
        );
        try {
            store.append(envelope);
            circuit.recordSuccess(source);
        } catch (DataAccessException e) {
            circuit.recordFailure(source);
            log.error("event store append failed for source={}", source, e);
            return ResponseEntity.status(500).build();
        }
        return ResponseEntity.ok().build();
    }
}
