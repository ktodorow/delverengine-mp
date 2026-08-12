package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OwnedGameCopyTest {
    private static final String OWNER_V108_SHA256 =
            "a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void unmountOwnedCopy() {
        OwnedGameCopyMount.unmount();
    }

    @Test
    public void validatesApprovedArchiveAndMountsAssetsReadOnly() throws Exception {
        File archive = createArchive(requiredEntries());
        String fingerprintBefore = OwnedGameCopyValidator.sha256(archive);
        OwnedGameCopyValidator validator = new OwnedGameCopyValidator(Collections.singleton(fingerprintBefore));

        OwnedGameCopy ownedGameCopy = validator.validate(archive);
        OwnedGameCopyMount.mount(ownedGameCopy);

        FileHandle gameData = OwnedGameCopyMount.resolve("./data/game.dat");
        assertEquals("fake game data", gameData.readString("UTF-8"));
        assertTrue(gameData.exists());
        assertFalse(gameData.isDirectory());
        assertTrue(OwnedGameCopyMount.resolve("data").isDirectory());
        assertNull("Java classes must never be mounted as data",
                OwnedGameCopyMount.resolve("com/example/Commercial.class"));

        try {
            gameData.writeString("changed", false);
            fail("Owned archive handle allowed a write");
        }
        catch(GdxRuntimeException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }

        OwnedGameCopyMount.unmount();
        assertEquals("Archive changed after read-only mount", fingerprintBefore,
                OwnedGameCopyValidator.sha256(archive));
    }

    @Test
    public void recognizesOwnerVerifiedV108Fingerprint() {
        assertTrue(KnownV108OwnedGameCopies.validator().isApprovedFingerprint(OWNER_V108_SHA256));
    }

    @Test
    public void resolvesRepeatedSeparatorsWithoutAllowingTraversal() throws Exception {
        File archive = createArchive(requiredEntries());
        String fingerprint = OwnedGameCopyValidator.sha256(archive);
        OwnedGameCopy ownedGameCopy =
                new OwnedGameCopyValidator(Collections.singleton(fingerprint)).validate(archive);
        OwnedGameCopyMount.mount(ownedGameCopy);

        assertEquals("fake audio", OwnedGameCopyMount.resolve("audio/ui/ui_equip_item.mp3")
                .readString("UTF-8"));
        FileHandle audio = OwnedGameCopyMount.resolve("audio//ui/ui_equip_item.mp3");

        assertEquals("fake audio", audio.readString("UTF-8"));
        assertNull(OwnedGameCopyMount.resolve("audio/../data/game.dat"));
    }

    @Test
    public void rejectsUnknownArchiveWithSafeCertificationInstructions() throws Exception {
        File archive = createArchive(requiredEntries());
        String fingerprint = OwnedGameCopyValidator.sha256(archive);

        try {
            new OwnedGameCopyValidator(Collections.<String>emptySet()).validate(archive);
            fail("Unknown archive was accepted");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains(fingerprint));
            assertTrue(expected.getMessage().contains("never share delver.jar"));
        }
    }

    @Test
    public void rejectsMissingRequiredV108Data() throws Exception {
        Map<String, String> entries = requiredEntries();
        entries.remove("data/game.dat");
        File archive = createArchive(entries);

        try {
            new OwnedGameCopyValidator(Collections.<String>emptySet()).inspect(archive);
            fail("Incomplete archive was accepted");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains("data/game.dat"));
        }
    }

    @Test
    public void rejectsUnsafeArchivePathsBeforeMounting() throws Exception {
        Map<String, String> entries = requiredEntries();
        entries.put("../outside.png", "unsafe");
        File archive = createArchive(entries);

        try {
            new OwnedGameCopyValidator(Collections.<String>emptySet()).inspect(archive);
            fail("Traversal archive was accepted");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains("unsafe archive path"));
        }
    }

    private Map<String, String> requiredEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("data/entities.dat", "fake entities");
        entries.put("data/game.dat", "fake game data");
        entries.put("data/items.dat", "fake items");
        entries.put("data/monsters.dat", "fake monsters");
        entries.put("data/player.dat", "fake player");
        entries.put("packaged_files.txt", "./data/game.dat\n");
        entries.put("audio/ui/ui_equip_item.mp3", "fake audio");
        entries.put("textures/fake.png", "fake png");
        entries.put("com/example/Commercial.class", "fake class");
        return entries;
    }

    private File createArchive(Map<String, String> entries) throws IOException {
        File archive = temporaryFolder.newFile("delver.jar");
        try(ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            for(Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }
}
