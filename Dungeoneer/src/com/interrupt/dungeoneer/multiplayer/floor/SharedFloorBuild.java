package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.math.MathUtils;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.managers.EntityManager;
import com.interrupt.managers.ItemManager;
import com.interrupt.managers.MonsterManager;

import java.security.SecureRandom;

/**
 * Makes one Direct Connect floor's native v1.08 construction identical on every peer.
 *
 * While {@link Level#loadFromEditor()} runs, every random generator that build draws from is
 * seeded from the Host-announced seed, and spawn-affecting graphics options are pinned to
 * desktop defaults. Each phase, marker and initialized entity gets its own derived streams, so
 * one divergent roll cannot shift every later roll. Local settings and unpredictable randomness
 * return before lighting and play. Single-player never creates a build, so vanilla generation
 * is unchanged.
 */
public final class SharedFloorBuild {
    /** Desktop Options defaults: every peer spawns the decoration a default install would. */
    public static final int CANONICAL_GRAPHICS_DETAIL_LEVEL = 3;
    public static final float CANONICAL_GFX_QUALITY = 1f;

    /** Steps of Level.loadFromEditor that draw random numbers. */
    public enum Phase { SPAWN_FILTER, PREFABS, MARKERS, DECORATION, INITIALIZATION }

    private static final int GAME_RANDOM = 0;
    private static final int LIBGDX_RANDOM = 1;
    private static final int ITEM_RANDOM = 2;
    private static final int MONSTER_RANDOM = 3;
    private static final int ENTITY_RANDOM = 4;

    private final long seed;
    private boolean active;
    private int localGraphicsDetailLevel;
    private float localGfxQuality;
    private SharedFloorFingerprint fingerprint;

    public SharedFloorBuild(long seed) {
        if(seed == 0L) throw new IllegalArgumentException("Shared floor seed is missing.");
        this.seed = seed;
    }

    public long getSeed() { return seed; }

    public boolean isActive() { return active; }

    /** Fingerprint of the finished build, or null until {@link #finish(Level)} runs. */
    public SharedFloorFingerprint getFingerprint() { return fingerprint; }

    public void begin() {
        if(active) throw new IllegalStateException("Shared floor build is already running.");
        localGraphicsDetailLevel = Options.instance.graphicsDetailLevel;
        localGfxQuality = Options.instance.gfxQuality;
        Options.instance.graphicsDetailLevel = CANONICAL_GRAPHICS_DETAIL_LEVEL;
        Options.instance.gfxQuality = CANONICAL_GFX_QUALITY;
        active = true;
        enter(Phase.SPAWN_FILTER);
    }

    public void enter(Phase phase) {
        reseed(phase, -1, 0);
    }

    /** One marker or entity of a phase; list separates Level entity arrays. */
    public void enterEntity(Phase phase, int list, int index) {
        if(list < 0 || index < 0) throw new IllegalArgumentException("Shared floor entity index is invalid.");
        reseed(phase, list, index);
    }

    /** Captures what this peer built while shared settings still apply. */
    public void finish(Level level) {
        if(!active) throw new IllegalStateException("Shared floor build is not running.");
        fingerprint = SharedFloorFingerprint.capture(level);
    }

    /** Returns local settings and unpredictable randomness; safe after a failed build. */
    public void end() {
        if(!active) return;
        active = false;
        Options.instance.graphicsDetailLevel = localGraphicsDetailLevel;
        Options.instance.gfxQuality = localGfxQuality;
        SecureRandom entropy = new SecureRandom();
        seedGenerators(entropy.nextLong(), entropy.nextLong(), entropy.nextLong(),
                entropy.nextLong(), entropy.nextLong());
    }

    private void reseed(Phase phase, int list, int index) {
        if(!active) return;
        seedGenerators(derive(GAME_RANDOM, phase, list, index),
                derive(LIBGDX_RANDOM, phase, list, index),
                derive(ITEM_RANDOM, phase, list, index),
                derive(MONSTER_RANDOM, phase, list, index),
                derive(ENTITY_RANDOM, phase, list, index));
    }

    /** Every generator the v1.08 floor build path draws from. */
    private static void seedGenerators(long game, long libgdx, long items, long monsters,
            long entities) {
        Game.rand.setSeed(game);
        // Array.random() and Array.shuffle() draw from libgdx's shared generator.
        MathUtils.random.setSeed(libgdx);
        Game current = Game.instance;
        ItemManager itemManager = current == null ? null : current.itemManager;
        if(itemManager != null) itemManager.seedRandom(items);
        MonsterManager monsterManager = current == null ? null : current.monsterManager;
        if(monsterManager != null) monsterManager.seedRandom(monsters);
        if(MonsterManager.instance != null && MonsterManager.instance != monsterManager) {
            MonsterManager.instance.seedRandom(monsters);
        }
        EntityManager entityManager = EntityManager.instance;
        if(entityManager != null) entityManager.seedRandom(entities);
        if(current != null && current.entityManager != null && current.entityManager != entityManager) {
            current.entityManager.seedRandom(entities);
        }
    }

    private long derive(int generator, Phase phase, int list, int index) {
        long value = mix(seed + 0x9E3779B97F4A7C15L * (phase.ordinal() + 1));
        value = mix(value + 0xC2B2AE3D27D4EB4FL * (list + 2));
        value = mix(value + 0x165667B19E3779F9L * (index + 1L));
        return mix(value + 0xD6E8FEB86659FD93L * (generator + 1));
    }

    // SplitMix64 finalizer: nearby inputs give unrelated streams.
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
