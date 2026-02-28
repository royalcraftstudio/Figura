package org.figuramc.figura.avatar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.permissions.PermissionManager;
import org.figuramc.figura.permissions.PermissionPack;
import org.figuramc.figura.permissions.Permissions;

import java.util.*;

public class SmartPanicManager {

    private static final boolean DEBUG = false;

    private final Minecraft client;

    private final Map<UUID, PermissionPack.PlayerPermissionPack> permPacks = new HashMap<>();
    private final Map<UUID, PermissionPack.CategoryPermissionPack> originalCategories = new HashMap<>();

    private final Map<UUID, Integer>  originalComplexity    = new HashMap<>();
    private final Map<UUID, Float>    smoothComplexity      = new HashMap<>();
    private final Map<UUID, Integer>  lastFlushedComplexity = new HashMap<>();
    private final Map<UUID, LodLevel> playerLodLevels       = new HashMap<>();
    private final Map<UUID, Double>   playerDistances       = new HashMap<>();
    private final Set<UUID>           playersInRange        = new HashSet<>();
    private final Map<UUID, Long>     lastPlayerUpdate      = new HashMap<>();

    private final Set<UUID> blockedPlayers = new HashSet<>();

    private final ArrayDeque<UUID> initQueue        = new ArrayDeque<>();
    private       Set<UUID>        lastKnownPlayers = new HashSet<>();

    private long lastTickTime = System.currentTimeMillis();

    private static final float HYSTERESIS_MARGIN = 2.0f;
    private static final float LERP_SPEED        = 4.0f;
    private static final int   FLUSH_THRESHOLD   = 3;
    private static final int   MIN_COMPLEXITY    = 0;
    private static final long  INIT_INTERVAL_MS  = 3000;

    public SmartPanicManager(Minecraft client) {
        this.client = client;
    }

    public void update() {
        if (!(Boolean) Configs.PANIC_ENABLED.value) {
            if (!originalComplexity.isEmpty()) restoreAll();
            return;
        }

        ClientLevel world       = client.level;
        LocalPlayer localPlayer = client.player;
        if (world == null || localPlayer == null) return;

        long  now          = System.currentTimeMillis();
        float deltaSeconds = Math.min((now - lastTickTime) / 1000f, 0.2f);
        lastTickTime = now;

        float panicDistance = Math.max(5.0f, Math.min(1000.0f, (Float) Configs.PANIC_DISTANCE.value));
        Set<UUID> currentPlayers = new HashSet<>();

        for (AbstractClientPlayer player : world.players()) {
            if (player.equals(localPlayer)) continue;
            if (Configs.PANIC_IGNORED_PLAYERS.asList().contains(player.getName().getString())) continue;

            UUID uuid = player.getUUID();
            currentPlayers.add(uuid);

            double dist = localPlayer.distanceTo(player);
            if (Double.isNaN(dist) || Double.isInfinite(dist)) continue;
            playerDistances.put(uuid, dist);

            if (!lastKnownPlayers.contains(uuid) && !initQueue.contains(uuid)) {
                initQueue.addLast(uuid);
            }

            Long lastInit = lastPlayerUpdate.get(uuid);
            if (lastInit != null && now - lastInit >= INIT_INTERVAL_MS && !initQueue.contains(uuid)) {
                initQueue.addLast(uuid);
            }
        }

        initQueue.removeIf(uuid -> !currentPlayers.contains(uuid));
        lastKnownPlayers = currentPlayers;

        for (UUID uuid : currentPlayers) {
            Double  distBoxed = playerDistances.get(uuid);
            Integer original  = originalComplexity.get(uuid);
            PermissionPack.PlayerPermissionPack pack = permPacks.get(uuid);

            if (distBoxed == null || original == null || pack == null) continue;

            double   dist = distBoxed;
            LodLevel lod  = calculateLodLevel(dist, panicDistance);

            if (blockedPlayers.contains(uuid)) {
                if (lod != LodLevel.DISABLED) {
                    unblockPlayer(uuid, pack, original);
                } else {
                    playerLodLevels.put(uuid, lod);
                    playersInRange.remove(uuid);
                    continue;
                }
            }

            float target  = (lod == LodLevel.DISABLED)
                    ? 0f
                    : calculateTargetComplexity(dist, panicDistance, original, lod);

            float current = smoothComplexity.getOrDefault(uuid, (float) original);
            float alpha   = 1f - (float) Math.exp(-LERP_SPEED * deltaSeconds);
            float next    = current + (target - current) * alpha;
            if (Math.abs(next - target) < 1.0f) next = target;
            smoothComplexity.put(uuid, next);

            LodLevel prevLod = playerLodLevels.get(uuid);
            if (prevLod != lod) {
                playerLodLevels.put(uuid, lod);
                if (DEBUG) System.out.printf("LOD %s: %s -> %s (dist=%.1f)%n", uuid, prevLod, lod, dist);
            }

            if      (dist < panicDistance - HYSTERESIS_MARGIN) playersInRange.add(uuid);
            else if (dist > panicDistance)                      playersInRange.remove(uuid);

            int intVal      = Math.max(MIN_COMPLEXITY, Math.round(next));
            int lastFlushed = lastFlushedComplexity.getOrDefault(uuid, -1);
            if (Math.abs(intVal - lastFlushed) >= FLUSH_THRESHOLD) {
                try {
                    if (intVal >= original) {
                        pack.reset(Permissions.COMPLEXITY);
                    } else {
                        pack.insert(Permissions.COMPLEXITY, intVal, "smart_panic_lod");
                    }
                    lastFlushedComplexity.put(uuid, intVal);
                    if (DEBUG) System.out.printf("Flush %s: %d%n", uuid, intVal);
                } catch (Exception e) {
                    if (DEBUG) System.err.println("Flush error " + uuid + ": " + e.getMessage());
                }
            }

            if (lod == LodLevel.DISABLED && intVal == 0) {
                blockPlayer(uuid, pack);
            }
        }

        if (!initQueue.isEmpty()) {
            UUID uuid = initQueue.pollFirst();
            try {
                initPlayer(uuid, now);
            } catch (Exception e) {
                if (DEBUG) System.err.println("Init error " + uuid + ": " + e.getMessage());
            }
        }

        cleanupDisconnected(currentPlayers);
    }

