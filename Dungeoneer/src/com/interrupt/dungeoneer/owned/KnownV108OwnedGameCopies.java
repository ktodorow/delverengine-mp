package com.interrupt.dungeoneer.owned;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class KnownV108OwnedGameCopies {
    private static final Set<String> APPROVED_SHA256 = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // Owner-verified Delver v1.08 archive. Fingerprints contain no commercial data.
            "a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae"
    )));

    private KnownV108OwnedGameCopies() { }

    public static OwnedGameCopyValidator validator() {
        return new OwnedGameCopyValidator(APPROVED_SHA256);
    }
}
