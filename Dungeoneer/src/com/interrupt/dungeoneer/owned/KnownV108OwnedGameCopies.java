package com.interrupt.dungeoneer.owned;

import java.util.Collections;

public final class KnownV108OwnedGameCopies {
    private static final ApprovedOwnedGameCopyRegistry REGISTRY = new ApprovedOwnedGameCopyRegistry(
            Collections.singletonList(new ApprovedOwnedGameCopyVariant(
                    "v1.08-owner-verified",
                    "Owner-verified",
                    "v1.08",
                    // Normalized content identity only. No commercial files or per-asset hashes are stored.
                    "6f33f828a076a8ad68582a623be6e92883326b24f7f635be64f093cffe05930e")));

    private KnownV108OwnedGameCopies() { }

    public static OwnedGameCopyValidator validator() {
        return new OwnedGameCopyValidator(REGISTRY);
    }
}
