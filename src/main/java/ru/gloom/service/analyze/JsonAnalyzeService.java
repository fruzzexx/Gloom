package ru.gloom.service.analyze;

import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import ru.gloom.GloomAI;
import ru.gloom.api.models.RotationFrame;
import ru.gloom.api.models.analyze.AnalyzeService;
import ru.gloom.config.anticheat.ChecksConfigManager;
import ru.gloom.player.GloomPlayer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class JsonAnalyzeService implements AnalyzeService {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private final Plugin plugin;
    private final ChecksConfigManager configManager;

    @Override
    public void analyzePlayerFrames(GloomPlayer gloomPlayer) {
        List<RotationFrame> frames = gloomPlayer.getRotationBuffer()
                .pollSnapshot(configManager.getAnalysisStep());

        if (frames == null || frames.isEmpty()) {
            return;
        }

        String payload = GSON.toJson(Map.of(
                "name", gloomPlayer.getBukkitPlayer().getName(),
                "frames", frames
        ));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sendRequest(payload, gloomPlayer));
    }

    private void sendRequest(String payload, GloomPlayer gloomPlayer) {
        RequestBody body = RequestBody.create(payload, JSON_TYPE);
        Request request = new Request.Builder()
                .url(configManager.getAnalyzeServer())
                .post(body)
                .build();

        GloomAI.INSTANCE.getHttpClient().newCall(request)
                .enqueue(new AnalyzeCallback(gloomPlayer));
    }

    private final class AnalyzeCallback implements Callback {
        private final GloomPlayer gloomPlayer;

        private AnalyzeCallback(GloomPlayer gloomPlayer) {
            this.gloomPlayer = gloomPlayer;
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) {
            try (ResponseBody responseBody = response.body()) {
                if (!response.isSuccessful() || responseBody == null) {
                    return;
                }

                AnalyzeResponse result = GSON.fromJson(responseBody.string(), AnalyzeResponse.class);
                if (result == null) {
                    return;
                }

                Double prob = null;

                if (result.cheat_probability != null) {
                    prob = result.cheat_probability;
                } else if (result.probability != null) {
                    prob = result.probability;
                } else if (result.verdict != null && result.verdict.probability != null) {
                    prob = result.verdict.probability;
                }

                if (prob == null) {
                    return;
                }

                double finalProb = prob;
                Bukkit.getScheduler().runTask(plugin, () ->
                        GloomAI.INSTANCE.getAiResultManager().handleAnalyzeResult(gloomPlayer, finalProb)
                );
            } catch (Exception e) {
                Bukkit.getLogger().warning("[GloomAI] Analyze response parse failed: " + e.getMessage());
            }
        }
    }

    private static final class AnalyzeResponse {
        Double cheat_probability;
        Double probability;
        Verdict verdict;
    }

    private static final class Verdict {
        Double probability;
        Double threshold;
        Boolean suspicious;
    }
}