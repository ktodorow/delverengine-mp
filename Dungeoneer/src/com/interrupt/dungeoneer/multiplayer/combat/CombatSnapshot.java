package com.interrupt.dungeoneer.multiplayer.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete observed state for small authoritative encounter. */
public final class CombatSnapshot {
    public static final int MAX_MONSTERS = 64;
    public static final int MAX_COMBATANTS = 68;
    private final long sequence;
    private final long hostTick;
    private final Map<String, MonsterSnapshot> monsters;
    private final Map<String, CombatantSnapshot> combatants;

    public CombatSnapshot(long sequence, long hostTick,
            List<MonsterSnapshot> monsters,
            List<CombatantSnapshot> combatants) {
        if(sequence < 1L || hostTick < 0L) {
            throw new IllegalArgumentException("Combat snapshot sequence or Host tick is invalid.");
        }
        if(monsters == null || monsters.size() > MAX_MONSTERS) {
            throw new IllegalArgumentException("Combat snapshot monster count is outside bounds.");
        }
        if(combatants == null || combatants.isEmpty()
                || combatants.size() > MAX_COMBATANTS) {
            throw new IllegalArgumentException("Combat snapshot combatant count is outside bounds.");
        }
        Map<String, MonsterSnapshot> monstersById =
                new LinkedHashMap<String, MonsterSnapshot>();
        for(MonsterSnapshot monster : monsters) {
            if(monster == null) throw new IllegalArgumentException("Monster cannot be null.");
            if(monstersById.put(monster.getId(), monster) != null) {
                throw new IllegalArgumentException("Combat snapshot contains duplicate monster ID.");
            }
        }
        Map<String, CombatantSnapshot> byId = new LinkedHashMap<String, CombatantSnapshot>();
        for(CombatantSnapshot combatant : combatants) {
            if(combatant == null) throw new IllegalArgumentException("Combatant cannot be null.");
            if(byId.put(combatant.getId(), combatant) != null) {
                throw new IllegalArgumentException("Combat snapshot contains duplicate combatant ID.");
            }
        }
        for(MonsterSnapshot monster : monstersById.values()) {
            CombatantSnapshot combatant = byId.get(monster.getId());
            if(combatant == null || combatant.getKind() != CombatantKind.MONSTER) {
                throw new IllegalArgumentException(
                        "Combat Monster state needs a matching Monster combatant.");
            }
            if(monster.isGibbed() && combatant.getHealth() > 0) {
                throw new IllegalArgumentException("Living combat Monster cannot be gibbed.");
            }
        }
        this.sequence = sequence;
        this.hostTick = hostTick;
        this.monsters = Collections.unmodifiableMap(monstersById);
        this.combatants = Collections.unmodifiableMap(byId);
    }

    /** Compatibility constructor for pre-registry tests and callers. */
    public CombatSnapshot(long sequence, long hostTick, String monsterTargetId,
            float monsterX, float monsterY, float monsterZ,
            List<CombatantSnapshot> combatants) {
        this(sequence, hostTick, Collections.singletonList(new MonsterSnapshot(
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, monsterTargetId,
                monsterX, monsterY, monsterZ)), combatants);
    }

    public long getSequence() { return sequence; }
    public long getHostTick() { return hostTick; }
    public MonsterSnapshot getMonster(String id) { return monsters.get(id); }

    public List<MonsterSnapshot> getMonsters() {
        return Collections.unmodifiableList(new ArrayList<MonsterSnapshot>(monsters.values()));
    }

    /** Compatibility accessors return first registered Monster. */
    public String getMonsterTargetId() {
        MonsterSnapshot monster = firstMonster();
        return monster == null ? "" : monster.getTargetId();
    }
    public float getMonsterX() {
        MonsterSnapshot monster = firstMonster();
        return monster == null ? 0f : monster.getX();
    }
    public float getMonsterY() {
        MonsterSnapshot monster = firstMonster();
        return monster == null ? 0f : monster.getY();
    }
    public float getMonsterZ() {
        MonsterSnapshot monster = firstMonster();
        return monster == null ? 0f : monster.getZ();
    }
    public CombatantSnapshot getCombatant(String id) { return combatants.get(id); }

    public List<CombatantSnapshot> getCombatants() {
        return Collections.unmodifiableList(new ArrayList<CombatantSnapshot>(combatants.values()));
    }

    private MonsterSnapshot firstMonster() {
        return monsters.isEmpty() ? null : monsters.values().iterator().next();
    }
}
