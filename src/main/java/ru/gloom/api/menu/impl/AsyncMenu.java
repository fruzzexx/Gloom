package ru.gloom.api.menu.impl;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.gloom.GloomAI;

import java.util.concurrent.CompletableFuture;

public abstract class AsyncMenu extends AbstractMenu {
    public void show(@NotNull Player player) {
        opennerPlayer = player;
        menuInventory = createInventory();

        async(() -> {
            this.populateInventory();
            Bukkit.getScheduler().runTaskLater(GloomAI.INSTANCE, () -> player.openInventory(menuInventory), 1L);
        });
    }

    protected void populateInventory() {
        menuInventory.clear();
        menuButtons.clear();
        menuItems.clear();

        getItemsAndButtons();
        sync(this::setInventoryItems);
    }

    private void async(Runnable runnable) {
        CompletableFuture.runAsync(runnable);
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(GloomAI.INSTANCE, runnable);
    }
}