package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;

/** Stable, explicitly bounded protocol constants for Direct Connect session traffic. */
public final class DirectConnectProtocol {
    public static final int MAGIC = 0x444D5031; // DMP1
    public static final int VERSION = 36;
    public static final String BUILD_ID = "mp-v108-prototype-native-world-hits-37";
    public static final int DEFAULT_PORT = 37777;

    public static final int MAX_TCP_FRAME_BYTES = 1024;
    public static final int MAX_UDP_DATAGRAM_BYTES = 1024;
    public static final int MAX_BUILD_ID_BYTES = 64;
    public static final int MAX_CONTENT_FORMAT_BYTES = 64;
    public static final int MAX_CONTENT_HASH_BYTES = 64;
    public static final int MAX_LAUNCHER_IDENTITY_BYTES = 64;
    public static final int MAX_PARTICIPANT_ID_BYTES = 64;
    public static final int MAX_SESSION_ID_BYTES = 32;
    public static final int MAX_CAMPAIGN_ID_BYTES = 64;
    public static final int MAX_RECONNECT_TOKEN_BYTES = 64;
    public static final int MAX_NICKNAME_BYTES = 64;
    public static final int MAX_AVATAR_ID_BYTES = 32;
    public static final int MAX_FLOOR_ID_BYTES = 128;
    public static final int MAX_REASON_BYTES = 256;
    public static final int MAX_INPUT_FRAMES = 16;
    public static final int MAX_MOVEMENT_ENTITIES = 4;
    public static final int MAX_PARTY_MEMBERS = 4;
    public static final int MAX_PARTY_CHAT_BYTES = 240;
    public static final int MAX_MONSTERS = CombatSnapshot.MAX_MONSTERS;
    public static final int MAX_COMBATANTS = CombatSnapshot.MAX_COMBATANTS;
    public static final int MAX_COMBAT_TARGET_ID_BYTES = 64;
    public static final int MAX_COMBAT_PRESENTATION_EVENTS = 64;
    public static final int MAX_NATIVE_DYNAMIC_ENTITIES = 128;

    public static final String OPEN_SOURCE_TEST_CONTENT_FORMAT =
            "delver-open-source-test-assets-v1";

    private DirectConnectProtocol() { }
}
