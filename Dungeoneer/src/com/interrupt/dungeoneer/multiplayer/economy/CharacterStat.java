package com.interrupt.dungeoneer.multiplayer.economy;

/** Native LevelUpOverlay attribute choices with stable wire identities. */
public enum CharacterStat {
    ATTACK(1, "ATTACK"), SPEED(2, "SPEED"), HEALTH(3, "HEALTH"),
    MAGIC(4, "MAGIC"), AGILITY(5, "AGILITY"), DEFENSE(6, "DEFENSE");

    private final int wireId;
    private final String nativeAttribute;

    CharacterStat(int wireId, String nativeAttribute) {
        this.wireId = wireId;
        this.nativeAttribute = nativeAttribute;
    }

    public int getWireId() { return wireId; }

    public String getNativeAttribute() { return nativeAttribute; }

    public static CharacterStat fromWireId(long id) {
        for(CharacterStat stat : values()) if(stat.wireId == id) return stat;
        throw new IllegalArgumentException("Unknown character stat: " + id);
    }

    /** Returns null for native attributes outside the v1.08 level-up choices. */
    public static CharacterStat fromNativeAttribute(String attribute) {
        for(CharacterStat stat : values()) if(stat.nativeAttribute.equals(attribute)) return stat;
        return null;
    }
}
