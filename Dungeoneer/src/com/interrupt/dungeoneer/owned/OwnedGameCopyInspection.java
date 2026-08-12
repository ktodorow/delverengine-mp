package com.interrupt.dungeoneer.owned;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class OwnedGameCopyInspection {
    private final File archive;
    private final String sha256;
    private final Set<String> mountableAssets;

    OwnedGameCopyInspection(File archive, String sha256, Set<String> mountableAssets) {
        this.archive = archive;
        this.sha256 = sha256;
        this.mountableAssets = Collections.unmodifiableSet(new HashSet<String>(mountableAssets));
    }

    public File getArchive() {
        return archive;
    }

    public String getSha256() {
        return sha256;
    }

    public Set<String> getMountableAssets() {
        return mountableAssets;
    }
}
