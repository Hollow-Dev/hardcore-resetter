package com.hardcoreresetter;

import org.bukkit.plugin.java.JavaPlugin;

public final class HardcoreResetterPlugin extends JavaPlugin {
    private ResetManager resetManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        resetManager = new ResetManager(this);
        resetManager.startup();
        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        var command = getCommand("hardcorereset");
        if (command != null) {
            command.setExecutor(new ResetCommand(this));
        }
    }

    @Override
    public void onDisable() {
        if (resetManager != null) {
            resetManager.shutdown();
        }
    }

    ResetManager getResetManager() {
        return resetManager;
    }
}
