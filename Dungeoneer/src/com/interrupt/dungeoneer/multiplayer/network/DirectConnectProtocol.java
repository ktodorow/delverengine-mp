package com.interrupt.dungeoneer.multiplayer.network;

/** Stable, explicitly bounded protocol constants for the first Direct Connect slice. */
public final class DirectConnectProtocol {
    public static final int MAGIC = 0x444D5031; // DMP1
    public static final int VERSION = 1;
    public static final String BUILD_ID = "mp-v108-prototype-network-1";
    public static final int DEFAULT_PORT = 37777;

    public static final int MAX_TCP_FRAME_BYTES = 1024;
    public static final int MAX_BUILD_ID_BYTES = 64;
    public static final int MAX_CONTENT_FORMAT_BYTES = 64;
    public static final int MAX_CONTENT_HASH_BYTES = 64;
    public static final int MAX_PARTICIPANT_ID_BYTES = 64;
    public static final int MAX_SESSION_ID_BYTES = 32;
    public static final int MAX_FLOOR_ID_BYTES = 128;
    public static final int MAX_REASON_BYTES = 256;

    public static final String OPEN_SOURCE_TEST_CONTENT_FORMAT =
            "delver-open-source-test-assets-v1";

    private DirectConnectProtocol() { }
}
