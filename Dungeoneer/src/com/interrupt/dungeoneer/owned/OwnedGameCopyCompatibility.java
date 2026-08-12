package com.interrupt.dungeoneer.owned;

/**
 * Only Owned Game Copy value permitted at a Participant boundary. It identifies content without
 * exposing an archive path, archive fingerprint, asset path, cache path, or asset bytes.
 */
public final class OwnedGameCopyCompatibility {
    private final String manifestFormat;
    private final String normalizedManifestSha256;

    OwnedGameCopyCompatibility(String manifestFormat, String normalizedManifestSha256) {
        this.manifestFormat = manifestFormat;
        this.normalizedManifestSha256 = normalizedManifestSha256;
    }

    public String getManifestFormat() {
        return manifestFormat;
    }

    public String getNormalizedManifestSha256() {
        return normalizedManifestSha256;
    }

    public String toWireValue() {
        return manifestFormat + ":" + normalizedManifestSha256;
    }
}
