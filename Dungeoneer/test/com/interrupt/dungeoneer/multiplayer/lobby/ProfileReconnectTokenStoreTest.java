package com.interrupt.dungeoneer.multiplayer.lobby;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProfileReconnectTokenStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void storesCredentialsByCampaignWithoutMixingThem() throws Exception {
        File file = new File(temporaryFolder.newFolder("identities"), "reconnect.properties");
        ProfileReconnectTokenStore store = new ProfileReconnectTokenStore(file);

        store.save("alpha", repeat('a'));
        store.save("beta", repeat('b'));

        ProfileReconnectTokenStore reloaded = new ProfileReconnectTokenStore(file);
        assertEquals(repeat('a'), reloaded.load("alpha"));
        assertEquals(repeat('b'), reloaded.load("beta"));
        assertNull(reloaded.load("unknown"));
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return result.toString();
    }
}
