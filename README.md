# Hardcore Resetter

Purpur / Paper plugin for Minecraft 1.21.11. Drop `HardcoreResetter.jar` into `plugins` and restart the server the way you already do.

When a player dies in survival or adventure, everyone is wiped and a new world is created without shutting the server down. Inventories, ender chests, advancements, statistics, the nether, and the end do not carry over. Nothing is copied into an archive. Ops and the whitelist stay.

The server's original main world has to stay loaded, because Purpur cannot unload it. After the first death, everyone plays on a world the plugin can delete and replace. Nether and end portals in that world go to its own nether and end, and those are replaced too.

The sidebar shows the run number, the total death count, how long the current run has lasted, and each player with their own deaths in parentheses. A death ends the run and adds one to that player. The next world is the next run, and the timer starts over. A forced reset counts as a new run, not a death. A totem pop does not reset the world. The numbers are kept if the server restarts.

Creative and spectator deaths do not reset the server. `/hardcorereset confirm` forces one. Operators only.
