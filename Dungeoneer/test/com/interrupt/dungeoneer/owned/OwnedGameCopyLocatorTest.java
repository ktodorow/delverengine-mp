package com.interrupt.dungeoneer.owned;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OwnedGameCopyLocatorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void detectsStandardSteamInstallation() throws Exception {
        File programFiles = temporaryFolder.newFolder("Program Files (x86)");
        File archive = new File(programFiles, "Steam/steamapps/common/Delver/delver.jar");
        assertTrue(archive.getParentFile().mkdirs());
        assertTrue(archive.createNewFile());
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("ProgramFiles(x86)", programFiles.getAbsolutePath());

        List<File> candidates = OwnedGameCopyLocator.findWindowsCandidates(environment,
                temporaryFolder.getRoot().getAbsolutePath());

        assertEquals(1, candidates.size());
        assertEquals(archive.getAbsoluteFile(), candidates.get(0));
    }
}
