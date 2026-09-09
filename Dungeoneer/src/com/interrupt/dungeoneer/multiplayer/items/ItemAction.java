package com.interrupt.dungeoneer.multiplayer.items;

/** Explicit reliable interaction intents; inventory slots are never wire identities. */
public enum ItemAction {
    PICKUP(1), DROP(2), USE_OBJECT(3), SPEND(4), CONSUME(5), EQUIP(6), STOW(7);

    private final int wireId;
    ItemAction(int wireId) { this.wireId = wireId; }
    public int getWireId() { return wireId; }

    public static ItemAction fromWireId(int id) {
        for(ItemAction action : values()) if(action.wireId == id) return action;
        throw new IllegalArgumentException("Unknown item action: " + id);
    }
}
