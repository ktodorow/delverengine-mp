package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import com.interrupt.dungeoneer.owned.OwnedGameCopy;
import com.interrupt.dungeoneer.owned.OwnedGameCopyMount;
import com.interrupt.dungeoneer.owned.OwnedGameCopySelectionStore;
import com.interrupt.dungeoneer.screens.SplashScreenInfo;
import com.interrupt.utils.JsonUtil;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class OwnedGameCopyLauncherTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File archive;
    private File previousProfile;

    @Before public void initializeProfile() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        MultiplayerProfile.initialize(temporary.newFolder("profile"));
        OwnedGameCopyMount.unmount();
        String ownedPath = System.getenv("OWNED_GAME_COPY_TEST");
        Assume.assumeTrue("Set OWNED_GAME_COPY_TEST for owned startup checks.",
                ownedPath != null && !ownedPath.trim().isEmpty());
        archive = new File(ownedPath).getCanonicalFile();
    }

    @After public void releaseMount() {
        OwnedGameCopyMount.unmount();
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void rejectedSelectionCanBrowseCompatibleCopyBeforeNativeStartup() throws Exception {
        File unsupported = temporary.newFile("unsupported.jar");
        Files.write(unsupported.toPath(), new byte[] {1, 2, 3});
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(
                new String[] {"--owned-copy=" + unsupported});
        List<String> problems = new ArrayList<>();

        OwnedGameCopy copy = OwnedGameCopyLauncher.selectForMenu(options, (initial, reason) -> {
            assertFalse("Rejected copy must not be mounted while Browse is open.",
                    OwnedGameCopyMount.isMounted());
            assertEquals(unsupported, initial);
            problems.add(reason);
            return archive;
        });

        assertNotNull(copy);
        assertEquals(archive, OwnedGameCopySelectionStore.load());
        assertTrue(OwnedGameCopyMount.isMounted());
        assertEquals(1, problems.size());
        assertTrue("Useful selection error is required.", problems.get(0).contains("archive"));
    }

    @Test public void rememberedCopyProvidesOriginalTitleDataReadOnlyWithoutAnotherBrowse() throws Exception {
        OwnedGameCopyLauncher.validateAndMount(DesktopLaunchOptions.parse(
                new String[] {"--owned-copy=" + archive}));
        OwnedGameCopyMount.unmount();

        OwnedGameCopy copy = OwnedGameCopyLauncher.selectForMenu(
                DesktopLaunchOptions.parse(new String[0]), (initial, reason) -> {
                    fail("Compatible remembered copy must not require another Browse.");
                    return null;
                });

        assertNotNull(copy);
        SplashScreenInfo title = JsonUtil.fromJson(SplashScreenInfo.class,
                OwnedGameCopyMount.resolve("data/splash.dat"));
        assertEquals("levels/outdoor-splash.bin", title.backgroundLevel);
        assertEquals("splash/Delver-Logo.png", title.logoImage);
        assertEquals("title.mp3", title.music);
        assertTrue(OwnedGameCopyMount.resolve(title.backgroundLevel).exists());
        assertTrue(OwnedGameCopyMount.resolve(title.logoImage).exists());
        assertTrue(OwnedGameCopyMount.resolve("audio/music/" + title.music).exists());
        assertTrue(OwnedGameCopyMount.resolve("ui/pixel.fnt").readString().contains("char"));
        assertTrue(OwnedGameCopyMount.resolve("ui/pixel.png").exists());
        assertTrue(OwnedGameCopyMount.resolve("ui/skin.png").exists());
        assertTrue(OwnedGameCopyMount.resolve("ui/window.png").exists());
        assertFalse(title.logoFilter);
        assertNull(OwnedGameCopyMount.resolve("com/interrupt/dungeoneer/DesktopStarter.class"));
        try {
            OwnedGameCopyMount.resolve("data/splash.dat").writeString("modified", false);
            fail("Owned title data must remain read-only.");
        }
        catch(RuntimeException expected) { }
        assertEquals("levels/outdoor-splash.bin", JsonUtil.fromJson(SplashScreenInfo.class,
                OwnedGameCopyMount.resolve("data/splash.dat")).backgroundLevel);
    }

    @Test public void explicitBrowseOverridesCompatiblePathAndCancelLeavesNoMount() throws Exception {
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(new String[] {
                "--browse-owned-copy", "--owned-copy=" + archive
        });
        List<String> prompts = new ArrayList<>();
        OwnedGameCopy copy = OwnedGameCopyLauncher.selectForMenu(options, (initial, reason) -> {
            assertEquals(archive, initial);
            assertFalse(OwnedGameCopyMount.isMounted());
            prompts.add(reason);
            return null;
        });
        assertNull("Cancel must not start native asset startup.", copy);
        assertEquals(1, prompts.size());
        assertFalse(OwnedGameCopyMount.isMounted());
        assertNull("Cancelled selection is not remembered.", OwnedGameCopySelectionStore.load());
    }
}
