package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentityStore;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import com.interrupt.dungeoneer.owned.OwnedGameCopyMount;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

public class DesktopStarterMenuTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File archive;
    private File profile;
    private File previousProfile;
    private boolean previousDebug;
    private boolean previousBoxes;
    private boolean previousDevTools;

    @Before public void setup() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        previousDebug = Game.isDebugMode;
        previousBoxes = Game.drawDebugBoxes;
        previousDevTools = Game.devToolsEnabled;
        profile = temporary.newFolder("profile").getCanonicalFile();
        MultiplayerProfile.initialize(profile);
        String ownedPath = System.getenv("OWNED_GAME_COPY_TEST");
        Assume.assumeTrue("Set OWNED_GAME_COPY_TEST for ordinary startup checks.",
                ownedPath != null && !ownedPath.trim().isEmpty());
        archive = new File(ownedPath).getCanonicalFile();
    }

    @After public void cleanup() {
        OwnedGameCopyMount.unmount();
        Game.isDebugMode = previousDebug;
        Game.drawDebugBoxes = previousBoxes;
        Game.devToolsEnabled = previousDevTools;
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void ordinaryEntryMountsOwnedAssetsKeepsIdentityAndDisablesDevelopmentTools() throws Exception {
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        Game.isDebugMode = true;
        Game.drawDebugBoxes = true;
        Game.devToolsEnabled = true;
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(new String[] {
                "--owned-copy=" + archive, "--profile-root=" + profile, "--dev-tools", "debug=true"
        });

        GameApplication application = DesktopStarter.prepareMultiplayerMenu(options, (initial, reason) -> {
            fail("Explicit compatible owned copy should not require Browse.");
            return null;
        });

        assertNotNull(application);
        assertTrue(OwnedGameCopyMount.isMounted());
        assertEquals(profile, MultiplayerProfile.getRoot());
        assertEquals(identity.getValue(), LauncherIdentityStore.loadOrCreate().getValue());
        assertFalse("Production entry disables development tools.", Game.devToolsEnabled);
        assertFalse(Game.isDebugMode);
        assertFalse(Game.drawDebugBoxes);
        assertTrue("Ordinary entry must use multiplayer-only startup.", application.isMultiplayerLauncher());
        assertNull("Title/menu must not open a session listener.", application.getDirectConnectPeer());
    }
}
