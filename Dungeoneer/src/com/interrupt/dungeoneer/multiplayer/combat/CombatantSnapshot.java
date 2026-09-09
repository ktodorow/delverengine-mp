package com.interrupt.dungeoneer.multiplayer.combat;

/** Bounded public state for one encounter actor. */
public final class CombatantSnapshot {
    private final String id;
    private final CombatantKind kind;
    private final int health;
    private final int maximumHealth;

    public CombatantSnapshot(String id, CombatantKind kind, int health, int maximumHealth) {
        if(id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Combatant ID cannot be blank.");
        }
        if(kind == null) throw new IllegalArgumentException("Combatant kind cannot be null.");
        if(maximumHealth < 1 || health < 0 || health > maximumHealth) {
            throw new IllegalArgumentException("Combatant health is outside bounds.");
        }
        this.id = id;
        this.kind = kind;
        this.health = health;
        this.maximumHealth = maximumHealth;
    }

    public String getId() { return id; }
    public CombatantKind getKind() { return kind; }
    public int getHealth() { return health; }
    public int getMaximumHealth() { return maximumHealth; }
    public boolean isLiving() { return health > 0; }
}
