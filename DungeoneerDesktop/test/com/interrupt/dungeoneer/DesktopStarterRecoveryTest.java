package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentityStore;
import com.interrupt.dungeoneer.multiplayer.lobby.ProfileReconnectTokenStore;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class DesktopStarterRecoveryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recoveryCommandsTransferIdentityWithoutLaunchingDesktopGame() throws Exception {
        File source = temporaryFolder.newFolder("source-profile");
        File target = temporaryFolder.newFolder("target-profile");
        File recovery = temporaryFolder.newFile("identity-recovery.properties");

        MultiplayerProfile.initialize(source);
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        new ProfileReconnectTokenStore().save("friends", token('d'));

        DesktopStarter.main(new String[] {
                "--profile-root=" + source.getAbsolutePath(),
                "--export-identity-recovery=" + recovery.getAbsolutePath()
        });
        DesktopStarter.main(new String[] {
                "--profile-root=" + target.getAbsolutePath(),
                "--import-identity-recovery=" + recovery.getAbsolutePath()
        });

        assertEquals(identity, LauncherIdentityStore.loadOrCreate());
        assertEquals(token('d'), new ProfileReconnectTokenStore().load("friends"));
    }

    private String token(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return result.toString();
    }
}
