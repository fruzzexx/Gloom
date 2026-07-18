package ru.gloom.api.models.monitor;

public record AiSnapshot(
        double probability,
        double buffer,
        double trend
) {
}
