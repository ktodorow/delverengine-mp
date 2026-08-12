package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.files.FileHandle;
import com.interrupt.utils.OSUtils;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public final class MultiplayerProfile {
    public static final String DIRECTORY_NAME = "Delver Multiplayer";

    private static File root;

    private MultiplayerProfile() { }

    public static synchronized File initializeDefault() {
        return initialize(resolveDefaultRoot(System.getenv(), System.getProperty("user.home"), OSUtils.isWindows()));
    }

    public static synchronized File initialize(File profileRoot) {
        if(profileRoot == null) throw new IllegalArgumentException("Multiplayer profile root cannot be null.");

        try {
            root = profileRoot.getCanonicalFile();
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not resolve multiplayer profile: " + profileRoot, ex);
        }

        ensureDirectory("settings");
        ensureDirectory("cache");
        ensureDirectory("identities");
        ensureDirectory("saves");
        ensureDirectory("logs");
        return root;
    }

    public static synchronized File resolveDefaultRoot(Map<String, String> environment, String userHome,
            boolean windows) {
        if(windows) {
            String localAppData = environment.get("LOCALAPPDATA");
            if(localAppData != null && !localAppData.trim().isEmpty()) {
                return new File(localAppData, DIRECTORY_NAME);
            }

            String userProfile = environment.get("USERPROFILE");
            if(userProfile != null && !userProfile.trim().isEmpty()) {
                return new File(new File(new File(userProfile, "AppData"), "Local"), DIRECTORY_NAME);
            }
        }

        if(userHome == null || userHome.trim().isEmpty()) {
            throw new IllegalStateException("Cannot locate user profile directory.");
        }
        return new File(new File(new File(userHome, ".local"), "share"), DIRECTORY_NAME);
    }

    public static synchronized boolean isInitialized() {
        return root != null;
    }

    public static synchronized File getRoot() {
        if(root == null) throw new IllegalStateException("Multiplayer profile is not initialized.");
        return root;
    }

    public static synchronized FileHandle resolveWritableFile(String relativePath) {
        String normalizedPath = normalizeRelativePath(relativePath);
        return new FileHandle(new File(root, normalizedPath));
    }

    public static synchronized FileHandle resolveLegacyGameFile(String relativePath) {
        if(root == null || relativePath == null) return null;

        String normalizedPath = relativePath.replace('\\', '/');
        while(normalizedPath.startsWith("./")) normalizedPath = normalizedPath.substring(2);

        if(normalizedPath.equals("errorlog.txt")) return resolveWritableFile("logs/errorlog.txt");
        if(normalizedPath.equals("save") || normalizedPath.equals("save/")) {
            return resolveWritableFile("saves");
        }
        if(normalizedPath.startsWith("save/")) {
            return resolveWritableFile("saves/" + normalizedPath.substring("save/".length()));
        }
        if(isProfilePath(normalizedPath)) return resolveWritableFile(normalizedPath);
        return null;
    }

    private static File directory(String name) {
        return new File(root, name);
    }

    private static void ensureDirectory(String name) {
        File directory = directory(name);
        if(!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create multiplayer profile directory: " + directory);
        }
    }

    private static boolean isProfilePath(String path) {
        return path.equals("settings") || path.startsWith("settings/")
                || path.equals("cache") || path.startsWith("cache/")
                || path.equals("identities") || path.startsWith("identities/")
                || path.equals("saves") || path.startsWith("saves/")
                || path.equals("logs") || path.startsWith("logs/");
    }

    private static String normalizeRelativePath(String relativePath) {
        if(root == null) throw new IllegalStateException("Multiplayer profile is not initialized.");
        if(relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Profile path cannot be empty.");
        }

        String normalizedPath = relativePath.replace('\\', '/');
        if(normalizedPath.startsWith("/") || normalizedPath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Profile path must be relative: " + relativePath);
        }

        String[] segments = normalizedPath.split("/", -1);
        for(String segment : segments) {
            if(segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe profile path: " + relativePath);
            }
        }
        return normalizedPath;
    }
}
