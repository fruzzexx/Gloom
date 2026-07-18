package ru.gloom.command.commands.gloom.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.gloom.GloomAI;
import ru.gloom.api.command.BuildableCommand;
import ru.gloom.api.command.register.SubCommandRegister;
import ru.gloom.database.model.ViolationRecord;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SubCommandRegister(permission = "gloom.command.history", aliases = "history")
public class HistorySubCommand implements BuildableCommand {

    @Override
    public void handle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            return;
        }

        String targetName = args[1];
        int page = getPage(args);

        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes());

        var violationStorage = GloomAI.INSTANCE.getViolationManager().getViolationStorage();
        var config = GloomAI.INSTANCE.getMainConfigManager();

        int limit = config.getHistoryEntryPerPage();

        violationStorage.getLogCount(uuid).thenAccept(count -> {
            int maxPages = (int) Math.ceil((double) count / limit);

            violationStorage.getViolations(uuid, page, limit).thenAccept(records -> {

                sender.sendMessage(config.getHistoryHeaderMessage()
                        .replace("{player}", targetName)
                        .replace("{page}", String.valueOf(page + 1))
                        .replace("{max_pages}", String.valueOf(Math.max(maxPages, 1)))
                );

                for (int i = records.size() - 1; i >= 0; i--) {
                    ViolationRecord record = records.get(i);

                    sender.sendMessage(config.getHistoryEntryMessage()
                            .replace("{check_name}", record.checkName())
                            .replace("{vl}", String.valueOf(record.vls()))
                            .replace("{verbose}", record.verbose())
                            .replace("{server}", record.server())
                            .replace("{time_ago}", formatTimeAgo(record.timestamp()))
                    );
                }
            });
        });
    }

    private int getPage(@NotNull String @NotNull [] args) {
        return args.length >= 3 ? parsePage(args[2]) : 0;
    }

    private int parsePage(String arg) {
        try {
            int page = Integer.parseInt(arg);
            return Math.max(page - 1, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatTimeAgo(long timestamp) {
        long duration = System.currentTimeMillis() - timestamp;

        long days = TimeUnit.MILLISECONDS.toDays(duration);
        duration -= TimeUnit.DAYS.toMillis(days);

        long hours = TimeUnit.MILLISECONDS.toHours(duration);
        duration -= TimeUnit.HOURS.toMillis(hours);

        long minutes = TimeUnit.MILLISECONDS.toMinutes(duration);
        duration -= TimeUnit.MINUTES.toMillis(minutes);

        long seconds = TimeUnit.MILLISECONDS.toSeconds(duration);

        StringBuilder sb = new StringBuilder();

        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }

        return List.of();
    }
}