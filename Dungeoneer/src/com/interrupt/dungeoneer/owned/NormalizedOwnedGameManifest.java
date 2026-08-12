package com.interrupt.dungeoneer.owned;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Content identity for locally owned game data. ZIP order, timestamps, compression, and excluded
 * executable entries do not participate in this identity.
 */
public final class NormalizedOwnedGameManifest {
    public static final String FORMAT = "delver-owned-assets-v1";

    private final String sha256;
    private final Set<String> assetPaths;
    private final long totalBytes;

    private NormalizedOwnedGameManifest(String sha256, Set<String> assetPaths, long totalBytes) {
        this.sha256 = sha256;
        this.assetPaths = Collections.unmodifiableSet(new LinkedHashSet<String>(assetPaths));
        this.totalBytes = totalBytes;
    }

    static NormalizedOwnedGameManifest create(List<AssetDigest> assetDigests)
            throws OwnedGameCopyValidationException {
        List<AssetDigest> sorted = new ArrayList<AssetDigest>(assetDigests);
        Collections.sort(sorted, new Comparator<AssetDigest>() {
            @Override
            public int compare(AssetDigest left, AssetDigest right) {
                return left.path.compareTo(right.path);
            }
        });

        MessageDigest digest = newSha256();
        updateBytes(digest, FORMAT.getBytes(StandardCharsets.UTF_8));

        Set<String> paths = new LinkedHashSet<String>();
        long totalBytes = 0;
        for(AssetDigest asset : sorted) {
            byte[] pathBytes = asset.path.getBytes(StandardCharsets.UTF_8);
            updateInt(digest, pathBytes.length);
            updateBytes(digest, pathBytes);
            updateLong(digest, asset.length);
            updateBytes(digest, asset.sha256);

            paths.add(asset.path);
            totalBytes += asset.length;
        }

        return new NormalizedOwnedGameManifest(toHex(digest.digest()), paths, totalBytes);
    }

    public String getFormat() {
        return FORMAT;
    }

    public String getSha256() {
        return sha256;
    }

    public int getAssetCount() {
        return assetPaths.size();
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public boolean containsAsset(String normalizedPath) {
        return assetPaths.contains(normalizedPath);
    }

    Set<String> getAssetPaths() {
        return assetPaths;
    }

    static MessageDigest newSha256() throws OwnedGameCopyValidationException {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException ex) {
            throw new OwnedGameCopyValidationException("SHA-256 is unavailable in this Java runtime.", ex);
        }
    }

    static String toHex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] hex = new char[bytes.length * 2];
        for(int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = alphabet[value >>> 4];
            hex[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(hex);
    }

    private static void updateInt(MessageDigest digest, int value) {
        updateBytes(digest, ByteBuffer.allocate(4).putInt(value).array());
    }

    private static void updateLong(MessageDigest digest, long value) {
        updateBytes(digest, ByteBuffer.allocate(8).putLong(value).array());
    }

    private static void updateBytes(MessageDigest digest, byte[] bytes) {
        digest.update(bytes, 0, bytes.length);
    }

    static final class AssetDigest {
        final String path;
        final long length;
        final byte[] sha256;

        AssetDigest(String path, long length, byte[] sha256) {
            this.path = path;
            this.length = length;
            this.sha256 = sha256.clone();
        }
    }
}
