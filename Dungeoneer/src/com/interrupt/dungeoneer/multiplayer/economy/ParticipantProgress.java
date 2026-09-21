package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Bounded Host-owned Campaign Slot gold and character progression. Never an engine object. */
public final class ParticipantProgress {
    public static final int MAX_GOLD = 1000000000;
    public static final int MAX_EXPERIENCE = 1000000000;
    public static final int MAX_LEVEL = 1000;
    public static final int MAX_STAT = 1000;
    public static final int MAX_PENDING_STAT_CHOICES = 1000;
    public static final int MAX_HEALTH = 1000000;
    public static final int MAX_INVENTORY_SIZE = 64;
    /** Native Player.canAddInventorySlot and canAddHotbarSlot limits. */
    public static final int MAX_BACKPACK_SLOTS = 36;
    public static final int MAX_HOTBAR_SLOTS = 10;

    public final ParticipantId participantId;
    public final long revision;
    public final int gold, experience, level;
    public final int attack, defense, agility, speed, magic, endurance;
    public final int pendingStatChoices, maximumHealth, inventorySize, hotbarSize;

    public ParticipantProgress(ParticipantId participantId, long revision, int gold, int experience,
            int level, int attack, int defense, int agility, int speed, int magic, int endurance,
            int pendingStatChoices, int maximumHealth, int inventorySize, int hotbarSize) {
        if(participantId == null || revision < 0L
                || gold < 0 || gold > MAX_GOLD || experience < 0 || experience > MAX_EXPERIENCE
                || level < 0 || level > MAX_LEVEL || !stat(attack) || !stat(defense)
                || !stat(agility) || !stat(speed) || !stat(magic) || !stat(endurance)
                || pendingStatChoices < 0 || pendingStatChoices > MAX_PENDING_STAT_CHOICES
                || maximumHealth < 1 || maximumHealth > MAX_HEALTH
                || inventorySize < 1 || inventorySize > MAX_INVENTORY_SIZE
                || hotbarSize < 0 || hotbarSize > inventorySize) {
            throw new IllegalArgumentException("Participant progress is outside protocol bounds.");
        }
        this.participantId = participantId;
        this.revision = revision;
        this.gold = gold;
        this.experience = experience;
        this.level = level;
        this.attack = attack;
        this.defense = defense;
        this.agility = agility;
        this.speed = speed;
        this.magic = magic;
        this.endurance = endurance;
        this.pendingStatChoices = pendingStatChoices;
        this.maximumHealth = maximumHealth;
        this.inventorySize = inventorySize;
        this.hotbarSize = hotbarSize;
    }

    /** Actor.getNextLevel from v1.08. */
    public int nextLevelExperience() {
        return (level * 4) * (level * 2);
    }

    /** LevelUpOverlay.applyStats from v1.08, including its compound float narrowing. */
    public static int nativeMaximumHealth(int endurance, int level) {
        int maximum = (int)(endurance * (endurance / 3f)) + 4;
        maximum += (level - 1) * 0.5f;
        return maximum;
    }

    public boolean canAddBackpackSlot() {
        return inventorySize < MAX_INVENTORY_SIZE && inventorySize - hotbarSize < MAX_BACKPACK_SLOTS;
    }

    public boolean canAddHotbarSlot() {
        return inventorySize < MAX_INVENTORY_SIZE && hotbarSize < MAX_HOTBAR_SLOTS;
    }

    public boolean sameState(ParticipantProgress other) {
        return other != null && participantId.equals(other.participantId) && gold == other.gold
                && experience == other.experience && level == other.level && attack == other.attack
                && defense == other.defense && agility == other.agility && speed == other.speed
                && magic == other.magic && endurance == other.endurance
                && pendingStatChoices == other.pendingStatChoices
                && maximumHealth == other.maximumHealth && inventorySize == other.inventorySize
                && hotbarSize == other.hotbarSize;
    }

    private static boolean stat(int value) {
        return value >= 0 && value <= MAX_STAT;
    }
}
