package com.interrupt.dungeoneer.multiplayer.lobby;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LauncherIdentityStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsPrivateRandomIdentityOnceAndReusesIt() throws Exception {
        File firstFile = new File(temporaryFolder.newFolder("first"), "launcher.properties");
        File secondFile = new File(temporaryFolder.newFolder("second"), "launcher.properties");

        LauncherIdentity first = LauncherIdentityStore.loadOrCreate(firstFile, new SecureRandom());
        LauncherIdentity loaded = LauncherIdentityStore.loadOrCreate(firstFile, new SecureRandom());
        LauncherIdentity otherInstallation = LauncherIdentityStore.loadOrCreate(
                secondFile, new SecureRandom());

        assertEquals(first, loaded);
        assertNotEquals(first, otherInstallation);
        assertTrue(firstFile.isFile());
        assertEquals(LauncherIdentity.ENCODED_LENGTH, first.getValue().length());
    }

    @Test
    public void corruptStoredIdentityFailsWithoutSilentReplacement() throws Exception {
        File identityFile = temporaryFolder.newFile("launcher.properties");
        Properties corrupt = new Properties();
        corrupt.setProperty("format", "1");
        corrupt.setProperty("launcherIdentity", "nickname-is-not-an-identity");
        try(FileOutputStream output = new FileOutputStream(identityFile)) {
            corrupt.store(output, "corrupt fixture");
        }

        try {
            LauncherIdentityStore.loadOrCreate(identityFile, new SecureRandom());
            fail("Corrupt Launcher Identity was silently replaced");
        }
        catch(IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not silently replaced"));
        }
    }
}
