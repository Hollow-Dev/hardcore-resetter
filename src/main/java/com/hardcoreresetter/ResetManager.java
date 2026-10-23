package com.hardcoreresetter;

import io.papermc.paper.ban.BanListType;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Statistic;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.scheduler.BukkitTask;

/**
 * Replaces the play world while the server stays online.
 * Purpur cannot unload its main world, so after the first death everyone plays on a world this plugin can delete.
 */
final class ResetManager {
    enum StartResult { STARTED, ALREADY_RUNNING, FAILED }

    private static final String HOLDING = "resetter_holding";
    private static final int MAX_ATTEMPTS = 80;

    private final HardcoreResetterPlugin plugin;
    private final Map<UUID, GameMode> previousModes = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Set<UUID> reviveQueued = new HashSet<>();
    private final Set<UUID> placed = new HashSet<>();
    private final Map<UUID, Long> settledAt = new HashMap<>();
    private final Random random = new Random();

    private boolean managed;
    private String overworldName;
    private boolean resetting;
    private boolean applyingMode;
    private boolean announced;
    private int remaining;
    private int attempts;
    private long pendingSeed;
    private Location playSpawn;
    private Location holdingSpawn;
    private String deathMessage = "";
    private String deathBroadcast = "";
    private boolean pendingDeath;
    private UUID pendingVictim;
    private final Map<UUID, Integer> playerDeaths = new HashMap<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Set<String> playerLines = new HashSet<>();
    private final Set<UUID> totemSaved = new HashSet<>();
    private int runs = 1;
    private int deaths;
    private long runStartedAt;
    private long reconnectUntil;
    private BukkitTask countdown;
    private BukkitTask sidebarTask;
    private org.bukkit.scoreboard.Scoreboard sidebar;
    private org.bukkit.scoreboard.Objective sidebarObjective;

    ResetManager(HardcoreResetterPlugin plugin) {
        this.plugin = plugin;
    }

