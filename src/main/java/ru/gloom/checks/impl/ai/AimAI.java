package ru.gloom.checks.impl.ai;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ru.gloom.GloomAI;
import ru.gloom.api.configuration.CustomConfig;
import ru.gloom.api.models.RotationFrame;
import ru.gloom.checks.Check;
import ru.gloom.checks.CheckData;
import ru.gloom.checks.type.PacketCheck;
import ru.gloom.player.GloomPlayer;
import ru.gloom.utils.math.MouseCalculator;

import java.util.Set;
import java.util.UUID;

@Setter
@Getter
@CheckData(name = "AimAI", configName = "ml_check")
public final class AimAI extends Check implements PacketCheck {
    private static final double CHEAT_PROBABILITY = 0.90D;
    private static final double LEGIT_PROBABILITY = 0.10D;

    private double buffer = 0.0D;
    private double bufferFlagThreshold = 50.0D;
    private double bufferResetOnFlag = 25.0D;
    private double bufferMultiplier = 100.0D;
    private double bufferDecrease = 0.25D;

    private float lastYaw = 0.0F;
    private float lastPitch = 0.0F;
    private float lastDeltaYaw = 0.0F;
    private float lastDeltaPitch = 0.0F;
    private float lastAccelYaw = 0.0F;
    private float lastAccelPitch = 0.0F;
    private float lastYawToTargetDiff = 0.0F;
    private float lastPitchToTargetDiff = 0.0F;

    private long rotationSampleIndex = 0L;
    private long lastAttackSampleIndex = -1L;

    private double lastProbability;

