package com.interrupt.dungeoneer.owned;

import java.io.File;

public final class OwnedGameCopy {
    private final File archive;
    private final String sha256;
    private final long length;
    private final long lastModified;
    private final ApprovedOwnedGameCopyVariant approvedVariant;
    private final OwnedGameCopyCompatibility compatibility;

    OwnedGameCopy(File archive, String sha256, long length, long lastModified,
            ApprovedOwnedGameCopyVariant approvedVariant, NormalizedOwnedGameManifest normalizedManifest) {
        this.archive = archive;
        this.sha256 = sha256;
        this.length = length;
        this.lastModified = lastModified;
        this.approvedVariant = approvedVariant;
        this.compatibility = new OwnedGameCopyCompatibility(
                normalizedManifest.getFormat(), normalizedManifest.getSha256());
    }

    File getArchive() {
        return archive;
    }

    String getSha256() {
        return sha256;
    }

    long getLength() {
        return length;
    }

    long getLastModified() {
        return lastModified;
    }

    public ApprovedOwnedGameCopyVariant getApprovedVariant() {
        return approvedVariant;
    }

    public OwnedGameCopyCompatibility getCompatibility() {
        return compatibility;
    }
}
