package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Options;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MultiplayerProfileTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesWindowsProfileUnderLocalAppData() throws Exception {
        File localAppData = temporaryFolder.newFolder("LocalAppData");
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("LOCALAPPDATA", localAppData.getAbsolutePath());

        File root = MultiplayerProfile.resolveDefaultRoot(environment, null, true);

        assertEquals(new File(localAppData, MultiplayerProfile.DIRECTORY_NAME), root);
    }

    @Test
    public void redirectsLegacySaveAndLogPathsIntoIsolatedProfile() throws Exception {
        File profileRoot = temporaryFolder.newFolder("profile");
        MultiplayerProfile.initialize(profileRoot);

        FileHandle saveDirectory = Game.getFile("save/");
        FileHandle save = Game.getFile("save/0/player.dat");
        FileHandle log = Game.getFile("errorlog.txt");

        assertEquals(new File(profileRoot, "saves").getCanonicalPath(),
                saveDirectory.file().getCanonicalPath());
        assertEquals(new File(profileRoot, "saves/0/player.dat").getCanonicalPath(),
                save.file().getCanonicalPath());
        assertEquals(new File(profileRoot, "logs/errorlog.txt").getCanonicalPath(),
                log.file().getCanonicalPath());
        assertEquals("settings/options.txt", Options.getOptionsFilePath());
        assertTrue(new File(profileRoot, "settings").isDirectory());
        assertTrue(new File(profileRoot, "cache").isDirectory());
        assertTrue(new File(profileRoot, "identities").isDirectory());
        assertTrue(new File(profileRoot, "saves").isDirectory());
        assertTrue(new File(profileRoot, "logs").isDirectory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsProfileTraversal() throws Exception {
        MultiplayerProfile.initialize(temporaryFolder.newFolder("profile"));
        MultiplayerProfile.resolveWritableFile("settings/../../original-save.dat");
    }

    @Test
    public void resolvesNestedProfileDirectoryWithTrailingSeparator() throws Exception {
        File profileRoot = temporaryFolder.newFolder("profile");
        MultiplayerProfile.initialize(profileRoot);

        FileHandle levelsDirectory =
                MultiplayerProfile.resolveLegacyGameFile("saves/0/levels/");
        FileHandle legacyLevelsDirectory =
                MultiplayerProfile.resolveLegacyGameFile("save/0/levels\\");

        assertEquals(new File(profileRoot, "saves/0/levels").getCanonicalPath(),
                levelsDirectory.file().getCanonicalPath());
        assertEquals(new File(profileRoot, "saves/0/levels").getCanonicalPath(),
                legacyLevelsDirectory.file().getCanonicalPath());
    }

    @Test
    public void resolvesNestedProfileDirectoryWithoutTrailingSeparator() throws Exception {
        File profileRoot = temporaryFolder.newFolder("profile");
        MultiplayerProfile.initialize(profileRoot);

        FileHandle levelsDirectory =
                MultiplayerProfile.resolveLegacyGameFile("saves/0/levels");

        assertEquals(new File(profileRoot, "saves/0/levels").getCanonicalPath(),
                levelsDirectory.file().getCanonicalPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyInternalProfileSegment() throws Exception {
        MultiplayerProfile.initialize(temporaryFolder.newFolder("profile"));
        MultiplayerProfile.resolveWritableFile("saves//levels/");
    }
}
