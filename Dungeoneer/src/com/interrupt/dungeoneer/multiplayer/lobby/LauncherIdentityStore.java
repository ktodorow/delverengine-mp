package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.security.SecureRandom;
import java.util.Properties;

/** Creates one private random Launcher Identity, then fails closed if stored data is corrupt. */
public final class LauncherIdentityStore {
    private static final String IDENTITY_PATH = "identities/launcher-identity.properties";
    private static final String FORMAT = "1";

    private LauncherIdentityStore() { }

    public static synchronized LauncherIdentity loadOrCreate() {
        if(!MultiplayerProfile.isInitialized()) {
            throw new IllegalStateException("Multiplayer profile is not initialized.");
        }
        return loadOrCreate(MultiplayerProfile.resolveWritableFile(IDENTITY_PATH).file(),
                new SecureRandom());
    }

    static LauncherIdentity loadOrCreate(File file, SecureRandom random) {
        File parent = file.getParentFile();
        if(parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Could not create Launcher Identity directory: " + parent);
        }
        File lockFile = new File(parent, file.getName() + ".lock");
        try(RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                FileLock ignored = lockAccess.getChannel().lock()) {
            AtomicProperties.restrictToOwner(lockFile);
            if(file.exists()) return load(file);
            LauncherIdentity identity = LauncherIdentity.random(random);
            Properties properties = new Properties();
            properties.setProperty("format", FORMAT);
            properties.setProperty("launcherIdentity", identity.getValue());
            AtomicProperties.store(file, properties,
                    "Delver Multiplayer private Launcher Identity");
            return identity;
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not lock Launcher Identity file: " + file, ex);
        }
    }

    private static LauncherIdentity load(File file) {
        Properties properties = AtomicProperties.load(file, "Launcher Identity");
        if(!FORMAT.equals(properties.getProperty("format"))) {
            throw new IllegalStateException("Launcher Identity file has unsupported format: " + file);
        }
        try {
            return new LauncherIdentity(properties.getProperty("launcherIdentity"));
        }
        catch(IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Launcher Identity file is corrupt; it was not silently replaced: " + file, ex);
        }
    }
}
