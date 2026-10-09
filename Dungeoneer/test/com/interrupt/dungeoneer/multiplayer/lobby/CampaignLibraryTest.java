package com.interrupt.dungeoneer.multiplayer.lobby;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class CampaignLibraryTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void resumePreservesCampaignHostPresentationInsteadOfLauncherDefaults() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(
                temporaryFolder.newFolder("saved-presentation"), new SecureRandom());
        CampaignRoster original = CampaignRoster.createNamed("friends", "Friday friends", 3, 5,
                AvatarCatalog.ownedV108Humanoids(), new LauncherIdentity(repeat('1')),
                new SlotPresentation("Explorer", AvatarCatalog.HUMANOID_3), new SecureRandom());
        store.save(original);

        CampaignRoster resumed = library(store).resume("friends");

        assertEquals(original.getSlot(1).getPresentation(), resumed.getSlot(1).getPresentation());
        assertEquals("Friday friends", resumed.getCampaignName());
        assertEquals(3, resumed.getCapacity());
        assertEquals(5, resumed.getStartingLives());
        assertEquals(original.getSlot(1).getReconnectToken(), resumed.getSlot(1).getReconnectToken());
        assertEquals(original.getSlot(1).getLauncherIdentity(), resumed.getSlot(1).getLauncherIdentity());
        assertEquals(original.getSlot(1).getPresentation(),
                store.load("friends", AvatarCatalog.ownedV108Humanoids()).getSlot(1).getPresentation());
    }

    @Test public void listsRosterOnlyCampaignsAndCreatesIndependentEntries() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(
                temporaryFolder.newFolder("campaigns"), new SecureRandom());
        CampaignLibrary library = library(store);

        library.create("alpha", 2);
        library.create("beta", 4);

        assertEquals(Arrays.asList("alpha", "beta"), store.listCampaigns());
        assertEquals(2, library.list().size());
        assertEquals("alpha", library.list().get(0).getCampaignId());
        assertEquals(2, library.list().get(0).getCapacity());
        assertEquals(1, library.list().get(0).getClaimedSlots());
        assertFalse(library.list().get(0).hasSave());
    }

    @Test public void resumesLockedCapacityWithoutCreatingOrReplacingRoster() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(
                temporaryFolder.newFolder("resume"), new SecureRandom());
        CampaignLibrary library = library(store);
        CampaignRoster created = library.create("friends", 3);
        String reconnectToken = created.getSlot(1).getReconnectToken();

        CampaignRoster resumed = library.resume("friends");

        assertEquals(3, resumed.getCapacity());
        assertEquals(reconnectToken, resumed.getSlot(1).getReconnectToken());
        try {
            library.create("friends", 4);
            fail("Expected duplicate Campaign rejection.");
        }
        catch(IllegalStateException expected) {
            assertEquals("Campaign already exists: friends", expected.getMessage());
        }
    }

    private CampaignLibrary library(CampaignRosterStore store) {
        return new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(),
                new LauncherIdentity(repeat('1')),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        while(result.length() < 64) result.append(value);
        return result.toString();
    }
}
