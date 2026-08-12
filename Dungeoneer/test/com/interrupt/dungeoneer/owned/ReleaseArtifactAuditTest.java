package com.interrupt.dungeoneer.owned;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReleaseArtifactAuditTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsArtifactContainingOnlyTrackedOpenSourceAssets() throws Exception {
        File assets = createAssetRoot();
        ReleaseArtifactAudit.verify(createArtifact(trackedEntries()), assets, createTrustedClasses());
    }

    @Test
    public void rejectsUnexpectedCommercialGameData() throws Exception {
        Map<String, String> entries = trackedEntries();
        entries.put("data/owned-campaign.bin", "commercial data");

        assertRejected(createArtifact(entries), createAssetRoot(), "Unexpected game-data entry");

        Map<String, String> excludedExtensionEntries = trackedEntries();
        excludedExtensionEntries.put("splash/commercial-source.psd", "commercial source image");
        assertRejected(createArtifact(excludedExtensionEntries), createAssetRoot(), "Unexpected game-data entry");
    }

    @Test
    public void rejectsOwnedArchiveAndPrivateCacheContent() throws Exception {
        Map<String, String> archiveEntries = trackedEntries();
        archiveEntries.put("private/delver.jar", "commercial archive");
        assertRejected(createArtifact(archiveEntries), createAssetRoot(), "Restricted commercial file");

        Map<String, String> cacheEntries = trackedEntries();
        cacheEntries.put("profile/cache/ab/cached.bin", "private cached asset");
        assertRejected(createArtifact(cacheEntries), createAssetRoot(), "Private profile/cache content");

        Map<String, String> nestedPrivateEntries = trackedEntries();
        nestedPrivateEntries.put("assets/.owned-cache/ab/cached.bin", "nested private cached asset");
        assertRejected(createArtifact(nestedPrivateEntries), createAssetRoot(), "Private profile/cache content");

        Map<String, String> packageCacheEntries = trackedEntries();
        packageCacheEntries.put("com/example/cache/owned.bin", "cache disguised as package resource");
        assertRejected(createArtifact(packageCacheEntries), createAssetRoot(), "Private profile/cache content");
    }

    @Test
    public void rejectsUnexpectedOrChangedCompiledClasses() throws Exception {
        Map<String, String> unexpectedEntries = trackedEntries();
        unexpectedEntries.put("com/interrupt/dungeoneer/CommercialOnly.class", "commercial game code");
        assertRejected(createArtifact(unexpectedEntries), createAssetRoot(), "Unexpected compiled class");

        Map<String, String> changedEntries = trackedEntries();
        changedEntries.put("com/example/Engine.class", "different compiled code");
        assertRejected(createArtifact(changedEntries), createAssetRoot(), "differs from trusted build output");
    }

    @Test
    public void rejectsChangedOrMissingTrackedAssets() throws Exception {
        Map<String, String> changedEntries = trackedEntries();
        changedEntries.put("data/game.dat", "not open source baseline");
        assertRejected(createArtifact(changedEntries), createAssetRoot(), "differs from tracked");

        Map<String, String> missingEntries = trackedEntries();
        missingEntries.remove("textures/fake.png");
        assertRejected(createArtifact(missingEntries), createAssetRoot(), "missing tracked");
    }

    private File createAssetRoot() throws IOException {
        File assets = temporaryFolder.newFolder();
        write(new File(assets, "data/game.dat"), "open source game data");
        write(new File(assets, "textures/fake.png"), "open source texture");
        write(new File(assets, "packaged_files.txt"), "generated metadata");
        write(new File(assets, "save/original.dat"), "excluded save fixture");
        return assets;
    }

    private Map<String, String> trackedEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        entries.put("com/example/Engine.class", "compiled open-source engine");
        entries.put("data/game.dat", "open source game data");
        entries.put("textures/fake.png", "open source texture");
        return entries;
    }

    private File createTrustedClasses() throws IOException {
        File classes = temporaryFolder.newFolder();
        write(new File(classes, "com/example/Engine.class"), "compiled open-source engine");
        return classes;
    }

    private File createArtifact(Map<String, String> entries) throws IOException {
        File artifact = temporaryFolder.newFile();
        try(ZipOutputStream output = new ZipOutputStream(new FileOutputStream(artifact))) {
            for(Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return artifact;
    }

    private void assertRejected(File artifact, File assets, String expectedMessage) throws Exception {
        try {
            ReleaseArtifactAudit.verify(artifact, assets, createTrustedClasses());
            fail("Unsafe release artifact passed audit");
        }
        catch(IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try(FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