    private void blockPlayer(UUID uuid, PermissionPack.PlayerPermissionPack pack) {
        try {
            PermissionPack.CategoryPermissionPack blockedCat =
                    PermissionManager.CATEGORIES.get(Permissions.Category.BLOCKED);
            pack.setCategory(blockedCat);

            pack.insert(Permissions.COMPLEXITY,         0, "smart_panic_lod");
            pack.insert(Permissions.VANILLA_MODEL_EDIT, 0, "smart_panic_lod");

            blockedPlayers.add(uuid);
            smoothComplexity.put(uuid, 0f);
            lastFlushedComplexity.put(uuid, 0);

            AvatarManager.clearAvatars(uuid);

            if (DEBUG) System.out.printf("BLOCK %s -> Category.BLOCKED, avatar cleared for re-fetch%n", uuid);
        } catch (Exception e) {
            if (DEBUG) System.err.println("Block error " + uuid + ": " + e.getMessage());
        }
    }

    private void unblockPlayer(UUID uuid, PermissionPack.PlayerPermissionPack pack, int original) {
        try {
            PermissionPack.CategoryPermissionPack originalCat = originalCategories.get(uuid);
            if (originalCat != null) {
                pack.setCategory(originalCat);
            }

            pack.reset(Permissions.COMPLEXITY);
            pack.reset(Permissions.VANILLA_MODEL_EDIT);

            blockedPlayers.remove(uuid);

            smoothComplexity.put(uuid, 0f);
            lastFlushedComplexity.remove(uuid);

            AvatarManager.clearAvatars(uuid);

            if (DEBUG) System.out.printf("UNBLOCK %s -> restored cat=%s, avatar re-fetching, original=%d%n",
                    uuid, originalCat != null ? originalCat.name : "?", original);
        } catch (Exception e) {
            if (DEBUG) System.err.println("Unblock error " + uuid + ": " + e.getMessage());
        }
    }

    private void initPlayer(UUID uuid, long now) {
        PermissionPack.PlayerPermissionPack pack = PermissionManager.get(uuid);
        if (pack == null) return;

        permPacks.put(uuid, pack);
        lastPlayerUpdate.put(uuid, now);

        if (!originalComplexity.containsKey(uuid)) {
            originalCategories.put(uuid, pack.category);

            pack.reset(Permissions.COMPLEXITY);
            int val = pack.get(Permissions.COMPLEXITY);
            if (val == -1) val = Permissions.COMPLEXITY.getDefault(pack.getCategory());
            if (val == Integer.MAX_VALUE || val > 100000) val = 8191;

            originalComplexity.put(uuid, val);
            smoothComplexity.put(uuid, (float) val);
            lastFlushedComplexity.remove(uuid);

            if (DEBUG) System.out.printf("Init %s: category=%s original=%d%n",
                    uuid, pack.getCategory().name(), val);

        } else if (blockedPlayers.contains(uuid)) {
            if (DEBUG) System.out.printf("Re-init %s while BLOCKED — keeping original=%d cat=%s%n",
                    uuid, originalComplexity.get(uuid),
                    originalCategories.containsKey(uuid) ? originalCategories.get(uuid).name : "?");
        }
    }

    private void cleanupDisconnected(Set<UUID> currentPlayers) {
        Set<UUID> all = new HashSet<>();
        all.addAll(playerDistances.keySet());
        all.addAll(originalComplexity.keySet());

        for (UUID uuid : all) {
            if (currentPlayers.contains(uuid)) continue;
            restorePlayer(uuid);
            if (DEBUG) System.out.println("Cleaned up disconnected: " + uuid);
            removeAllDataFor(uuid);
        }
    }

