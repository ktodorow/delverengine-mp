package com.interrupt.dungeoneer.multiplayer.items;

/** Explicit reliable interaction intents; inventory slots are never wire identities. */
public enum ItemAction {
    PICKUP(1), DROP(2), USE_OBJECT(3), SPEND(4), CONSUME(5), EQUIP(6), STOW(7),
    /** entityId is shop world object; quantity is Host shop entry identity. */
    PURCHASE(8),
    /** entityId is CharacterStat wire identity. */
    CHOOSE_STAT(9),
    /** entityId is the owned item held in hand; quantity 1 wields, 0 releases it. */
    WIELD(10),
    /** entityId is an owned fused bomb whose fuse the owner lit before throwing it. */
    LIGHT(11);

    private final int wireId;
    ItemAction(int wireId) { this.wireId = wireId; }
    public int getWireId() { return wireId; }

    public static ItemAction fromWireId(int id) {
        for(ItemAction action : values()) if(action.wireId == id) return action;
        throw new IllegalArgumentException("Unknown item action: " + id);
    }
}
