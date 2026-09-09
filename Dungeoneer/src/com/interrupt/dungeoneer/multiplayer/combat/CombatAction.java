package com.interrupt.dungeoneer.multiplayer.combat;

/** Allowlisted actions for one Host-authoritative encounter. */
public enum CombatAction {
    MELEE(1, 4, true, true, 2f, 18L),
    PROJECTILE(2, 3, true, true, 12f, 24L),
    SPELL(3, 5, true, true, 12f, 30L),
    BENEFICIAL_SPELL(4, 3, false, true, 12f, 30L),
    SELF_DAMAGE(5, 2, true, false, 0f, 6L),
    ENVIRONMENTAL_HAZARD(6, 2, true, false, 0f, 30L);

    private final int wireId;
    private final int amount;
    private final boolean harmful;
    private final boolean directed;
    private final float maximumRange;
    private final long minimumIntervalTicks;

    CombatAction(int wireId, int amount, boolean harmful, boolean directed,
            float maximumRange, long minimumIntervalTicks) {
        this.wireId = wireId;
        this.amount = amount;
        this.harmful = harmful;
        this.directed = directed;
        this.maximumRange = maximumRange;
        this.minimumIntervalTicks = minimumIntervalTicks;
    }

    public int getWireId() { return wireId; }
    public int getAmount() { return amount; }
    public boolean isHarmful() { return harmful; }
    public boolean isDirected() { return directed; }
    public boolean allowsTargetedRequest() {
        return this == BENEFICIAL_SPELL || this == SELF_DAMAGE
                || this == ENVIRONMENTAL_HAZARD;
    }
    public float getMaximumRange() { return maximumRange; }
    public long getMinimumIntervalTicks() { return minimumIntervalTicks; }

    public static CombatAction fromWireId(int wireId) {
        for(CombatAction action : values()) {
            if(action.wireId == wireId) return action;
        }
        throw new IllegalArgumentException("Unknown combat action wire ID: " + wireId);
    }
}
