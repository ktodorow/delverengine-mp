package com.interrupt.dungeoneer.owned;

import java.io.File;
public final class OwnedGameCopyInspection {
    private final File archive;
    private final String sha256;
    private final NormalizedOwnedGameManifest normalizedManifest;

    OwnedGameCopyInspection(File archive, String sha256,
            NormalizedOwnedGameManifest normalizedManifest) {
        this.archive = archive;
        this.sha256 = sha256;
        this.normalizedManifest = normalizedManifest;
    }

    public File getArchive() {
        return archive;
    }

    public String getSha256() {
        return sha256;
    }

    public int getMountableAssetCount() {
        return normalizedManifest.getAssetCount();
    }

    public NormalizedOwnedGameManifest getNormalizedManifest() {
        return normalizedManifest;
    }
}
