package ru.gloom.service.analyze;

import com.google.flatbuffers.FlatBufferBuilder;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.gloom.api.models.RotationFrame;
import ru.gloom.api.models.analyze.AnalyzeService;
import ru.gloom.config.anticheat.ChecksConfigManager;
import ru.gloom.player.GloomPlayer;
import ru.gloom.protocol.flatbuffers.AnalyzeRequest;

import java.util.List;

@RequiredArgsConstructor
public final class FlatBufferAnalyzeService implements AnalyzeService {

    private final ChecksConfigManager configManager;
    private final AnalyzeBatchDispatcher dispatcher;

    @Override
    public void analyzePlayerFrames(GloomPlayer gloomPlayer) {
        if (!gloomPlayer.getRotationBuffer().isFull()) {
            return;
        }

        Player bukkitPlayer = gloomPlayer.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return;
        }

        List<RotationFrame> frames = gloomPlayer.getRotationBuffer()
                .pollSnapshot(configManager.getAnalysisStep());

        if (frames == null || frames.isEmpty()) {
            return;
        }

        final byte[] payload;
        try {
            payload = encodeRequest(frames);
        } catch (RuntimeException exception) {
            Bukkit.getLogger().warning(
                    "[GloomAI] FlatBuffers request build failed: " + exception.getMessage()
            );
            return;
        }

        dispatcher.enqueue(payload, gloomPlayer);
    }

    private byte[] encodeRequest(List<RotationFrame> frames) {
        float[] features = flattenFeatures(frames);

        int initialCapacity = 64 + features.length * Float.BYTES;
        FlatBufferBuilder builder = new FlatBufferBuilder(initialCapacity);

        int featuresOffset = AnalyzeRequest.createFeaturesVector(builder, features);

        int requestOffset = AnalyzeRequest.createAnalyzeRequest(
                builder,
                0,
                frames.size(),
                featuresOffset
        );

        AnalyzeRequest.finishAnalyzeRequestBuffer(builder, requestOffset);
        return builder.sizedByteArray();
    }

    private float[] flattenFeatures(List<RotationFrame> frames) {
        float[] features = new float[frames.size() * 8];

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            RotationFrame frame = frames.get(frameIndex);
            int base = frameIndex * 8;

            features[base] = frame.getDeltaYaw();
            features[base + 1] = frame.getDeltaPitch();
            features[base + 2] = frame.getAccelYaw();
            features[base + 3] = frame.getAccelPitch();
            features[base + 4] = frame.getJerkYaw();
            features[base + 5] = frame.getJerkPitch();
            features[base + 6] = frame.getGcdErrorYaw();
            features[base + 7] = frame.getGcdErrorPitch();
        }

        return features;
    }
}
