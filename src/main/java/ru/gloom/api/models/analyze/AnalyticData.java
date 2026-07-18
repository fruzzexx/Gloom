package ru.gloom.api.models.analyze;

import ru.gloom.api.models.RotationFrame;
import ru.gloom.player.GloomPlayer;

import java.util.List;

public record AnalyticData(String username, List<RotationFrame> rotationFrames) {

    public static AnalyticData createData(GloomPlayer gloomPlayer) {
        final List<RotationFrame> rotationFrames = gloomPlayer.getRotationBuffer().getSnapshot();

        gloomPlayer.setLastAnalyzedFrames(rotationFrames);

        return new AnalyticData(gloomPlayer.getName(), rotationFrames);
    }

}