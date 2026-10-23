package com.hardcoreresetter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class ResetCommand implements CommandExecutor {
    private final HardcoreResetterPlugin plugin;

    ResetCommand(HardcoreResetterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hardcoreresetter.force")) {
            sender.sendMessage(Component.text("You can't reset the world.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text(plugin.getResetManager().statusText(), NamedTextColor.GOLD));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm")) {
            sender.sendMessage(Component.text(
                    "This deletes the world and starts a new one for everybody. Run /"
                            + label + " confirm",
                    NamedTextColor.GOLD
            ));
            return true;
        }
        Player subject = sender instanceof Player player ? player : null;
        ResetManager.StartResult result = plugin.getResetManager().begin(
                subject,
                sender.getName(),
                "Forced by " + sender.getName(),
                "Forced reset by " + sender.getName(),
                false
        );
        switch (result) {
            case STARTED -> sender.sendMessage(Component.text("Reset started.", NamedTextColor.GREEN));
            case ALREADY_RUNNING -> sender.sendMessage(Component.text("A reset is already running.", NamedTextColor.RED));
            case FAILED -> sender.sendMessage(Component.text("The reset could not start.", NamedTextColor.RED));
        }
        return true;
    }
}