    public AimAI(GloomPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (event.getConnectionState() != ConnectionState.PLAY) {
            return;
        }

        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            return;
        }

        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        if (!flying.hasRotationChanged()) {
            return;
        }

        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return;
        }

        if (GloomAI.INSTANCE.getChecksConfigManager().isAimAiBypassedInRegion(bukkitPlayer)) {
            updateRotationState(flying);
            player.getRotationBuffer().clear();
            return;
        }

        Entity target = GloomAI.INSTANCE.getTargetEntityIndex().getByUniqueId(player.getLastDamagedEntity());
        if (target == null || !target.isValid()) {
            updateRotationState(flying);
            return;
        }

        float currentYaw = normalizeYaw(flying.getLocation().getYaw());
        float currentPitch = clampPitch(flying.getLocation().getPitch());

        float deltaYaw = getSignedAngleDelta(currentYaw, lastYaw);
        float deltaPitch = currentPitch - lastPitch;

        float accelYaw = MouseCalculator.calculateAcceleration(deltaYaw, lastDeltaYaw);
        float accelPitch = MouseCalculator.calculateAcceleration(deltaPitch, lastDeltaPitch);

        float jerkYaw = MouseCalculator.calculateJerk(accelYaw, lastAccelYaw);
        float jerkPitch = MouseCalculator.calculateJerk(accelPitch, lastAccelPitch);

        float gcdErrorYaw = MouseCalculator.calculateGCDError(deltaYaw, lastDeltaYaw);
        float gcdErrorPitch = MouseCalculator.calculateGCDError(deltaPitch, lastDeltaPitch);

        float yawToTargetDiff = calculateSignedYawToTargetDiff(bukkitPlayer, target);
        float pitchToTargetDiff = calculateSignedPitchToTargetDiff(bukkitPlayer, target);

        rotationSampleIndex++;

        RotationFrame rotationFrame = new RotationFrame(
                deltaYaw,
                deltaPitch,
                accelYaw,
                accelPitch,
                jerkYaw,
                jerkPitch,
                gcdErrorYaw,
                gcdErrorPitch
        );

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;
        lastAccelYaw = accelYaw;
        lastAccelPitch = accelPitch;
        lastYawToTargetDiff = yawToTargetDiff;
        lastPitchToTargetDiff = pitchToTargetDiff;

        player.writeFrame(rotationFrame);
    }

    public void handleAttack(UUID targetUuid) {
        player.setLastDamagedEntity(targetUuid);
        lastAttackSampleIndex = rotationSampleIndex;
    }

    public void handleAnalyzeResult(double chance) {
        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline() || !Double.isFinite(chance)) {
            return;
        }

        if (GloomAI.INSTANCE.getChecksConfigManager().isAimAiBypassedInRegion(bukkitPlayer)) {
            player.getRotationBuffer().clear();
            return;
        }

        lastProbability = Math.max(0.0D, Math.min(1.0D, chance));

        GloomAI.INSTANCE.getViolationManager().addAiProbability(
                bukkitPlayer.getUniqueId(),
                lastProbability,
                true
        );

        updateBuffer(lastProbability);
        GloomAI.INSTANCE.getViolationManager().updateAnalysisSnapshot(
                bukkitPlayer.getUniqueId(),
                lastProbability,
                buffer
        );
        sendAiVerbose();

        GloomAI.INSTANCE.getMonitorManager().publish(
                bukkitPlayer,
                lastProbability,
                buffer
        );

        if (buffer <= bufferFlagThreshold || !canFlag()) {
            return;
        }

        double bufferAtFlag = buffer;

        registerViolation();
        player.getPunishmentManager().handleViolation(
                this,
                "prob: %.4f, buffer: %.2f".formatted(lastProbability, bufferAtFlag),
                lastProbability
        );

        buffer = bufferResetOnFlag;
        GloomAI.INSTANCE.getViolationManager().updateAnalysisSnapshot(
                bukkitPlayer.getUniqueId(),
                lastProbability,
                buffer
        );
    }

    private void updateBuffer(double probability) {
        if (probability > CHEAT_PROBABILITY) {
            buffer += (probability - CHEAT_PROBABILITY) * bufferMultiplier;
        } else if (probability < LEGIT_PROBABILITY) {
            buffer = Math.max(0.0D, buffer - bufferDecrease);
        }
    }

    @Override
    public boolean alert() {
        if (!canAlert()) {
            return false;
        }

        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return false;
        }

        String probability = GloomAI.INSTANCE.getMainConfigManager().getChanceString(lastProbability);
        String verboseMessage = GloomAI.INSTANCE.getMainConfigManager().getAiVerboseMessage()
                .replace("{player}", bukkitPlayer.getName())
                .replace("{probability}", probability)
                .replace("{buffer}", "%.2f".formatted(buffer));

        String alertMessage = GloomAI.INSTANCE.getMainConfigManager().getAiAlertMessage()
                .replace("{player}", bukkitPlayer.getName())
                .replace("{vl}", String.valueOf((int) getViolations()))
                .replace("{probability}", probability)
                .replace("{buffer}", "%.2f".formatted(buffer));

        GloomAI.INSTANCE.getAlertManager().sendVerbose(verboseMessage);
        GloomAI.INSTANCE.getAlertManager().sendAlert(alertMessage);
        GloomAI.INSTANCE.getViolationManager().logAlert(
                this,
                "prob: %.4f, buffer: %.2f".formatted(lastProbability, buffer)
        );
        return true;
    }

    @Override
    public boolean alert(String verbose) {
        return alert();
    }

    private void sendAiVerbose() {
        Player bukkitPlayer = player.getBukkitPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return;
        }

        String probability = GloomAI.INSTANCE.getMainConfigManager().getChanceString(lastProbability);
        String verboseMessage = GloomAI.INSTANCE.getMainConfigManager().getAiVerboseMessage()
                .replace("{player}", bukkitPlayer.getName())
                .replace("{probability}", probability)
                .replace("{buffer}", "%.2f".formatted(buffer));

        GloomAI.INSTANCE.getAlertManager().sendVerbose(verboseMessage);
    }

    private void updateRotationState(WrapperPlayClientPlayerFlying flying) {
        float currentYaw = normalizeYaw(flying.getLocation().getYaw());
        float currentPitch = clampPitch(flying.getLocation().getPitch());

        float deltaYaw = getSignedAngleDelta(currentYaw, lastYaw);
        float deltaPitch = currentPitch - lastPitch;

        float accelYaw = MouseCalculator.calculateAcceleration(deltaYaw, lastDeltaYaw);
        float accelPitch = MouseCalculator.calculateAcceleration(deltaPitch, lastDeltaPitch);

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;
        lastAccelYaw = accelYaw;
        lastAccelPitch = accelPitch;
        lastYawToTargetDiff = 0.0F;
        lastPitchToTargetDiff = 0.0F;
    }

    private float calculateSignedYawToTargetDiff(Player player, Entity target) {
        Location eyeLocation = player.getEyeLocation();
        Location targetLocation = getTargetCenter(target);

        double diffX = targetLocation.getX() - eyeLocation.getX();
        double diffZ = targetLocation.getZ() - eyeLocation.getZ();

        float targetYaw = normalizeYaw((float) Math.toDegrees(Math.atan2(-diffX, diffZ)));
        float currentYaw = normalizeYaw(eyeLocation.getYaw());

        return getSignedAngleDelta(targetYaw, currentYaw);
    }

    private float calculateSignedPitchToTargetDiff(Player player, Entity target) {
        Location eyeLocation = player.getEyeLocation();
        Location targetLocation = getTargetCenter(target);

        double diffX = targetLocation.getX() - eyeLocation.getX();
        double diffY = targetLocation.getY() - eyeLocation.getY();
        double diffZ = targetLocation.getZ() - eyeLocation.getZ();
        double horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetPitch = clampPitch((float) -Math.toDegrees(Math.atan2(diffY, horizontalDistance)));
        float currentPitch = clampPitch(eyeLocation.getPitch());

        return targetPitch - currentPitch;
    }

    private Location getTargetCenter(Entity target) {
        return target.getLocation().clone().add(0.0D, target.getHeight() * 0.5D, 0.0D);
    }

    private float normalizeYaw(float yaw) {
        return MouseCalculator.normalizeAngle(yaw);
    }

    private float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private float getSignedAngleDelta(float current, float previous) {
        return MouseCalculator.normalizeAngle(current - previous);
    }

    public void onReload(CustomConfig config) {
        String path = getConfigName();

        this.bufferFlagThreshold = Math.max(0.0D, config.getDouble(path + ".buffer.flag", 50.0D));
        this.bufferResetOnFlag = Math.max(
                0.0D,
                Math.min(bufferFlagThreshold, config.getDouble(path + ".buffer.reset_on_flag", 25.0D))
        );
        this.bufferMultiplier = Math.max(0.0D, config.getDouble(path + ".buffer.multiplier", 100.0D));
        this.bufferDecrease = Math.max(0.0D, config.getDouble(path + ".buffer.decrease", 0.25D));
    }

}
