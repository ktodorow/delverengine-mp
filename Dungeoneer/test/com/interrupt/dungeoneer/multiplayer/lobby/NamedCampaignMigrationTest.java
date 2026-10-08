package com.interrupt.dungeoneer.multiplayer.lobby;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import static org.junit.Assert.*;

/** Real predecessor profile files, independent of current writer's schema. */
public class NamedCampaignMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void legacyRosterFallsBackToIdAndBacksUpBeforeAddingMetadata() throws Exception {
        File root = temporary.newFolder("campaigns");
        File file = legacyRoster(root);
        File folder = file.getParentFile();
        String identity = new String(new char[64]).replace('\0', '1');
        String token = new String(new char[64]).replace('\0', '2');
        byte[] before = Files.readAllBytes(file.toPath());
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster migrated = store.load("legacy-friends", AvatarCatalog.ownedV108Humanoids());
        assertEquals("legacy-friends", migrated.getCampaignId());
        assertEquals("legacy-friends", migrated.getCampaignName());
        assertEquals(4, migrated.getCapacity());
        assertEquals(3, migrated.getStartingLives());
        assertEquals(identity, migrated.getSlot(1).getLauncherIdentity().getValue());
        assertEquals(token, migrated.getSlot(1).getReconnectToken());
        assertEquals("humanoid-4", migrated.getSlot(1).getPresentation().getAvatarId());
        File backup = new File(folder, "roster.properties.before-format-2");
        assertTrue("Exact original backup must precede migration", backup.isFile());
        assertArrayEquals(before, Files.readAllBytes(backup.toPath()));
        assertEquals("2", AtomicProperties.load(file, "test roster").getProperty("format"));
        store.load("legacy-friends", AvatarCatalog.ownedV108Humanoids());
        assertArrayEquals(before, Files.readAllBytes(backup.toPath()));
    }

    @Test public void failedRosterBackupLeavesOriginalBytesAndOwnershipUntouched() throws Exception {
        File root = temporary.newFolder("blocked-backup");
        File file = legacyRoster(root);
        byte[] before = Files.readAllBytes(file.toPath());
        assertTrue(new File(file.getParentFile(), "roster.properties.before-format-2").mkdir());
        try {
            new CampaignRosterStore(root, new SecureRandom()).load("legacy-friends", AvatarCatalog.ownedV108Humanoids());
            fail("Migration requires readable exact original backup.");
        }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("original retained")); }
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }

    @Test public void missingNameInCurrentRosterIsCorruptAndNeverSilentlyReplaced() throws Exception {
        File root = temporary.newFolder("missing-name");
        File file = legacyRoster(root);
        java.util.Properties properties = AtomicProperties.load(file, "test fixture");
        properties.setProperty("format", "2");
        properties.setProperty("startingLives", "4");
        AtomicProperties.store(file, properties, "Current corrupt fixture");
        byte[] before = Files.readAllBytes(file.toPath());
        try {
            new CampaignRosterStore(root, new SecureRandom()).load("legacy-friends", AvatarCatalog.ownedV108Humanoids());
            fail("Current schema must contain its display metadata.");
        }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("corrupt")); }
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        assertFalse(new File(file.getParentFile(), "roster.properties.before-format-2").exists());
    }

    private static File legacyRoster(File root) throws Exception {
        File folder = new File(root, "legacy-friends");
        assertTrue(folder.mkdir());
        File file = new File(folder, "roster.properties");
        String identity = new String(new char[64]).replace('\0', '1');
        String token = new String(new char[64]).replace('\0', '2');
        Files.write(file.toPath(), ("format=1\ncampaignId=legacy-friends\ncapacity=4\nslot.1.launcherIdentity="
                + identity + "\nslot.1.reconnectToken=" + token
                + "\nslot.1.nickname=Host\nslot.1.avatar=humanoid-4\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
