package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.files.FileHandle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public final class OwnedGameCopySelectionStore {
    private static final String SETTINGS_PATH = "settings/owned-game-copy.properties";
    private static final String ARCHIVE_PATH_KEY = "archivePath";

    private OwnedGameCopySelectionStore() { }

    public static File load() {
        if(!MultiplayerProfile.isInitialized()) return null;
        FileHandle settings = MultiplayerProfile.resolveWritableFile(SETTINGS_PATH);
        if(!settings.exists()) return null;

        Properties properties = new Properties();
        try(InputStream input = settings.read()) {
            properties.load(input);
        }
        catch(IOException ex) {
            return null;
        }

        String archivePath = properties.getProperty(ARCHIVE_PATH_KEY);
        if(archivePath == null || archivePath.trim().isEmpty()) return null;
        return new File(archivePath);
    }

    public static void save(OwnedGameCopy ownedGameCopy) throws OwnedGameCopyValidationException {
        if(!MultiplayerProfile.isInitialized()) {
            throw new OwnedGameCopyValidationException("Multiplayer profile is not initialized.");
        }

        Properties properties = new Properties();
        properties.setProperty(ARCHIVE_PATH_KEY, ownedGameCopy.getArchive().getAbsolutePath());
        properties.setProperty("sha256", ownedGameCopy.getSha256());

        FileHandle settings = MultiplayerProfile.resolveWritableFile(SETTINGS_PATH);
        try(OutputStream output = settings.write(false)) {
            properties.store(output, "Delver Multiplayer Owned Game Copy reference");
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException("Could not save Owned Game Copy selection.", ex);
        }
    }
}
