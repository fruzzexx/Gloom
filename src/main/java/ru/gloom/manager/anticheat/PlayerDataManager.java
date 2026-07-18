package ru.gloom.manager.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.player.User;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.gloom.GloomAI;
import ru.gloom.api.models.data.TrainData;
import ru.gloom.player.GloomPlayer;
import ru.gloom.utils.reflections.GeyserUtil;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {
    @Getter
    private final Collection<User> exemptUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<User, GloomPlayer> playerDataMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TrainData> trainDataMap = new ConcurrentHashMap<>();

    @Nullable
    public GloomPlayer getPlayer(@NotNull UUID uuid) {
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(uuid);
        User user = PacketEvents.getAPI().getProtocolManager().getUser(channel);

        return getPlayer(user);
    }

    public void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            addUser(user);
        }
    }

    @Nullable
    public GloomPlayer getPlayer(String username) {
        Player player = Bukkit.getPlayer(username);
        if (player == null) {
            return null;
        }

        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(player.getUniqueId());
        User user = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        return getPlayer(user);
    }

    @Nullable
    public GloomPlayer getPlayer(@NotNull User user) {
        return playerDataMap.get(user);
    }

    public TrainData getOrCreateTrainData(UUID uuid, String name) {
        return trainDataMap.computeIfAbsent(uuid, k -> new TrainData(uuid, name));
    }

    public boolean exemptCheck(@NotNull User user) {
        if (exemptUsers.contains(user)) {
            return true;
        }

        if (!ChannelHelper.isOpen(user.getChannel())) {
            return true;
        }

        if (GeyserUtil.isBedrockPlayer(user.getUUID())) {
            exemptUsers.add(user);
            return true;
        }

        if (user.getUUID().toString().startsWith("00000000-0000-0000-0009")) {
            exemptUsers.add(user);
            return true;
        }

        return false;
    }

    public void addUser(@NotNull User user) {
        if (exemptCheck(user)) {
            return;
        }

        GloomAI.INSTANCE.getPlayerOnlineService()
                .heartbeat(
                        user.getUUID(),
                        user.getName()
                );
        GloomAI.INSTANCE
                .getViolationManager()
                .getProbabilityStorage()
                .getOrCreatePlayerData(user.getUUID(), user.getName());

        GloomPlayer player = new GloomPlayer(user);
        playerDataMap.put(user, player);
    }

    public GloomPlayer remove(final @NotNull User user) {
        return playerDataMap.remove(user);
    }

    public void onDisconnect(User user) {
        GloomAI.INSTANCE.getPlayerOnlineService()
                .quit(user.getUUID());

        exemptUsers.remove(user);
        GloomPlayer player = remove(user);

        if (player != null) {
            GloomAI.INSTANCE.getAlertManager().setAlertsEnabled(player.getUuid(), false, true);
            GloomAI.INSTANCE.getAlertManager().setVerboseEnabled(player.getUuid(), false, true);
        }
    }

    public Collection<GloomPlayer> getEntries() {
        return playerDataMap.values();
    }

    public int size() {
        return playerDataMap.size();
    }
}