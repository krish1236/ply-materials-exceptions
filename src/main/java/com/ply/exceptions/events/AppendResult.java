package com.ply.exceptions.events;

import java.util.UUID;

public record AppendResult(UUID id, boolean wasNew) {}
