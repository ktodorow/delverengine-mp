package com.interrupt.dungeoneer.owned;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ApprovedOwnedGameCopyRegistryTest {
    private static final String STEAM_MANIFEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String GOG_MANIFEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void addsStorefrontVariantsUsingOnlyNormalizedIdentityAndLabels() {
        ApprovedOwnedGameCopyRegistry registry = new ApprovedOwnedGameCopyRegistry(Arrays.asList(
                new ApprovedOwnedGameCopyVariant("v1.08-steam", "Steam", "v1.08", STEAM_MANIFEST),
                new ApprovedOwnedGameCopyVariant("v1.08-gog", "GOG", "v1.08", GOG_MANIFEST)));

        assertEquals("Steam", registry.find(STEAM_MANIFEST).getStorefront());
        assertEquals("GOG", registry.find(GOG_MANIFEST).getStorefront());
        assertNull(registry.find("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAmbiguousDuplicateManifestRegistration() {
        new ApprovedOwnedGameCopyRegistry(Arrays.asList(
                new ApprovedOwnedGameCopyVariant("v1.08-steam", "Steam", "v1.08", STEAM_MANIFEST),
                new ApprovedOwnedGameCopyVariant("v1.08-gog", "GOG", "v1.08", STEAM_MANIFEST)));
    }
}
