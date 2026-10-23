package com.hardcoreresetter;

import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerPortalEvent;

final class DeathListener implements Listener {
    private final HardcoreResetterPlugin plugin;

    DeathListener(HardcoreResetterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * A real hardcore death bans that player and drops their connection.
     * Cancel the killing blow instead, so the reset runs while they are still online.
     * A totem in either hand is left alone, because that hit would not have killed them.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalHit(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ResetManager manager = plugin.getResetManager();
        if (manager.isResetting() || manager.savedByTotem(player) || player.isDead() || event.getFinalDamage() <= 0) {
            return;
        }
        if (!manager.triggers(player.getGameMode())) {
            return;
        }
        if (player.getHealth() + player.getAbsorptionAmount() - event.getFinalDamage() > 0.0) {
            return;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isTotem(main) || isTotem(off)) {
            return;
        }
        event.setCancelled(true);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setNoDamageTicks(40);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getValue() > 0) {
            try {
                player.setHealth(maxHealth.getValue());
            } catch (RuntimeException ignored) {
                // The hit is already cancelled. Healing is only there so a second blow cannot land.
            }
        }
        ResetManager.StartResult result = manager.begin(
                player,
                player.getName(),
                event.getCause().name(),
                deathText(player, event.getCause()),
                true
        );
        if (result == ResetManager.StartResult.FAILED) {
            plugin.getLogger().severe("This death did not reset the world.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.getResetManager().noteTotem(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        ResetManager manager = plugin.getResetManager();
        if (manager.isResetting() || manager.savedByTotem(event.getEntity())) {
            return;
        }
        Player player = event.getEntity();
        if (!manager.triggers(player.getGameMode())) {
            plugin.getLogger().info("Ignored a " + player.getGameMode() + " death from " + player.getName() + ".");
            return;
        }
        String deathMessage = event.deathMessage() == null
                ? null
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        String cause = "UNKNOWN";
        if (player.getLastDamageCause() != null && player.getLastDamageCause().getCause() != null) {
            cause = player.getLastDamageCause().getCause().name();
        }
        ResetManager.StartResult result = manager.begin(player, player.getName(), cause, deathMessage, true);
        if (result == ResetManager.StartResult.FAILED) {
            plugin.getLogger().severe("This death did not reset the world.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getResetManager().handleJoin(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (plugin.getResetManager().isResetting()) {
            event.setCancelled(true);
            return;
        }
        var destination = plugin.getResetManager().portalDestination(event.getFrom(), event.getCause());
        if (destination == null) {
            return;
        }
        event.setTo(destination);
        if (event.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            event.setCanCreatePortal(true);
            event.setSearchRadius(16);
            event.setCreationRadius(3);
        } else {
            event.setCanCreatePortal(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(PlayerKickEvent event) {
        ResetManager manager = plugin.getResetManager();
        if (!manager.isResetting()) {
            return;
        }
        manager.pardonEveryone();
        event.setCancelled(true);
        if (event.getCause() == PlayerKickEvent.Cause.BANNED) {
            plugin.getLogger().info("Kept " + event.getPlayer().getName() + " online through the hardcore ban.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        if (event.isAllowed() || !(event.getConnection() instanceof PlayerLoginConnection login)) {
            return;
        }
        var profile = login.getAuthenticatedProfile();
        if (profile == null) {
            profile = login.getUnsafeProfile();
        }
        if (plugin.getResetManager().liftBan(profile)) {
            event.allow();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getResetManager().isResetting()) {
            return;
        }
        var here = event.getPlayer().getLocation();
        if (here.getWorld() != null) {
            event.setRespawnLocation(here);
        }
        plugin.getResetManager().markJustRespawned(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGamemode(PlayerGameModeChangeEvent event) {
        ResetManager manager = plugin.getResetManager();
        if (manager.isResetting() && !manager.isApplyingMode() && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getResetManager().isResetting()) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("The world is resetting.", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            deny(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventory(InventoryOpenEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        deny(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        deny(event);
    }

    private void deny(Cancellable event) {
        if (plugin.getResetManager().isResetting()) {
            event.setCancelled(true);
        }
    }

    private static boolean isTotem(ItemStack stack) {
        return stack != null && stack.getType() == Material.TOTEM_OF_UNDYING;
    }

    private static String deathText(Player player, EntityDamageEvent.DamageCause cause) {
        String name = player.getName();
        return switch (cause) {
            case FALL -> name + " fell from a high place";
            case VOID -> name + " fell out of the world";
            case LAVA -> name + " tried to swim in lava";
            case DROWNING -> name + " drowned";
            case FIRE, FIRE_TICK -> name + " burned to death";
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> name + " was slain";
            case PROJECTILE -> name + " was shot";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> name + " blew up";
            case MAGIC, POISON, WITHER -> name + " withered away";
            case STARVATION -> name + " starved to death";
            case SUFFOCATION -> name + " suffocated";
            case CONTACT -> name + " was pricked to death";
            case HOT_FLOOR -> name + " discovered the floor was lava";
            case CRAMMING -> name + " was squished";
            case FLY_INTO_WALL -> name + " experienced kinetic energy";
            case FREEZE -> name + " froze to death";
            case SONIC_BOOM -> name + " was obliterated";
            default -> name + " died";
        };
    }
}
