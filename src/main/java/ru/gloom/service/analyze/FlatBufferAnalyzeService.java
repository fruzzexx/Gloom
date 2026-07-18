package ru.gloom.service.analyze;

import com.google.flatbuffers.FlatBufferBuilder;
import lombok.RequiredArgsConstructor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import ru.gloom.GloomAI;
import ru.gloom.api.models.RotationFrame;
import ru.gloom.api.models.analyze.AnalyzeService;
import ru.gloom.config.anticheat.ChecksConfigManager;
import ru.gloom.player.GloomPlayer;
import ru.gloom.protocol.flatbuffers.AnalyzeRequest;
import ru.gloom.protocol.flatbuffers.AnalyzeResponse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

@RequiredArgsConstructor
public final class FlatBufferAnalyzeService implements AnalyzeService {
    private static final MediaType FLATBUFFERS_TYPE =
            MediaType.parse("application/x-flatbuffers");

    private static final int FEATURES_PER_FRAME = 8;

    private final Plugin plugin;
    private final ChecksConfigManager configManager;

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
            payload = encodeRequest(bukkitPlayer.getName(), frames);
        } catch (RuntimeException exception) {
            Bukkit.getLogger().warning(
                    "[GloomAI] FlatBuffers request build failed: " + exception.getMessage()
            );
            return;
        }

        sendRequest(payload, gloomPlayer);
    }

    private byte[] encodeRequest(String playerName, List<RotationFrame> frames) {
        float[] features = flattenFeatures(frames);

        int initialCapacity = 128 + features.length * Float.BYTES;
        FlatBufferBuilder builder = new FlatBufferBuilder(initialCapacity);

        int nameOffset = builder.createString(playerName);
        int featuresOffset = AnalyzeRequest.createFeaturesVector(builder, features);

        int requestOffset = AnalyzeRequest.createAnalyzeRequest(
                builder,
                nameOffset,
                frames.size(),
                featuresOffset
        );

        AnalyzeRequest.finishAnalyzeRequestBuffer(builder, requestOffset);
        return builder.sizedByteArray();
    }

    private float[] flattenFeatures(List<RotationFrame> frames) {
        float[] features = new float[frames.size() * FEATURES_PER_FRAME];

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            RotationFrame frame = frames.get(frameIndex);
            int base = frameIndex * FEATURES_PER_FRAME;

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

    private void sendRequest(byte[] payload, GloomPlayer gloomPlayer) {
        RequestBody body = RequestBody.create(payload, FLATBUFFERS_TYPE);
        Request request = new Request.Builder()
                .url(configManager.getAnalyzeServer())
                .header("Accept", "application/x-flatbuffers")
                .post(body)
                .build();

        GloomAI.INSTANCE.getHttpClient()
                .newCall(request)
                .enqueue(new AnalyzeCallback(gloomPlayer));
    }

    private final class AnalyzeCallback implements Callback {
        private final GloomPlayer gloomPlayer;

        private AnalyzeCallback(GloomPlayer gloomPlayer) {
            this.gloomPlayer = gloomPlayer;
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException exception) {
            Bukkit.getLogger().warning(
                    "[GloomAI] Analyze request failed: " + exception.getMessage()
            );
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) {
            try (ResponseBody responseBody = response.body()) {
                if (!response.isSuccessful() || responseBody == null) {
                    Bukkit.getLogger().warning(
                            "[GloomAI] Analyze server returned HTTP " + response.code()
                    );
                    return;
                }

                double probability = decodeProbability(responseBody.bytes());
                if (!Double.isFinite(probability)) {
                    throw new IllegalArgumentException("probability is not finite");
                }

                double normalizedProbability = Math.max(0.0D, Math.min(1.0D, probability));
                Bukkit.getScheduler().runTask(plugin, () ->
                        GloomAI.INSTANCE.getAiResultManager().handleAnalyzeResult(
                                gloomPlayer,
                                normalizedProbability
                        )
                );
            } catch (Exception exception) {
                Bukkit.getLogger().warning(
                        "[GloomAI] FlatBuffers response parse failed: " + exception.getMessage()
                );
            }
        }
    }

    private double decodeProbability(byte[] responseBytes) {
        if (responseBytes.length < 8) {
            throw new IllegalArgumentException("response is too short");
        }

        ByteBuffer buffer = ByteBuffer.wrap(responseBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!AnalyzeResponse.AnalyzeResponseBufferHasIdentifier(buffer)) {
            throw new IllegalArgumentException("invalid response identifier; expected GAIR");
        }

        return AnalyzeResponse.getRootAsAnalyzeResponse(buffer).probability();
    }
}