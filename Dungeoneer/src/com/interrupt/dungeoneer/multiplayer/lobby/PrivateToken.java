package com.interrupt.dungeoneer.multiplayer.lobby;

import java.security.SecureRandom;

final class PrivateToken {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PrivateToken() { }

    static String randomHex(SecureRandom random) {
        byte[] bytes = new byte[LauncherIdentity.RANDOM_BYTES];
        random.nextBytes(bytes);
        char[] encoded = new char[bytes.length * 2];
        for(int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            encoded[i * 2] = HEX[value >>> 4];
            encoded[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(encoded);
    }

    static boolean isValid(String value) {
        return value != null && value.matches("[0-9a-f]{" + LauncherIdentity.ENCODED_LENGTH + "}");
    }
}
