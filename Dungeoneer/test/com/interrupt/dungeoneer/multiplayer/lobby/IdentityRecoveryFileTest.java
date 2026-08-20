package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IdentityRecoveryFileTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exportImportRestoresPrivateIdentityAndEveryReconnectCredential() throws Exception {
        File sourceRoot = temporaryFolder.newFolder("source-profile");
        File targetRoot = temporaryFolder.newFolder("target-profile");
        File recoveryFile = temporaryFolder.newFile("identity-recovery.properties");

        MultiplayerProfile.initialize(sourceRoot);
        LauncherIdentity sourceIdentity = LauncherIdentityStore.loadOrCreate();
        ProfileReconnectTokenStore sourceTokens = new ProfileReconnectTokenStore();
        sourceTokens.save("friends", token('a'));
        sourceTokens.save("second-campaign", token('b'));
        IdentityRecoveryFile.exportCurrentProfile(recoveryFile);

        MultiplayerProfile.initialize(targetRoot);
        ProfileReconnectTokenStore targetTokens = new ProfileReconnectTokenStore();
        targetTokens.save("stale", token('c'));
        LauncherIdentity imported = IdentityRecoveryFile.importIntoCurrentProfile(recoveryFile);

        assertTrue(recoveryFile.isFile());
        assertEquals(sourceIdentity, imported);
        assertEquals(sourceIdentity, LauncherIdentityStore.loadOrCreate());
        assertEquals(token('a'), targetTokens.load("friends"));
        assertEquals(token('b'), targetTokens.load("second-campaign"));
        assertNull(targetTokens.load("stale"));
    }

    private String token(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return result.toString();
    }
}
