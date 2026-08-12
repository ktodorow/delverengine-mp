package com.interrupt.dungeoneer.owned;

import java.io.File;

public final class OwnedGameCopy {
    private final File archive;
    private final String sha256;
    private final long length;
    private final long lastModified;

    OwnedGameCopy(File archive, String sha256, long length, long lastModified) {
        this.archive = archive;
        this.sha256 = sha256;
        this.length = length;
        this.lastModified = lastModified;
    }

    public File getArchive() {
        return archive;
    }

    public String getSha256() {
        return sha256;
    }

    public long getLength() {
        return length;
    }

    public long getLastModified() {
        return lastModified;
    }
}
