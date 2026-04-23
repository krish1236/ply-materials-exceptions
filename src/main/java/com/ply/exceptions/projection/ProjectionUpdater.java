package com.ply.exceptions.projection;

import com.ply.exceptions.events.EventEnvelope;

public interface ProjectionUpdater {
    void apply(EventEnvelope envelope);
}