    void startup() {
        overworldName = mainWorld().getName();
        loadState();
        ensureHolding();
        if (managed) {
            if (!loadOrCreateManaged(false)) {
                plugin.getLogger().severe("The world could not be loaded. It will be created on the next death.");
                managed = false;
                overworldName = mainWorld().getName();
            }
        }
        if (runStartedAt <= 0) {
            runStartedAt = System.currentTimeMillis();
        }
        saveState();
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        }
        startSidebar();
        for (Player player : Bukkit.getOnlinePlayers()) {
            remember(player);
        }
        plugin.getLogger().info(statusText());
    }

    void shutdown() {
        if (sidebarTask != null) {
            sidebarTask.cancel();
            sidebarTask = null;
        }
    }

    boolean isResetting() {
        return resetting;
    }

    boolean isApplyingMode() {
        return applyingMode;
    }

    boolean acceptBannedReconnect() {
        return resetting || System.currentTimeMillis() < reconnectUntil;
    }

    boolean liftBan(com.destroystokyo.paper.profile.PlayerProfile profile) {
        if (profile == null || !acceptBannedReconnect()) {
            return false;
        }
        try {
            ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
            if (!bans.isBanned(profile)) {
                return false;
            }
            pardonEveryone();
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not clear the hardcore ban: " + ex.getMessage());
            return false;
        }
    }

    String statusText() {
        if (resetting) {
            return "Reset in progress. " + Math.max(remaining, 0) + " seconds left.";
        }
        String stats = "Run " + runs + ". Deaths: " + deaths + ". Time: " + formatDuration(System.currentTimeMillis() - runStartedAt) + ".";
        if (!managed) {
            return stats + " The next death wipes everyone and creates a new world.";
        }
        World world = Bukkit.getWorld(overworldName);
        long seed = world == null ? 0L : world.getSeed();
        return stats + " Seed " + seed + ".";
    }

    boolean triggers(GameMode mode) {
        return triggerModes().contains(mode);
    }

    Location portalDestination(Location from, PlayerTeleportEvent.TeleportCause cause) {
        if (!managed || resetting || from.getWorld() == null || !isPlayWorld(from.getWorld())) {
            return null;
        }
        World overworld = Bukkit.getWorld(overworldName);
        World nether = Bukkit.getWorld(netherName());
        World end = Bukkit.getWorld(endName());
        if (overworld == null || nether == null || end == null) {
            return null;
        }
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (from.getWorld().getEnvironment() == World.Environment.NETHER) {
                return findOverworldSpot(overworld, from.getX() * 8.0, from.getZ() * 8.0);
            }
            return findNetherSpot(nether, from.getX() / 8.0, from.getZ() / 8.0);
        }
        if (cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY
                || from.getWorld().getEnvironment() == World.Environment.THE_END) {
            return currentSpawn();
        }
        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return endPlatform(end);
        }
        return null;
    }

    void handleJoin(Player player) {
        remember(player);
        player.setInvulnerable(false);
        if (resetting) {
            player.setInvulnerable(true);
            teleportToHolding(player);
            return;
        }
        if (!managed || isPlayWorld(player.getWorld())) {
            return;
        }
        Location spawn = currentSpawn();
        if (spawn != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !isPlayWorld(player.getWorld())) {
                    player.teleport(spawn);
                }
            });
        }
    }

    StartResult begin(Player subject, String actor, String cause, String message, boolean death) {
        if (resetting) {
            return StartResult.ALREADY_RUNNING;
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return StartResult.FAILED;
        }
        resetting = true;
        announced = false;
        attempts = 0;
        previousModes.clear();
        returnLocations.clear();
        reviveQueued.clear();
        settledAt.clear();
        placed.clear();
        reconnectUntil = System.currentTimeMillis() + 60_000L;
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.setInvulnerable(true);
            online.setFireTicks(0);
            online.setFallDistance(0f);
            online.setNoDamageTicks(40);
            if (online.isDead()) {
                reviveLater(online);
            }
        }
        deathMessage = message == null || message.isBlank() ? actor + " died" : message;
        pendingDeath = death;
        pendingVictim = null;
        if (death && subject != null) {
            remember(subject);
            pendingVictim = subject.getUniqueId();
        }
        refreshSidebar();
        int seconds = countdownSeconds();
        remaining = seconds;
        deathBroadcast = fill(config(
                "death-broadcast",
                "{message} Run {run} lasted {time}. Deaths: {deaths}."
        ), seconds);
        plugin.getLogger().info("Reset started by " + actor + " (" + cause + ").");
        countdown = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 20L);
        return StartResult.STARTED;
    }

    private void tick() {
        pardonEveryone();
        if (!announced) {
            announced = true;
            freeze();
            broadcast(Component.text(deathBroadcast, NamedTextColor.RED));
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 0.35f, 1f);
            }
        } else {
            freeze();
        }
        if (remaining <= 0) {
            if (countdown != null) {
                countdown.cancel();
                countdown = null;
            }
            beginRebuild();
            return;
        }
        Title title = Title.title(
                Component.text(config("reset-title", "WORLD RESET"), NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text(fill(config("reset-subtitle", "New world in {seconds}s"), remaining), NamedTextColor.GOLD),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
        );
        Component bar = Component.text(deathMessage, NamedTextColor.GRAY);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
            player.sendActionBar(bar);
        }
        remaining--;
    }

    private void beginRebuild() {
        pardonEveryone();
        pendingSeed = random.nextLong();
        if (!managed) {
            String freshName = nextFreeName();
            broadcast(Component.text("Creating a new world...", NamedTextColor.GOLD));
            if (!createTrio(freshName, pendingSeed)) {
                abort("The world could not be created. Everyone is still on the old one.");
                return;
            }
            String oldNether = netherName();
            String oldEnd = endName();
            overworldName = freshName;
            managed = true;
            startNewRun();
            saveState();
            Location spawn = currentSpawn();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!moveIfSettled(player, spawn, GameMode.SPECTATOR)) {
                    reviveLater(player);
                }
            }
            if (anyoneUnsettled() || !clearOf(List.of(oldNether, oldEnd))) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> finishFirstMove(oldNether, oldEnd), 5L);
                return;
            }
            removeDimensions(List.of(oldNether, oldEnd));
            arrive();
            return;
        }
        teleportEveryoneToHolding();
        attempts = 0;
        Bukkit.getScheduler().runTaskLater(plugin, this::finishManagedSwap, 5L);
    }

    private void finishManagedSwap() {
        List<String> oldNames = List.of(overworldName, netherName(), endName());
        if (playersInside(oldNames)) {
            teleportEveryoneToHolding();
            retry(this::finishManagedSwap, () -> abort("Players were still in the old world, so it was not deleted."));
            return;
        }
        unload(oldNames);
        if (anyLoaded(oldNames)) {
            retry(this::finishManagedSwap, () -> abort("The old world stayed loaded, so it was not deleted."));
            return;
        }
        if (!deleteFolders(oldNames) && foldersRemain(oldNames)) {
            retry(this::finishManagedSwap, () -> createReplacementAnyway());
            return;
        }
        createReplacementAnyway();
    }

    private void createReplacementAnyway() {
        String freshName = nextFreeName();
        broadcast(Component.text("Creating a new world...", NamedTextColor.GOLD));
        if (!createTrio(freshName, pendingSeed)) {
            abort("The old world was removed, but the new one could not be created. Restart if this stays empty.");
            return;
        }
        overworldName = freshName;
        managed = true;
        startNewRun();
        saveState();
        arrive();
    }

    private void retry(Runnable again, Runnable giveUp) {
        attempts++;
        if (attempts < MAX_ATTEMPTS) {
            Bukkit.getScheduler().runTaskLater(plugin, again, 5L);
            return;
        }
        giveUp.run();
    }

    private void finishFirstMove(String oldNether, String oldEnd) {
        Location spawn = currentSpawn();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!moveIfSettled(player, spawn, GameMode.SPECTATOR)) {
                reviveLater(player);
            }
        }
        if (anyoneUnsettled() || !clearOf(List.of(oldNether, oldEnd))) {
            retry(() -> finishFirstMove(oldNether, oldEnd), () -> {
                plugin.getLogger().warning("A player was still settling, so the old nether and end were left in place.");
                arrive();
            });
            return;
        }
        attempts = 0;
        removeDimensions(List.of(oldNether, oldEnd));
        arrive();
    }

    private void arrive() {
        pardonEveryone();
        Bukkit.getScheduler().runTaskLater(plugin, () -> placeEveryone(0), 5L);
    }

    private void placeEveryone(int attempt) {
        Location spawn = currentSpawn();
        boolean waiting = false;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (placed.contains(player.getUniqueId())) {
                continue;
            }
            if (!settled(player)) {
                waiting = true;
                reviveLater(player);
                continue;
            }
            if (spawn != null && !player.teleport(spawn)) {
                waiting = true;
                continue;
            }
            wipe(player);
            applyMode(player, GameMode.SURVIVAL);
            player.setInvulnerable(false);
            player.setFlying(false);
            player.setAllowFlight(false);
            if (spawn != null) {
                player.setRespawnLocation(spawn, true);
            }
            player.sendMessage(Component.text(newWorldMessage(), NamedTextColor.GOLD));
            player.showTitle(Title.title(
                    Component.text("NEW WORLD", NamedTextColor.GREEN, TextDecoration.BOLD),
                    Component.text("Run " + runs + "  ·  Seed " + currentSeed(), NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(400))
            ));
            placed.add(player.getUniqueId());
        }
        if (waiting && attempt < 40) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> placeEveryone(attempt + 1), 5L);
            return;
        }
        if (waiting) {
            plugin.getLogger().warning("A player was still on the death screen when the new world opened.");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setInvulnerable(false);
        }
        deleteStoredPlayers();
        previousModes.clear();
        returnLocations.clear();
        reviveQueued.clear();
        settledAt.clear();
        placed.clear();
        resetting = false;
        reconnectUntil = System.currentTimeMillis() + 60_000L;
        plugin.getLogger().info("New world " + overworldName + " is ready. Seed " + currentSeed() + ".");
        Bukkit.getScheduler().runTaskLater(plugin, this::pullStragglers, 10L);
    }

    private void pullStragglers() {
        if (resetting) {
            return;
        }
        Location spawn = currentSpawn();
        if (spawn == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline() && !player.isDead() && !isPlayWorld(player.getWorld())) {
                player.setInvulnerable(false);
                applyMode(player, GameMode.SURVIVAL);
                player.teleport(spawn);
            }
        }
    }

    private void abort(String reason) {
        abort(reason, true);
    }

    private void abort(String reason, boolean failure) {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        pendingDeath = false;
        pendingVictim = null;
        if (failure) {
            plugin.getLogger().severe(reason);
        } else {
            plugin.getLogger().info(reason);
        }
        broadcast(Component.text(reason, NamedTextColor.RED));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setInvulnerable(false);
            GameMode mode = previousModes.get(player.getUniqueId());
            if (mode != null) {
                applyMode(player, mode);
            }
            Location back = returnLocations.get(player.getUniqueId());
            if (back != null && back.getWorld() != null) {
                player.teleport(back);
            }
            player.clearTitle();
        }
        previousModes.clear();
        returnLocations.clear();
        reviveQueued.clear();
        settledAt.clear();
        placed.clear();
        resetting = false;
        refreshSidebar();
    }

    private void freeze() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setInvulnerable(true);
            previousModes.putIfAbsent(
                    player.getUniqueId(),
                    player.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : player.getGameMode()
            );
            if (player.getWorld() != null && !HOLDING.equals(player.getWorld().getName())) {
                returnLocations.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
            }
            if (!settled(player)) {
                reviveLater(player);
                continue;
            }
            if (player.getGameMode() != GameMode.SPECTATOR) {
                applyMode(player, GameMode.SPECTATOR);
            }
        }
    }

    private void wipe(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.getEnderChest().clear();
        player.setLevel(0);
        player.setExp(0);
        player.setTotalExperience(0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        for (org.bukkit.potion.PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        if (!player.isDead()) {
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && maxHealth.getValue() > 0) {
                player.setHealth(maxHealth.getValue());
            }
        }
        player.setRespawnLocation(null, true);
        player.undiscoverRecipes(new ArrayList<>(player.getDiscoveredRecipes()));
        Iterator<Advancement> advancements = Bukkit.advancementIterator();
        while (advancements.hasNext()) {
            AdvancementProgress progress = player.getAdvancementProgress(advancements.next());
            for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criterion);
            }
        }
        resetStatistics(player);
        PersistentDataContainer data = player.getPersistentDataContainer();
        for (NamespacedKey key : new ArrayList<>(data.getKeys())) {
            data.remove(key);
        }
    }

    private void resetStatistics(Player player) {
        for (Statistic statistic : Statistic.values()) {
            switch (statistic.getType()) {
                case UNTYPED -> player.setStatistic(statistic, 0);
                case BLOCK -> {
                    for (Material material : Material.values()) {
                        if (!material.isBlock()) {
                            continue;
                        }
                        try {
                            player.setStatistic(statistic, material, 0);
                        } catch (IllegalArgumentException ignored) {
                            // This material is not tracked by this statistic.
                        }
                    }
                }
                case ITEM -> {
                    for (Material material : Material.values()) {
                        if (!material.isItem()) {
                            continue;
                        }
                        try {
                            player.setStatistic(statistic, material, 0);
                        } catch (IllegalArgumentException ignored) {
                            // This material is not tracked by this statistic.
                        }
                    }
                }
                case ENTITY -> {
                    for (EntityType type : EntityType.values()) {
                        if (type == EntityType.UNKNOWN) {
                            continue;
                        }
                        try {
                            player.setStatistic(statistic, type, 0);
                        } catch (IllegalArgumentException ignored) {
                            // This entity is not tracked by this statistic.
                        }
                    }
                }
            }
        }
    }

    private boolean createTrio(String name, long seed) {
        World overworld = createWorld(name, World.Environment.NORMAL, true, seed);
        World nether = createWorld(name + "_nether", World.Environment.NETHER, true, seed);
        World end = createWorld(name + "_the_end", World.Environment.THE_END, true, seed);
        if (overworld == null || nether == null || end == null) {
            plugin.getLogger().severe("World creation failed for " + name + ".");
            return false;
        }
        prepare(overworld);
        prepare(nether);
        prepare(end);
        endPlatform(end);
        playSpawn = findOverworldSpot(overworld, overworld.getSpawnLocation().getX(), overworld.getSpawnLocation().getZ());
        overworld.setSpawnLocation(playSpawn.getBlockX(), playSpawn.getBlockY(), playSpawn.getBlockZ());
        playSpawn = new Location(overworld, playSpawn.getBlockX() + 0.5, playSpawn.getY(), playSpawn.getBlockZ() + 0.5);
        return true;
    }

    private boolean loadOrCreateManaged(boolean freshSeed) {
        File container = plugin.getServer().getWorldContainer();
        boolean missing = !new File(container, overworldName).isDirectory();
        boolean applySeed = freshSeed || missing;
        long seed = applySeed ? random.nextLong() : 0L;
        World overworld = createWorld(overworldName, World.Environment.NORMAL, applySeed, seed);
        World nether = createWorld(netherName(), World.Environment.NETHER, applySeed, seed);
        World end = createWorld(endName(), World.Environment.THE_END, applySeed, seed);
        if (overworld == null || nether == null || end == null) {
            return false;
        }
        prepare(overworld);
        prepare(nether);
        prepare(end);
        if (missing) {
            endPlatform(end);
            playSpawn = findOverworldSpot(overworld, overworld.getSpawnLocation().getX(), overworld.getSpawnLocation().getZ());
            overworld.setSpawnLocation(playSpawn.getBlockX(), playSpawn.getBlockY(), playSpawn.getBlockZ());
            playSpawn = new Location(overworld, playSpawn.getBlockX() + 0.5, playSpawn.getY(), playSpawn.getBlockZ() + 0.5);
        } else {
            Location spawn = overworld.getSpawnLocation();
            playSpawn = new Location(overworld, spawn.getBlockX() + 0.5, spawn.getY(), spawn.getBlockZ() + 0.5);
        }
        if (missing) {
            saveState();
        }
        return true;
    }

    private World createWorld(String name, World.Environment environment, boolean applySeed, long seed) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(name).environment(environment);
        File folder = new File(plugin.getServer().getWorldContainer(), name);
        if (applySeed && !folder.isDirectory()) {
            creator.seed(seed);
        }
        if (plugin.getConfig().getBoolean("hardcore", true) && environment == World.Environment.NORMAL) {
            creator.hardcore(true);
        }
        return creator.createWorld();
    }

    private void prepare(World world) {
        if (plugin.getConfig().getBoolean("hardcore", true)) {
            world.setHardcore(true);
        }
        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(GameRules.KEEP_INVENTORY, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setStorm(false);
        world.setThundering(false);
        if (world.getEnvironment() == World.Environment.NORMAL) {
            world.setTime(1000L);
        }
    }

    private World ensureHolding() {
        World existing = Bukkit.getWorld(HOLDING);
        if (existing == null && !HOLDING.equals(mainWorld().getName())) {
            existing = new WorldCreator(HOLDING)
                    .environment(World.Environment.NORMAL)
                    .generator(new VoidGenerator())
                    .generateStructures(false)
                    .createWorld();
        }
        if (existing == null) {
            plugin.getLogger().severe("The waiting world could not be created.");
            return null;
        }
        existing.setDifficulty(Difficulty.PEACEFUL);
        existing.setSpawnFlags(false, false);
        existing.setGameRule(GameRules.SPAWN_MOBS, false);
        existing.setGameRule(GameRules.ADVANCE_TIME, false);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                existing.getBlockAt(x, 64, z).setType(Material.BEDROCK);
                existing.getBlockAt(x, 65, z).setType(Material.AIR);
                existing.getBlockAt(x, 66, z).setType(Material.AIR);
            }
        }
        existing.setSpawnLocation(0, 65, 0);
        holdingSpawn = new Location(existing, 0.5, 65, 0.5);
        return existing;
    }

    private void teleportEveryoneToHolding() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            teleportToHolding(player);
        }
    }

    private void teleportToHolding(Player player) {
        if (holdingSpawn == null) {
            ensureHolding();
        }
        if (!moveIfSettled(player, holdingSpawn, GameMode.SPECTATOR)) {
            reviveLater(player);
        }
    }

    private Location currentSpawn() {
        if (playSpawn != null && playSpawn.getWorld() != null) {
            return playSpawn;
        }
        World world = Bukkit.getWorld(overworldName);
        if (world == null) {
            return null;
        }
        Location spawn = world.getSpawnLocation();
        playSpawn = new Location(world, spawn.getBlockX() + 0.5, spawn.getY(), spawn.getBlockZ() + 0.5);
        return playSpawn;
    }

    private Location findOverworldSpot(World world, double x, double z) {
        int blockX = clamp(x);
        int blockZ = clamp(z);
        Location found = searchSurface(world, blockX, blockZ);
        if (found != null) {
            return found;
        }
        int y = Math.max(64, world.getHighestBlockYAt(blockX, blockZ));
        buildPlatform(world, blockX, y, blockZ, Material.STONE);
        return new Location(world, blockX + 0.5, y + 1, blockZ + 0.5);
    }

    private Location findNetherSpot(World nether, double x, double z) {
        int blockX = clamp(x);
        int blockZ = clamp(z);
        int checks = 0;
        for (int radius = 0; radius <= 32 && checks < 400; radius += 8) {
            for (int dx = -radius; dx <= radius && checks < 400; dx += 8) {
                for (int dz = -radius; dz <= radius && checks < 400; dz += 8) {
                    if (radius != 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int y = 48; y <= 96; y += 8) {
                        checks++;
                        if (safeStand(nether, blockX + dx, y, blockZ + dz)) {
                            return new Location(nether, blockX + dx + 0.5, y, blockZ + dz + 0.5);
                        }
                    }
                }
            }
        }
        buildPlatform(nether, blockX, 64, blockZ, Material.NETHERRACK);
        return new Location(nether, blockX + 0.5, 65, blockZ + 0.5);
    }

    private Location searchSurface(World world, int x, int z) {
        for (int radius = 0; radius <= 96; radius += 16) {
            for (int dx = -radius; dx <= radius; dx += 16) {
                for (int dz = -radius; dz <= radius; dz += 16) {
                    if (radius != 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int columnX = x + dx;
                    int columnZ = z + dz;
                    int y = world.getHighestBlockYAt(columnX, columnZ);
                    if (safeStand(world, columnX, y + 1, columnZ)) {
                        return new Location(world, columnX + 0.5, y + 1, columnZ + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private boolean safeStand(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) {
            return false;
        }
        Block ground = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Material type = ground.getType();
        if (!type.isSolid() || Tag.LEAVES.isTagged(type) || ground.isLiquid() || feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        if (type == Material.MAGMA_BLOCK || type == Material.CACTUS || type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE) {
            return false;
        }
        return feet.getType().isAir() && head.getType().isAir();
    }

    private void buildPlatform(World world, int x, int y, int z, Material floor) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(floor);
                world.getBlockAt(x + dx, y + 1, z + dz).setType(Material.AIR);
                world.getBlockAt(x + dx, y + 2, z + dz).setType(Material.AIR);
            }
        }
    }

    private Location endPlatform(World end) {
        int x = 100;
        int y = 48;
        int z = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                end.getBlockAt(x + dx, y, z + dz).setType(Material.OBSIDIAN);
                for (int dy = 1; dy <= 3; dy++) {
                    end.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                }
            }
        }
        return new Location(end, x + 0.5, y + 1, z + 0.5);
    }

    private void removeDimensions(List<String> names) {
        List<String> removable = new ArrayList<>();
        for (String name : names) {
            if (canDelete(name)) {
                removable.add(name);
            }
        }
        unload(removable);
        if (anyLoaded(removable)) {
            plugin.getLogger().warning("Could not unload " + String.join(", ", removable) + ". Those folders were left in place.");
            return;
        }
        if (!deleteFolders(removable)) {
            plugin.getLogger().warning("Could not delete every old dimension folder.");
        }
    }

    private boolean playersInside(List<String> names) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != null && names.contains(player.getWorld().getName())) {
                return true;
            }
        }
        return false;
    }

    private void unload(List<String> names) {
        for (String name : names) {
            World world = Bukkit.getWorld(name);
            if (world == null || world.equals(mainWorld()) || HOLDING.equals(name)) {
                continue;
            }
            if (!world.getPlayers().isEmpty()) {
                continue;
            }
            Bukkit.unloadWorld(world, false);
        }
    }

    private boolean anyLoaded(List<String> names) {
        for (String name : names) {
            World world = Bukkit.getWorld(name);
            if (world != null && !world.equals(mainWorld()) && !HOLDING.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean deleteFolders(List<String> names) {
        boolean allGone = true;
        for (String name : names) {
            if (!canDelete(name) || Bukkit.getWorld(name) != null) {
                continue;
            }
            File folder = new File(plugin.getServer().getWorldContainer(), name);
            if (!folder.exists()) {
                continue;
            }
            new File(folder, "session.lock").delete();
            if (!deleteRecursively(folder)) {
                allGone = false;
            }
        }
        return allGone;
    }

    private boolean foldersRemain(List<String> names) {
        File container = plugin.getServer().getWorldContainer();
        for (String name : names) {
            if (canDelete(name) && new File(container, name).exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean canDelete(String name) {
        if (name == null || name.isBlank() || HOLDING.equals(name) || name.equals(mainWorld().getName())) {
            return false;
        }
        File folder = new File(plugin.getServer().getWorldContainer(), name);
        try {
            File container = plugin.getServer().getWorldContainer().getCanonicalFile();
            File target = folder.getCanonicalFile();
            File main = mainWorld().getWorldFolder().getCanonicalFile();
            return target.getParentFile() != null
                    && target.getParentFile().getCanonicalFile().equals(container)
                    && !target.equals(main);
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return !file.exists() || file.delete();
    }

    private void deleteStoredPlayers() {
        Set<String> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId().toString());
        }
        for (World world : Bukkit.getWorlds()) {
            File folder = world.getWorldFolder();
            emptyExcept(new File(folder, "playerdata"), online);
            emptyExcept(new File(folder, "players"), online);
            emptyExcept(new File(folder, "advancements"), online);
            emptyExcept(new File(folder, "stats"), online);
        }
    }

    private void emptyExcept(File directory, Set<String> keep) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteRecursively(file);
                continue;
            }
            String name = file.getName();
            int dot = name.indexOf('.');
            String id = dot > 0 ? name.substring(0, dot) : name;
            if (keep.contains(id)) {
                continue;
            }
            file.delete();
        }
    }

    private String nextFreeName() {
        File container = plugin.getServer().getWorldContainer();
        String name = "hardcore";
        int number = 2;
        while (number < 100 && (Bukkit.getWorld(name) != null || new File(container, name).isDirectory())) {
            name = "hardcore-" + number;
            number++;
        }
        return name;
    }

    private void loadState() {
        File file = new File(plugin.getDataFolder(), "state.yml");
        if (!file.exists()) {
            managed = false;
            overworldName = mainWorld().getName();
            runs = 1;
            deaths = 0;
            runStartedAt = System.currentTimeMillis();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        managed = yaml.getBoolean("managed");
        overworldName = yaml.getString("overworld", mainWorld().getName());
        if (overworldName == null || overworldName.isBlank()) {
            overworldName = mainWorld().getName();
        }
        runs = Math.max(1, yaml.getInt("runs", 1));
        deaths = Math.max(0, yaml.getInt("deaths", 0));
        runStartedAt = yaml.getLong("run-started", 0L);
        var players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = players.getString(key + ".name", "Unknown");
                playerNames.put(id, name == null || name.isBlank() ? "Unknown" : name);
                playerDeaths.put(id, Math.max(0, players.getInt(key + ".deaths", 0)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipped a saved player id: " + key);
            }
        }
    }

    private void saveState() {
        plugin.getDataFolder().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("managed", managed);
        yaml.set("overworld", overworldName);
        yaml.set("runs", runs);
        yaml.set("deaths", deaths);
        yaml.set("run-started", runStartedAt);
        for (UUID id : playerNames.keySet()) {
            yaml.set("players." + id + ".name", playerNames.get(id));
            yaml.set("players." + id + ".deaths", playerDeaths.getOrDefault(id, 0));
        }
        try {
            yaml.save(new File(plugin.getDataFolder(), "state.yml"));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save the current world name: " + ex.getMessage());
        }
    }

    void markJustRespawned(Player player) {
        settledAt.put(player.getUniqueId(), System.currentTimeMillis() + 400L);
    }

    private boolean settled(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        return System.currentTimeMillis() >= settledAt.getOrDefault(player.getUniqueId(), 0L);
    }

    private boolean moveIfSettled(Player player, Location location, GameMode mode) {
        if (!settled(player)) {
            return false;
        }
        applyMode(player, mode);
        if (location != null && location.getWorld() != null && player.getWorld() != location.getWorld()) {
            player.teleport(location);
        }
        return true;
    }

    private boolean anyoneUnsettled() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!settled(player)) {
                return true;
            }
        }
        return false;
    }

    private boolean clearOf(List<String> names) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (world != null && names.contains(world.getName())) {
                return false;
            }
        }
        return true;
    }

    private void reviveLater(Player player) {
        if (!player.isDead() || !reviveQueued.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reviveQueued.remove(player.getUniqueId());
            if (!player.isOnline() || !player.isDead()) {
                return;
            }
            try {
                player.spigot().respawn();
                settledAt.put(player.getUniqueId(), System.currentTimeMillis() + 400L);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Could not respawn " + player.getName() + ": " + ex.getMessage());
            }
        }, 5L);
    }

    void pardonEveryone() {
        try {
            ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
            @SuppressWarnings("unchecked")
            Collection<BanEntry<?>> entries = new ArrayList<>((Collection<BanEntry<?>>) (Collection<?>) bans.getEntries());
            for (BanEntry<?> entry : entries) {
                entry.remove();
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not clear the hardcore ban: " + ex.getMessage());
        }
    }

    private void applyMode(Player player, GameMode mode) {
        applyingMode = true;
        try {
            player.setGameMode(mode);
        } finally {
            applyingMode = false;
        }
    }

    private boolean isPlayWorld(World world) {
        if (!managed || world == null) {
            return false;
        }
        String name = world.getName();
        return name.equals(overworldName) || name.equals(netherName()) || name.equals(endName());
    }

    private String netherName() {
        return overworldName + "_nether";
    }

    private String endName() {
        return overworldName + "_the_end";
    }

    private World mainWorld() {
        return plugin.getServer().getWorlds().get(0);
    }

    private int clamp(double value) {
        return (int) Math.max(-2.9e7, Math.min(2.9e7, Math.floor(value)));
    }

    private long currentSeed() {
        World world = Bukkit.getWorld(overworldName);
        return world == null ? pendingSeed : world.getSeed();
    }

    private void startNewRun() {
        if (pendingDeath) {
            deaths++;
            if (pendingVictim != null) {
                playerDeaths.merge(pendingVictim, 1, Integer::sum);
            }
            pendingDeath = false;
            pendingVictim = null;
        }
        runs++;
        runStartedAt = System.currentTimeMillis();
        refreshSidebar();
    }

    void noteTotem(Player player) {
        totemSaved.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> totemSaved.remove(player.getUniqueId()), 40L);
        if (resetting && pendingDeath && player.getUniqueId().equals(pendingVictim)) {
            abort("A totem saved " + player.getName() + ", so the world was not reset.", false);
        }
    }

    boolean savedByTotem(Player player) {
        return totemSaved.contains(player.getUniqueId());
    }

    private void remember(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        boolean changed = !name.equals(playerNames.get(id));
        playerNames.put(id, name);
        playerDeaths.putIfAbsent(id, 0);
        if (changed) {
            saveState();
        }
        refreshSidebar();
    }

    private int shownDeaths() {
        return deaths + (pendingDeath ? 1 : 0);
    }

    private int shownDeaths(UUID id) {
        int count = playerDeaths.getOrDefault(id, 0);
        if (pendingDeath && id.equals(pendingVictim)) {
            count++;
        }
        return count;
    }

    private void startSidebar() {
        if (Bukkit.getScoreboardManager() == null) {
            return;
        }
        sidebar = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebarObjective = sidebar.registerNewObjective(
                "hardcore",
                org.bukkit.scoreboard.Criteria.DUMMY,
                Component.text("Hardcore", NamedTextColor.RED, TextDecoration.BOLD)
        );
        sidebarObjective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        for (String entry : List.of("run", "deaths", "time")) {
            org.bukkit.scoreboard.Score line = sidebarObjective.getScore(entry);
            line.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        }
        sidebarObjective.getScore("run").setScore(15);
        sidebarObjective.getScore("deaths").setScore(14);
        sidebarObjective.getScore("time").setScore(13);
        refreshSidebar();
        sidebarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshSidebar, 20L, 20L);
    }

    private void refreshSidebar() {
        if (sidebarObjective == null) {
            return;
        }
        captureOnline();
        sidebarLine("run", Component.text("Run ", NamedTextColor.GRAY)
                .append(Component.text(Integer.toString(runs), NamedTextColor.WHITE)));
        sidebarLine("deaths", Component.text("Deaths ", NamedTextColor.GRAY)
                .append(Component.text(Integer.toString(shownDeaths()), NamedTextColor.WHITE)));
        sidebarLine("time", Component.text("Time ", NamedTextColor.GRAY)
                .append(Component.text(formatDuration(System.currentTimeMillis() - runStartedAt), NamedTextColor.WHITE)));
        List<UUID> shown = sidebarPlayers();
        Set<String> keep = new HashSet<>();
        int score = 12;
        for (UUID id : shown) {
            String entry = id.toString();
            keep.add(entry);
            org.bukkit.scoreboard.Score line = sidebarObjective.getScore(entry);
            if (playerLines.add(entry)) {
                line.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
            }
            line.setScore(score--);
            String name = playerNames.getOrDefault(id, "Unknown");
            sidebarLine(entry, Component.text(name, NamedTextColor.GRAY)
                    .append(Component.text(" (" + shownDeaths(id) + ")", NamedTextColor.WHITE)));
        }
        for (String entry : new ArrayList<>(playerLines)) {
            if (!keep.contains(entry)) {
                sidebar.resetScores(entry);
                playerLines.remove(entry);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() != sidebar) {
                player.setScoreboard(sidebar);
            }
        }
    }

    private void captureOnline() {
        boolean changed = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            playerDeaths.putIfAbsent(id, 0);
            if (!player.getName().equals(playerNames.get(id))) {
                playerNames.put(id, player.getName());
                changed = true;
            }
        }
        if (changed) {
            saveState();
        }
    }

    private List<UUID> sidebarPlayers() {
        List<UUID> ids = new ArrayList<>(playerNames.keySet());
        ids.sort(Comparator.comparing(id -> playerNames.getOrDefault(id, ""), String.CASE_INSENSITIVE_ORDER));
        if (ids.size() <= 12) {
            return ids;
        }
        List<UUID> kept = new ArrayList<>();
        for (UUID id : ids) {
            if (shownDeaths(id) > 0 || Bukkit.getPlayer(id) != null) {
                kept.add(id);
            }
        }
        if (kept.size() > 12) {
            kept.sort(Comparator.<UUID>comparingInt(this::shownDeaths).reversed()
                    .thenComparing(id -> playerNames.getOrDefault(id, ""), String.CASE_INSENSITIVE_ORDER));
            return new ArrayList<>(kept.subList(0, 12));
        }
        for (UUID id : ids) {
            if (kept.size() >= 12) {
                break;
            }
            if (!kept.contains(id)) {
                kept.add(id);
            }
        }
        kept.sort(Comparator.comparing(id -> playerNames.getOrDefault(id, ""), String.CASE_INSENSITIVE_ORDER));
        return kept;
    }

    private void sidebarLine(String entry, Component text) {
        sidebarObjective.getScore(entry).customName(text);
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + ":" + pad(minutes) + ":" + pad(secs);
        }
        return minutes + ":" + pad(secs);
    }

    private static String pad(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private String newWorldMessage() {
        return fill(config("new-world-message", "Run {run}. Deaths: {deaths}. Seed {seed}. Nothing carried over."), 0);
    }

    private List<GameMode> triggerModes() {
        List<GameMode> modes = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList("trigger-gamemodes")) {
            try {
                modes.add(GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown gamemode in config: " + name);
            }
        }
        if (modes.isEmpty()) {
            modes.add(GameMode.SURVIVAL);
            modes.add(GameMode.ADVENTURE);
        }
        return modes;
    }

    private int countdownSeconds() {
        int seconds = plugin.getConfig().getInt("countdown-seconds", 10);
        if (seconds < 0) {
            return 0;
        }
        return Math.min(seconds, 120);
    }

    private String config(String path, String fallback) {
        String value = plugin.getConfig().getString(path, fallback);
        return value == null ? fallback : value;
    }

    private String fill(String template, int seconds) {
        int shownDeaths = shownDeaths();
        return template.replace("{message}", deathMessage)
                .replace("{seconds}", Integer.toString(seconds))
                .replace("{run}", Integer.toString(runs))
                .replace("{deaths}", Integer.toString(shownDeaths))
                .replace("{time}", formatDuration(System.currentTimeMillis() - runStartedAt))
                .replace("{seed}", Long.toString(currentSeed()));
    }

    private void broadcast(Component message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }
}
