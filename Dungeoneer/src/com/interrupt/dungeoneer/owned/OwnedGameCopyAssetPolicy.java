package com.interrupt.dungeoneer.owned;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class OwnedGameCopyAssetPolicy {
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_SEGMENT_LENGTH = 255;

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "ani", "atlas", "bin", "dat", "fnt", "frag", "g3db", "g3dj", "ink", "jpeg", "jpg",
            "lvl", "md2", "mp3", "mtl", "obj", "ogg", "otf", "png", "skel", "ttf", "txt",
            "vert", "vox", "wav"));

    private static final Set<String> RESTRICTED_FILE_NAMES = new HashSet<String>(Arrays.asList(
            ".ds_store", "desktop.ini", "delver.exe", "delver.jar", "packaged_files.txt",
            "steam_api.dll", "steam_api64.dll", "steam_appid.txt", "thumbs.db"));

    private static final Set<String> RESTRICTED_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "bat", "class", "cmd", "com", "dll", "dylib", "exe", "jar", "jnilib", "ps1",
            "sh", "so"));

    private static final Set<String> RESTRICTED_TOP_LEVEL_DIRECTORIES = new HashSet<String>(Arrays.asList(
            ".git", "__macosx", "com", "java", "javax", "meta-inf", "metadata", "mods", "net",
            "org", "save", "saves", "steam", "steamworks"));

    private OwnedGameCopyAssetPolicy() { }

    static String normalize(String archivePath) throws OwnedGameCopyValidationException {
        if(archivePath == null || archivePath.isEmpty()) return "";
        if(archivePath.length() > MAX_PATH_LENGTH || archivePath.indexOf('\\') >= 0) {
            throw unsafePath(archivePath);
        }
        for(int i = 0; i < archivePath.length(); i++) {
            if(Character.isISOControl(archivePath.charAt(i))) throw unsafePath(archivePath);
        }

        String normalized = archivePath;
        while(normalized.startsWith("./")) normalized = normalized.substring(2);
        if(normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw unsafePath(archivePath);
        }
        while(normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);

        if(normalized.isEmpty()) return "";
        String[] segments = normalized.split("/", -1);
        for(String segment : segments) {
            if(segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.length() > MAX_SEGMENT_LENGTH || segment.indexOf(':') >= 0) {
                throw unsafePath(archivePath);
            }
        }
        return normalized;
    }

    static String normalizeLookup(String assetPath) throws OwnedGameCopyValidationException {
        if(assetPath == null || assetPath.isEmpty()) return "";

        StringBuilder canonicalPath = new StringBuilder(assetPath.length());
        boolean previousWasSeparator = false;
        for(int i = 0; i < assetPath.length(); i++) {
            char character = assetPath.charAt(i);
            boolean isSeparator = character == '/';
            if(!isSeparator || !previousWasSeparator) canonicalPath.append(character);
            previousWasSeparator = isSeparator;
        }
        return normalize(canonicalPath.toString());
    }

    static boolean isMountableAsset(String normalizedPath) {
        if(normalizedPath == null || normalizedPath.isEmpty()) return false;

        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
        String[] segments = lowerPath.split("/");
        if(segments.length > 1 && RESTRICTED_TOP_LEVEL_DIRECTORIES.contains(segments[0])) return false;
        for(String segment : segments) {
            if(segment.startsWith(".")) return false;
        }

        String fileName = segments[segments.length - 1];
        if(RESTRICTED_FILE_NAMES.contains(fileName)) return false;
        if(fileName.startsWith("steam_api") || fileName.startsWith("steamworks4j")
                || fileName.startsWith("libsteam")) return false;

        int extensionStart = fileName.lastIndexOf('.');
        if(extensionStart < 0 || extensionStart == fileName.length() - 1) return false;
        String extension = fileName.substring(extensionStart + 1);
        return !RESTRICTED_EXTENSIONS.contains(extension) && ALLOWED_EXTENSIONS.contains(extension);
    }

    private static OwnedGameCopyValidationException unsafePath(String archivePath) {
        return new OwnedGameCopyValidationException("Owned Game Copy contains unsafe archive path: " + archivePath);
    }
}