    private void restorePlayer(UUID uuid) {
        PermissionPack.PlayerPermissionPack pack = permPacks.get(uuid);
        if (pack == null) return;
        try {
            PermissionPack.CategoryPermissionPack originalCat = originalCategories.get(uuid);
            if (originalCat != null) pack.setCategory(originalCat);
            pack.reset(Permissions.COMPLEXITY);
            pack.reset(Permissions.VANILLA_MODEL_EDIT);
        } catch (Exception ignored) {}
    }

    private void removeAllDataFor(UUID uuid) {
        permPacks.remove(uuid);
        playerDistances.remove(uuid);
        originalComplexity.remove(uuid);
        originalCategories.remove(uuid);
        smoothComplexity.remove(uuid);
        lastFlushedComplexity.remove(uuid);
        playerLodLevels.remove(uuid);
        playersInRange.remove(uuid);
        lastPlayerUpdate.remove(uuid);
        blockedPlayers.remove(uuid);
    }

    private void restoreAll() {
        for (UUID uuid : new HashSet<>(permPacks.keySet())) {
            restorePlayer(uuid);
        }
        permPacks.clear();
        originalComplexity.clear();
        originalCategories.clear();
        smoothComplexity.clear();
        lastFlushedComplexity.clear();
        playerLodLevels.clear();
        playersInRange.clear();
        blockedPlayers.clear();
        initQueue.clear();
        lastKnownPlayers.clear();
    }

    public void clear() {
        restoreAll();
        playerDistances.clear();
        lastPlayerUpdate.clear();
    }

    private LodLevel calculateLodLevel(double distance, float panicDistance) {
        float near = panicDistance - HYSTERESIS_MARGIN;
        float far  = panicDistance * 3;

        if      (distance < near * 0.5f)           return LodLevel.ULTRA;
        else if (distance < near)                   return LodLevel.HIGH;
        else if (distance < panicDistance)          return LodLevel.MEDIUM;
        else if (distance < panicDistance * 1.5f)   return LodLevel.LOW;
        else if (distance < far)                    return LodLevel.MINIMAL;
        else                                        return LodLevel.DISABLED;
    }

    private float calculateTargetComplexity(double distance, float panicDistance,
                                            int maxComplexity, LodLevel lod) {
        float transitionStart  = panicDistance - HYSTERESIS_MARGIN;
        float maxReductionDist = panicDistance * 3;

        if (distance < transitionStart) {
            return maxComplexity;
        } else if (distance < panicDistance) {
            float t = (float)((distance - transitionStart) / HYSTERESIS_MARGIN);
            return maxComplexity * (1f - t * 0.2f);
        } else {
            float beyond  = (float)(distance - panicDistance);
            float range   = maxReductionDist - panicDistance;
            if (beyond >= range) return MIN_COMPLEXITY;
            float falloff = (float) Math.sqrt(beyond / range);
            return Math.max(MIN_COMPLEXITY, maxComplexity * (1f - falloff));
        }
    }

    public Map<UUID, Double> getPlayerDistances() {
        Map<UUID, Double> copy = new HashMap<>();
        for (Map.Entry<UUID, Double> e : playerDistances.entrySet()) {
            if (e.getKey() != null && e.getValue() != null
                    && !e.getValue().isNaN() && !e.getValue().isInfinite()) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        return copy;
    }

    public Map<UUID, Integer> getPlayerComplexities() {
        Map<UUID, Integer> out = new HashMap<>();
        for (Map.Entry<UUID, Float> e : smoothComplexity.entrySet()) {
            out.put(e.getKey(), Math.max(0, Math.round(e.getValue())));
        }
        return out;
    }

    public Map<UUID, Integer> getOriginalComplexities() {
        return new HashMap<>(originalComplexity);
    }

    public Map<UUID, LodLevel> getPlayerLodLevels() {
        return new HashMap<>(playerLodLevels);
    }

    public boolean isBlocked(UUID uuid) {
        return blockedPlayers.contains(uuid);
    }

    public Set<UUID> getBlockedPlayers() {
        return new HashSet<>(blockedPlayers);
    }

    public String getMemoryStats() {
        return String.format("LOD: orig=%d dist=%d smooth=%d lod=%d blocked=%d queue=%d",
                originalComplexity.size(), playerDistances.size(),
                smoothComplexity.size(), playerLodLevels.size(),
                blockedPlayers.size(), initQueue.size());
    }

    public enum LodLevel {
        ULTRA(100,  "Ultra - Full Detail"),
        HIGH(90,    "High - Minor Optimization"),
        MEDIUM(70,  "Medium - Moderate Detail"),
        LOW(40,     "Low - Simplified"),
        MINIMAL(15, "Minimal - Basic Only"),
        DISABLED(0, "Disabled");

        public final int    percentDetail;
        public final String displayName;

        LodLevel(int percentDetail, String displayName) {
            this.percentDetail = percentDetail;
            this.displayName   = displayName;
        }
    }
}