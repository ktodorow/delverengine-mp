package com.interrupt.dungeoneer.owned;

import java.util.Locale;

public final class ApprovedOwnedGameCopyVariant {
    private final String id;
    private final String storefront;
    private final String gameVersion;
    private final String normalizedManifestSha256;

    public ApprovedOwnedGameCopyVariant(String id, String storefront, String gameVersion,
            String normalizedManifestSha256) {
        if(id == null || !id.matches("[a-z0-9][a-z0-9.-]*")) {
            throw new IllegalArgumentException("Variant ID must use lowercase letters, numbers, dots, or hyphens.");
        }
        if(storefront == null || storefront.trim().isEmpty()) {
            throw new IllegalArgumentException("Variant storefront cannot be empty.");
        }
        if(gameVersion == null || gameVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("Variant game version cannot be empty.");
        }
        if(normalizedManifestSha256 == null || !normalizedManifestSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Variant normalized manifest must be a SHA-256 value.");
        }

        this.id = id;
        this.storefront = storefront;
        this.gameVersion = gameVersion;
        this.normalizedManifestSha256 = normalizedManifestSha256.toLowerCase(Locale.ROOT);
    }

    public String getId() {
        return id;
    }

    public String getStorefront() {
        return storefront;
    }

    public String getGameVersion() {
        return gameVersion;
    }

    public String getNormalizedManifestSha256() {
        return normalizedManifestSha256;
    }
}
