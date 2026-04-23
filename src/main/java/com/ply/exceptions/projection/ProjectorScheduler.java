package com.ply.exceptions.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ply.projector.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ProjectorScheduler {

    private final Projector projector;

    public ProjectorScheduler(Projector projector) {
        this.projector = projector;
    }

    @Scheduled(fixedDelayString = "${ply.projector.poll-ms:500}")
    public void tick() {
        projector.pump();
    }
}
