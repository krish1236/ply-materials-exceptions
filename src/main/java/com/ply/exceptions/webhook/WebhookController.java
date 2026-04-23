package com.ply.exceptions.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventStore;
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

    private final HmacVerifier verifier;
    private final WebhookProperties props;
    private final EventStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WebhookController(HmacVerifier verifier,
                             WebhookProperties props,
                             EventStore store,
                             ObjectMapper mapper,
                             Clock clock) {
        this.verifier = verifier;
        this.props = props;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/{source}")
    public ResponseEntity<Void> ingest(
            @PathVariable String source,
            @RequestHeader(name = "X-Ply-Signature", required = false) String signature,
            @RequestBody String body) {

        long now = clock.instant().getEpochSecond();
        HmacVerifier.Outcome outcome = verifier.verify(props.signingSecret(), body, signature, now);
        if (outcome != HmacVerifier.Outcome.VALID) {
            return ResponseEntity.status(401).build();
        }

        WebhookRequest req;
        try {
            req = mapper.readValue(body, WebhookRequest.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (req.externalId() == null || req.type() == null || req.occurredAt() == null || req.payload() == null) {
            return ResponseEntity.badRequest().build();
        }

        EventEnvelope envelope = new EventEnvelope(
                source,
                req.externalId(),
                req.type(),
                req.occurredAt(),
                req.payload()
        );
        store.append(envelope);
        return ResponseEntity.ok().build();
    }
}
