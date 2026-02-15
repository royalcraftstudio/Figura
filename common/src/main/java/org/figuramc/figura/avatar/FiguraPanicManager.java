package org.figuramc.figura.avatar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.permissions.PermissionManager;
import org.figuramc.figura.permissions.PermissionPack;
import org.figuramc.figura.permissions.Permissions;

import java.util.*;

/**
 * FiguraPanicManager - Automatically manages avatar permissions based on player distance
 * This system implements a "panic mode" that automatically blocks or restricts
 * avatars of players who are too FAR from you, helping with performance.
 *
 * HOW IT WORKS:
 * - Players OUTSIDE the panic distance → Avatars are BLOCKED
 * - Players INSIDE the panic distance → Original permissions are RESTORED
 *
 * This allows you to safely render nearby players while blocking distant ones.
 *
 * COMMON MODULE - Platform-agnostic implementation
 */
public class FiguraPanicManager {

    private static final long RELOAD_COOLDOWN_MS = 5000; // 5 seconds between reloads

    private final Minecraft client;

    // Store original permission categories before panic mode changes them
    private final WeakHashMap<UUID, Permissions.Category> originalPerms = new WeakHashMap<>();

    // Track distances to all players (WeakHashMap prevents memory leaks from disconnected players)
    private final Map<UUID, Double> playerDistances = new WeakHashMap<>();

    // Track which players are currently in range (to detect transitions)
    // Use WeakHashMap to prevent memory leaks
    private final Set<UUID> playersInRange = Collections.newSetFromMap(new WeakHashMap<>());

    // Track last update time per player for throttling (prevents spam)
    private final Map<UUID, Long> lastPlayerUpdate = new WeakHashMap<>();

    // Track reload cooldowns per player (WeakHashMap prevents memory leaks from disconnected players)
    private final Map<UUID, Long> reloadCooldown = new WeakHashMap<>();

    // Per-player update interval in milliseconds
    private static final long PLAYER_UPDATE_INTERVAL_MS = 100; // Update every 100ms (10 times per second)

    public FiguraPanicManager(Minecraft client) {
        this.client = client;
    }

    /**
     * Main update method - should be called every client tick from platform-specific code
     * Now updates in real-time with per-player throttling to prevent memory leaks
     */
    public void update() {
        // Check if panic mode is enabled
        if (!(Boolean) Configs.PANIC_ENABLED.value) {
            return;
        }

        ClientLevel world = client.level;
        LocalPlayer localPlayer = client.player;

        if (world == null || localPlayer == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Track which players are still present in this update
        Set<UUID> currentPlayers = new HashSet<>();

        // Iterate through all players in the world
        List<AbstractClientPlayer> players = world.players();
        for (AbstractClientPlayer player : players) {
            try {
                // Skip self
                if (player.equals(localPlayer)) {
                    continue;
                }

                // Skip ignored players
                List<String> ignoredPlayers = Configs.PANIC_IGNORED_PLAYERS.asList();
                if (ignoredPlayers.contains(player.getName().getString())) {
                    continue;
                }

                UUID uuid = player.getUUID();
                currentPlayers.add(uuid);

                // Per-player throttling - only update this player if enough time has passed
                Long lastUpdate = lastPlayerUpdate.get(uuid);
                if (lastUpdate != null && currentTime - lastUpdate < PLAYER_UPDATE_INTERVAL_MS) {
                    continue;
                }

                lastPlayerUpdate.put(uuid, currentTime);

                // Calculate distance
                double distance = localPlayer.distanceTo(player);
                playerDistances.put(uuid, distance);

                // Get player's permission pack
                PermissionPack.PlayerPermissionPack pack = PermissionManager.get(uuid);
                if (pack == null) {
                    continue;
                }

                float panicDistance = (Float) Configs.PANIC_DISTANCE.value;

                // Check if player is OUTSIDE panic distance (far away)
                if (distance > panicDistance) {
                    // Player is out of range
                    playersInRange.remove(uuid);

                    // Store original permissions if not already stored
                    if (!originalPerms.containsKey(uuid)) {
                        originalPerms.put(uuid, pack.getCategory());
                    }

                    // Set to BLOCKED category (block players that are too far)
                    if (pack.getCategory() != Permissions.Category.BLOCKED) {
                        pack.setCategory(PermissionManager.CATEGORIES.get(Permissions.Category.BLOCKED));
                        requestReload(uuid, currentTime);
                    }
                } else {
                    // Player is INSIDE panic distance (close to you)
                    boolean wasInRange = playersInRange.contains(uuid);

                    Permissions.Category previous = originalPerms.remove(uuid);

                    if (previous != null && pack.getCategory() == Permissions.Category.BLOCKED) {
                        // Restore original permissions (allow players that are close)
                        pack.setCategory(PermissionManager.CATEGORIES.get(previous));

                        // Only reload if player just entered range (wasn't in range before)
                        if (!wasInRange) {
                            requestReload(uuid, currentTime);
                        }
                    }

                    // Mark player as in range
                    playersInRange.add(uuid);
                }

                // Always check complexity
                checkComplexity(uuid);

            } catch (Exception e) {
                // Silently handle errors for individual players
            }
        }

        // Clean up disconnected players (prevents memory leaks)
        // Remove players that are no longer in the world from tracking maps
        playerDistances.keySet().retainAll(currentPlayers);
        lastPlayerUpdate.keySet().retainAll(currentPlayers);
        // Note: playersInRange, originalPerms, and reloadCooldown use WeakHashMap
        // so they'll automatically clean up disconnected players
    }

    /**
     * Request avatar reload with cooldown protection
     */
    private void requestReload(UUID uuid, long currentTime) {
        Long lastReload = reloadCooldown.get(uuid);

        if (lastReload == null || currentTime - lastReload >= RELOAD_COOLDOWN_MS) {
            // Request the avatar reload
            Player player = client.level.getPlayerByUUID(uuid);
            if (player != null) {
                // Trigger avatar reload through Figura's system
                org.figuramc.figura.avatar.AvatarManager.reloadAvatar(uuid);
            }
            reloadCooldown.put(uuid, currentTime);
        }
    }

    /**
     * Check and enforce complexity limits
     */
    private void checkComplexity(UUID uuid) {
        // This is a placeholder for complexity checking
        // The actual implementation would depend on Figura's Avatar.Instructions system
        // You can implement additional checks here if needed
    }

    /**
     * Get current player distances (for UI display)
     * Returns a defensive copy to prevent external modification
     */
    public Map<UUID, Double> getPlayerDistances() {
        return new HashMap<>(playerDistances);
    }

    /**
     * Clear all panic mode data and restore original permissions
     */
    public void clear() {
        for (Map.Entry<UUID, Permissions.Category> entry : originalPerms.entrySet()) {
            PermissionPack.PlayerPermissionPack pack = PermissionManager.get(entry.getKey());
            if (pack != null) {
                pack.setCategory(PermissionManager.CATEGORIES.get(entry.getValue()));
            }
        }
        originalPerms.clear();
        playerDistances.clear();
        playersInRange.clear();
        lastPlayerUpdate.clear();
        reloadCooldown.clear();
    }
}