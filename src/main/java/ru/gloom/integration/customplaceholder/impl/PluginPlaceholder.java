package ru.gloom.integration.customplaceholder.impl;

import org.bukkit.plugin.Plugin;
import ru.gloom.integration.customplaceholder.PlaceholderIntegration;
import ru.gloom.utils.StringColorize;

public class PluginPlaceholder implements PlaceholderIntegration {
    private Plugin plugin;

    @Override
    public void init(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPlaceholder(String path) {
        return StringColorize.parse(
                plugin.getConfig().getString("placeholder." + path)
        );
    }

    @Override
    public String getPlaceholder(String path, String def) {
        return StringColorize.parse(
                plugin.getConfig().getString("placeholder." + path, def)
        );
    }
}
