package ru.gloom.command.commands.gloom.subcommands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.gloom.GloomAI;
import ru.gloom.api.command.BuildableCommand;
import ru.gloom.api.command.register.SubCommandRegister;

import java.util.List;

@SubCommandRegister(permission = "gloom.command.holograms", aliases = {"hologram", "holo"})
public class HologramsSubCommand implements BuildableCommand {

    @Override
    public void handle(@NotNull CommandSender commandSender, @NotNull String[] args) {
        if (!(commandSender instanceof Player player)) {
            return;
        }

        GloomAI.INSTANCE.getHologramManager().toggleHolograms(player.getUniqueId(), false);
    }

    @Override
    public List<String> tabComplete(@NotNull CommandSender commandSender, @NotNull String[] args) {
        return List.of();
    }
}