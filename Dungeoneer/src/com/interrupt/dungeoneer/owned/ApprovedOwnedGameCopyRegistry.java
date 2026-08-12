package com.interrupt.dungeoneer.owned;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ApprovedOwnedGameCopyRegistry {
    private final Map<String, ApprovedOwnedGameCopyVariant> variantsByManifest;

    public ApprovedOwnedGameCopyRegistry(Collection<ApprovedOwnedGameCopyVariant> variants) {
        if(variants == null) throw new IllegalArgumentException("Approved variants cannot be null.");
        Map<String, ApprovedOwnedGameCopyVariant> indexed =
                new LinkedHashMap<String, ApprovedOwnedGameCopyVariant>();
        for(ApprovedOwnedGameCopyVariant variant : variants) {
            if(variant == null) throw new IllegalArgumentException("Approved variant cannot be null.");
            ApprovedOwnedGameCopyVariant previous = indexed.put(
                    variant.getNormalizedManifestSha256(), variant);
            if(previous != null) {
                throw new IllegalArgumentException("Duplicate approved normalized manifest: "
                        + variant.getNormalizedManifestSha256());
            }
        }
        variantsByManifest = Collections.unmodifiableMap(indexed);
    }

    public ApprovedOwnedGameCopyVariant find(NormalizedOwnedGameManifest manifest) {
        return manifest == null ? null : variantsByManifest.get(manifest.getSha256());
    }

    public ApprovedOwnedGameCopyVariant find(String normalizedManifestSha256) {
        return normalizedManifestSha256 == null ? null
                : variantsByManifest.get(normalizedManifestSha256.toLowerCase(Locale.ROOT));
    }

    public boolean isApproved(NormalizedOwnedGameManifest manifest) {
        return find(manifest) != null;
    }
}
