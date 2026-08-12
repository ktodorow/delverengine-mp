package com.interrupt.dungeoneer.multiplayer.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Exact engine build plus normalized local content identity exchanged during handshake. */
public final class DirectConnectCompatibility {
    private final String buildId;
    private final String contentFormat;
    private final String contentSha256;

    public DirectConnectCompatibility(String buildId, String contentFormat,
            String contentSha256) {
        this.buildId = requireIdentifier(buildId, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                "Build identity");
        this.contentFormat = requireIdentifier(contentFormat,
                DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES, "Content identity format");
        if(contentSha256 == null
                || !contentSha256.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Normalized content identity must be exactly 64 hexadecimal characters.");
        }
        this.contentSha256 = contentSha256.toLowerCase(Locale.ROOT);
    }

    public static DirectConnectCompatibility forOpenSourceTestFloor(byte[] floorBytes) {
        if(floorBytes == null || floorBytes.length == 0) {
            throw new IllegalArgumentException("Open-source test floor bytes cannot be empty.");
        }

        return forNormalizedOpenSourceAssets(Collections.singletonMap(
                "levels/test-level.bin", floorBytes));
    }

    public static DirectConnectCompatibility forNormalizedOpenSourceAssets(
            Map<String, byte[]> assets) {
        if(assets == null || assets.isEmpty() || assets.size() > 4096) {
            throw new IllegalArgumentException(
                    "Normalized open-source content must contain 1-4096 assets.");
        }

        TreeMap<String, byte[]> sorted = new TreeMap<String, byte[]>();
        for(Map.Entry<String, byte[]> entry : assets.entrySet()) {
            String path = entry.getKey();
            byte[] bytes = entry.getValue();
            if(path == null || path.isEmpty() || path.length() > 512
                    || path.startsWith("/") || path.startsWith("\\")
                    || path.contains("\\") || path.contains("../")
                    || path.contains("/..") || bytes == null) {
                throw new IllegalArgumentException(
                        "Normalized open-source asset path or bytes are invalid.");
            }
            if(sorted.put(path, bytes) != null) {
                throw new IllegalArgumentException(
                        "Normalized open-source asset path is duplicated: " + path);
            }
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] format = DirectConnectProtocol.OPEN_SOURCE_TEST_CONTENT_FORMAT
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(format.length).array());
            digest.update(format);
            digest.update(ByteBuffer.allocate(4).putInt(sorted.size()).array());
            for(Map.Entry<String, byte[]> asset : sorted.entrySet()) {
                byte[] path = asset.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] bytes = asset.getValue();
                digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());
                digest.update(bytes);
            }
            return new DirectConnectCompatibility(DirectConnectProtocol.BUILD_ID,
                    DirectConnectProtocol.OPEN_SOURCE_TEST_CONTENT_FORMAT,
                    toHex(digest.digest()));
        }
        catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", ex);
        }
    }

    public String getBuildId() {
        return buildId;
    }

    public String getContentFormat() {
        return contentFormat;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getContentIdentity() {
        return contentFormat + ":" + contentSha256;
    }

    private static String requireIdentifier(String value, int maximumBytes, String label) {
        if(value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(label
                    + " must be 1-64 letters, numbers, dots, underscores, or hyphens.");
        }
        return value;
    }

    private static String toHex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] hex = new char[bytes.length * 2];
        for(int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = alphabet[value >>> 4];
            hex[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(hex);
    }
}
