package com.interrupt.dungeoneer.multiplayer.lives;

import com.interrupt.dungeoneer.multiplayer.combat.DeathCause;
import com.interrupt.dungeoneer.multiplayer.items.ItemKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure Life-loss rules: what a consumed Life scatters, destroys or damages, how much gold it
 * forfeits, and how long an exhausted Participant's scatter survives. Host applies them once.
 */
public final class DeathDropRules {
    /** Six unpaused minutes at 60 Host ticks per second. */
    public static final long EXHAUSTED_DROP_TICKS = 6L * 60L * 60L;
    public static final float SCATTER_RADIUS = 0.5f;
    public static final float DROWNING_SCATTER_RADIUS = 3f;
    public static final int MAXIMUM_CONDITION = 4;

    static final int BASE_RATE_BASIS_POINTS = 800;
    static final int RATE_PER_PRIOR_LOSS_BASIS_POINTS = 400;
    static final int RATE_PER_DESTROYED_ITEM_BASIS_POINTS = 200;
    static final int MAXIMUM_RATE_BASIS_POINTS = 2500;

    public enum Fate { DROPPED, DESTROYED }

    public static final class Verdict {
        public final Fate fate;
        /** Condition after the fall for DROPPED; unchanged input for DESTROYED. */
        public final int condition;

        Verdict(Fate fate, int condition) {
            this.fate = fate;
            this.condition = condition;
        }
    }

    private DeathDropRules() { }

    /** Decides every carried item together so an explosion can take everything at once. */
    public static List<Verdict> judge(DeathCause cause, List<ItemKind> kinds, List<Integer> conditions,
            Random random) {
        if(kinds == null || conditions == null || kinds.size() != conditions.size() || random == null) {
            throw new IllegalArgumentException("Item kinds and conditions must pair up.");
        }
        DeathCause effective = cause == null ? DeathCause.COMBAT : cause;
        boolean everythingLost = effective == DeathCause.LAVA
                || (effective == DeathCause.EXPLOSION && chance(random, 3));
        List<Verdict> verdicts = new ArrayList<Verdict>(kinds.size());
        for(int index = 0; index < kinds.size(); index++) {
            ItemKind kind = kinds.get(index) == null ? ItemKind.OTHER : kinds.get(index);
            int condition = clampCondition(conditions.get(index));
            verdicts.add(everythingLost ? destroyed(condition) : judgeOne(effective, kind, condition, random));
        }
        return verdicts;
    }

    private static Verdict judgeOne(DeathCause cause, ItemKind kind, int condition, Random random) {
        boolean gear = kind == ItemKind.WEAPON || kind == ItemKind.ARMOR;
        switch(cause) {
            case EXPLOSION:
                if(chance(random, 35)) return destroyed(condition);
                return dropped(condition - (1 + random.nextInt(3)));
            case BURNING:
                if(kind == ItemKind.SCROLL || kind == ItemKind.FOOD) return destroyed(condition);
                if(kind == ItemKind.POTION && chance(random, 50)) return destroyed(condition);
                return dropped(condition);
            case FALL:
                if(kind == ItemKind.POTION && chance(random, 50)) return destroyed(condition);
                return dropped(gear ? condition - 1 : condition);
            case TRAP:
                return dropped(gear ? condition - 2 : condition);
            case FROST:
                if(kind == ItemKind.POTION && chance(random, 30)) return destroyed(condition);
                return dropped(condition);
            default:
                return dropped(condition);
        }
    }

    public static float scatterRadius(DeathCause cause) {
        return cause == DeathCause.DROWNING ? DROWNING_SCATTER_RADIUS : SCATTER_RADIUS;
    }

    /**
     * Gold forfeited by one Life loss: floor(gold * rate), where
     * rate = clamp(8% + 4% per earlier Life loss - 2% per item this death destroyed, 0%, 25%).
     */
    public static int goldLoss(int gold, int previousLifeLosses, int itemsDestroyed) {
        if(gold <= 0) return 0;
        long basisPoints = BASE_RATE_BASIS_POINTS
                + RATE_PER_PRIOR_LOSS_BASIS_POINTS * (long)Math.max(0, previousLifeLosses)
                - RATE_PER_DESTROYED_ITEM_BASIS_POINTS * (long)Math.max(0, itemsDestroyed);
        basisPoints = Math.max(0L, Math.min(MAXIMUM_RATE_BASIS_POINTS, basisPoints));
        return (int)((gold * basisPoints) / 10000L);
    }

    private static boolean chance(Random random, int percent) {
        return random.nextInt(100) < percent;
    }

    private static int clampCondition(Integer condition) {
        int value = condition == null ? MAXIMUM_CONDITION : condition.intValue();
        return Math.max(0, Math.min(MAXIMUM_CONDITION, value));
    }

    private static Verdict dropped(int condition) {
        return new Verdict(Fate.DROPPED, clampCondition(condition));
    }

    private static Verdict destroyed(int condition) {
        return new Verdict(Fate.DESTROYED, condition);
    }
}
