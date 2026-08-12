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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private static final String OWNER_V108_NORMALIZED_MANIFEST_SHA256 =
            "6f33f828a076a8ad68582a623be6e92883326b24f7f635be64f093cffe05930e";

    private int archiveCounter;

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
        OwnedGameCopyInspection inspection = emptyValidator().inspect(archive);
        OwnedGameCopyValidator validator = validatorFor(inspection.getNormalizedManifest().getSha256());

        OwnedGameCopy ownedGameCopy = validator.validate(archive);
        OwnedGameCopyMount.mount(ownedGameCopy);

        FileHandle gameData = OwnedGameCopyMount.resolve("./data/game.dat");
        assertEquals("fake game data", gameData.readString("UTF-8"));
        assertTrue(gameData.exists());
        assertFalse(gameData.isDirectory());
        assertTrue(OwnedGameCopyMount.resolve("data").isDirectory());
        assertNull("Java classes must never be mounted as data",
                OwnedGameCopyMount.resolve("com/example/Commercial.class"));
        assertTrue(OwnedGameCopyMount.resolve("packaged_files.txt").readString("UTF-8")
                .contains("./data/game.dat"));

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
    public void recognizesOwnerVerifiedV108NormalizedManifest() {
        assertTrue(KnownV108OwnedGameCopies.validator()
                .isApprovedManifest(OWNER_V108_NORMALIZED_MANIFEST_SHA256));
    }

    @Test
    public void resolvesRepeatedSeparatorsWithoutAllowingTraversal() throws Exception {
        File archive = createArchive(requiredEntries());
        OwnedGameCopyInspection inspection = emptyValidator().inspect(archive);
        OwnedGameCopy ownedGameCopy = validatorFor(
                inspection.getNormalizedManifest().getSha256()).validate(archive);
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
        String manifestSha256 = emptyValidator().inspect(archive).getNormalizedManifest().getSha256();

        try {
            emptyValidator().validate(archive);
            fail("Unknown archive was accepted");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains(manifestSha256));
            assertTrue(expected.getMessage().contains("Select an approved v1.08 copy"));
            assertTrue(expected.getMessage().contains("Never share delver.jar"));
            assertTrue(expected.getMessage().contains("private cache"));
        }
    }

    @Test
    public void rejectsMissingRequiredV108Data() throws Exception {
        Map<String, String> entries = requiredEntries();
        entries.remove("data/game.dat");
        File archive = createArchive(entries);

        try {
            emptyValidator().inspect(archive);
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
            emptyValidator().inspect(archive);
            fail("Traversal archive was accepted");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains("unsafe archive path"));
        }
    }

    @Test
    public void normalizesArchiveOrderTimestampAndCompression() throws Exception {
        Map<String, String> entries = requiredEntries();
        File first = createArchive(entries, 1, 1000000000L);

        List<Map.Entry<String, String>> reversedEntries =
                new ArrayList<Map.Entry<String, String>>(entries.entrySet());
        Collections.reverse(reversedEntries);
        Map<String, String> reversed = new LinkedHashMap<String, String>();
        for(Map.Entry<String, String> entry : reversedEntries) reversed.put(entry.getKey(), entry.getValue());
        File repackaged = createArchive(reversed, 9, 2000000000L);

        OwnedGameCopyInspection firstInspection = emptyValidator().inspect(first);
        OwnedGameCopyInspection repackagedInspection = emptyValidator().inspect(repackaged);

        assertFalse(OwnedGameCopyValidator.sha256(first)
                .equals(OwnedGameCopyValidator.sha256(repackaged)));
        assertEquals(firstInspection.getNormalizedManifest().getSha256(),
                repackagedInspection.getNormalizedManifest().getSha256());

        OwnedGameCopy accepted = validatorFor(
                firstInspection.getNormalizedManifest().getSha256()).validate(repackaged);
        assertEquals("test-v108", accepted.getApprovedVariant().getId());
    }

    @Test
    public void meaningfulAssetChangeProducesDifferentManifestAndFailsClosed() throws Exception {
        Map<String, String> approvedEntries = requiredEntries();
        File approvedArchive = createArchive(approvedEntries);
        String approvedManifest = emptyValidator().inspect(approvedArchive)
                .getNormalizedManifest().getSha256();

        Map<String, String> changedEntries = requiredEntries();
        changedEntries.put("data/game.dat", "changed game data");
        File changedArchive = createArchive(changedEntries);
        String changedManifest = emptyValidator().inspect(changedArchive)
                .getNormalizedManifest().getSha256();

        assertFalse(approvedManifest.equals(changedManifest));
        try {
            validatorFor(approvedManifest).validate(changedArchive);
            fail("Changed content was accepted as approved");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains(changedManifest));
        }
    }

    @Test
    public void excludesCodeNativesSteamMetadataModsAndSavesFromManifestAndMount() throws Exception {
        File assetsOnly = createArchive(requiredEntries());
        String assetsOnlyManifest = emptyValidator().inspect(assetsOnly)
                .getNormalizedManifest().getSha256();

        Map<String, String> packagedEntries = requiredEntries();
        packagedEntries.put("com/example/Commercial.class", "fake class");
        packagedEntries.put("gdx64.dll", "fake native");
        packagedEntries.put("libsteam_api.so", "fake Steam native");
        packagedEntries.put("steam_appid.txt", "fake Steam metadata");
        packagedEntries.put("META-INF/services/provider.txt", "fake JAR metadata");
        packagedEntries.put("packaged_files.txt", "archive packaging metadata");
        packagedEntries.put("mods/commercial.dat", "fake mod");
        packagedEntries.put("save/game.dat", "fake save");
        File packagedArchive = createArchive(packagedEntries);
        OwnedGameCopyInspection inspection = emptyValidator().inspect(packagedArchive);

        assertEquals(assetsOnlyManifest, inspection.getNormalizedManifest().getSha256());
        OwnedGameCopyMount.mount(validatorFor(assetsOnlyManifest).validate(packagedArchive));
        assertNull(OwnedGameCopyMount.resolve("com/example/Commercial.class"));
        assertNull(OwnedGameCopyMount.resolve("gdx64.dll"));
        assertNull(OwnedGameCopyMount.resolve("steam_appid.txt"));
        assertNull(OwnedGameCopyMount.resolve("META-INF/services/provider.txt"));
        String syntheticIndex = OwnedGameCopyMount.resolve("packaged_files.txt").readString("UTF-8");
        assertFalse(syntheticIndex.contains("archive packaging metadata"));
        assertTrue(syntheticIndex.contains("./data/game.dat"));
        assertNull(OwnedGameCopyMount.resolve("mods/commercial.dat"));
        assertNull(OwnedGameCopyMount.resolve("save/game.dat"));
    }

    @Test
    public void rejectsUnsafePathEvenWhenEntryWouldOtherwiseBeExcluded() throws Exception {
        Map<String, String> entries = requiredEntries();
        entries.put("../Commercial.class", "unsafe code");

        try {
            emptyValidator().inspect(createArchive(entries));
            fail("Unsafe excluded entry was ignored");
        }
        catch(OwnedGameCopyValidationException expected) {
            assertTrue(expected.getMessage().contains("unsafe archive path"));
        }
    }

    @Test
    public void exposesOnlyNormalizedIdentityForParticipantCompatibility() throws Exception {
        File archive = createArchive(requiredEntries());
        OwnedGameCopyInspection inspection = emptyValidator().inspect(archive);
        OwnedGameCopy ownedGameCopy = validatorFor(
                inspection.getNormalizedManifest().getSha256()).validate(archive);

        String wireValue = ownedGameCopy.getCompatibility().toWireValue();
        assertEquals(NormalizedOwnedGameManifest.FORMAT + ":"
                + inspection.getNormalizedManifest().getSha256(), wireValue);
        assertFalse(wireValue.contains(archive.getAbsolutePath()));
        assertFalse(wireValue.contains(inspection.getSha256()));
        assertFalse(wireValue.contains("fake game data"));
    }

    private Map<String, String> requiredEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("data/entities.dat", "fake entities");
        entries.put("data/game.dat", "fake game data");
        entries.put("data/items.dat", "fake items");
        entries.put("data/monsters.dat", "fake monsters");
        entries.put("data/player.dat", "fake player");
        entries.put("audio/ui/ui_equip_item.mp3", "fake audio");
        entries.put("textures/fake.png", "fake png");
        return entries;
    }

    private File createArchive(Map<String, String> entries) throws IOException {
        return createArchive(entries, 6, 1500000000L + archiveCounter);
    }

    private File createArchive(Map<String, String> entries, int compressionLevel, long timestamp)
            throws IOException {
        File archiveDirectory = temporaryFolder.newFolder("archive-" + archiveCounter++);
        File archive = new File(archiveDirectory, "delver.jar");
        try(ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.setLevel(compressionLevel);
            for(Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(timestamp);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private OwnedGameCopyValidator emptyValidator() {
        return new OwnedGameCopyValidator(new ApprovedOwnedGameCopyRegistry(
                Collections.<ApprovedOwnedGameCopyVariant>emptyList()));
    }

    private OwnedGameCopyValidator validatorFor(String normalizedManifestSha256) {
        ApprovedOwnedGameCopyVariant variant = new ApprovedOwnedGameCopyVariant(
                "test-v108", "Test storefront", "v1.08", normalizedManifestSha256);
        return new OwnedGameCopyValidator(new ApprovedOwnedGameCopyRegistry(
                Collections.singletonList(variant)));
    }
}
