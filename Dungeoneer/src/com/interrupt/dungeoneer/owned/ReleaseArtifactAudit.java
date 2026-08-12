package com.interrupt.dungeoneer.owned;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Audits produced artifacts without reading or copying any private Owned Game Copy. */
public final class ReleaseArtifactAudit {
    private static final Set<String> RESTRICTED_FILE_NAMES = new HashSet<String>(Arrays.asList(
            "delver.exe", "delver.jar", "steam_api.dll", "steam_api64.dll", "steam_appid.txt"));

    private static final Set<String> ALWAYS_PRIVATE_DIRECTORIES = new HashSet<String>(Arrays.asList(
            ".owned-cache", "owned-cache"));

    private static final Set<String> PROFILE_DATA_DIRECTORIES = new HashSet<String>(Arrays.asList(
            "cache", "save", "saves"));

    private static final Set<String> OWNED_DATA_DIRECTORIES = new HashSet<String>(Arrays.asList(
            "audio", "data", "generator", "levels", "meshes", "shaders", "splash", "textures", "ui"));

    private static final Set<String> NON_GAME_DATA_DIRECTORIES = new HashSet<String>(Arrays.asList(
            "license", "licenses", "notice", "notices", "third-party", "third_party"));

    private static final Set<String> NON_GAME_DATA_ROOT_FILES = new HashSet<String>(Arrays.asList(
            "changelog.txt", "license.txt", "notice.txt", "readme.txt"));

    private ReleaseArtifactAudit() { }

    public static void main(String[] args) throws Exception {
        if(args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: ReleaseArtifactAudit <release-archive> <tracked-open-source-assets> "
                            + "<trusted-class-origin>...");
        }

        File artifact = new File(args[0]);
        File[] trustedClassOrigins = new File[args.length - 2];
        for(int i = 2; i < args.length; i++) trustedClassOrigins[i - 2] = new File(args[i]);
        verify(artifact, new File(args[1]), trustedClassOrigins);
        System.out.println("Release artifact audit passed: " + artifact.getAbsolutePath());
    }

    public static void verify(File artifact, File trackedAssetRoot, File... trustedClassOrigins)
            throws IOException, OwnedGameCopyValidationException {
        if(artifact == null || !artifact.isFile()) {
            throw new IllegalStateException("Release artifact does not exist: " + artifact);
        }
        if(trackedAssetRoot == null || !trackedAssetRoot.isDirectory()) {
            throw new IllegalStateException("Tracked open-source asset root does not exist: "
                    + trackedAssetRoot);
        }
        if(trustedClassOrigins == null || trustedClassOrigins.length == 0) {
            throw new IllegalStateException("At least one trusted compiled-class origin is required");
        }
        checkRestrictedPath(artifact.getName());

        Map<String, String> expectedAssets = new HashMap<String, String>();
        collectExpectedAssets(trackedAssetRoot.getCanonicalFile(), trackedAssetRoot.getCanonicalFile(),
                expectedAssets);
        Map<String, String> expectedClasses = new HashMap<String, String>();
        for(File trustedClassOrigin : trustedClassOrigins) {
            collectExpectedClasses(trustedClassOrigin, expectedClasses);
        }
        auditZip(artifact, expectedAssets, expectedClasses);
    }

    private static void collectExpectedAssets(File root, File file, Map<String, String> expectedAssets)
            throws IOException, OwnedGameCopyValidationException {
        File canonicalFile = file.getCanonicalFile();
        if(!canonicalFile.toPath().startsWith(root.toPath())) {
            throw new IllegalStateException("Tracked asset escapes asset root: " + file);
        }

        String relativePath = root.toURI().relativize(canonicalFile.toURI()).getPath();
        if(relativePath.equals("packaged_files.txt") || relativePath.equals("save/")
                || relativePath.startsWith("save/")) return;

        if(canonicalFile.isDirectory()) {
            File[] children = canonicalFile.listFiles();
            if(children == null) throw new IOException("Could not list tracked asset directory: " + canonicalFile);
            for(File child : children) collectExpectedAssets(root, child, expectedAssets);
            return;
        }

        if(!canonicalFile.isFile()) return;
        expectedAssets.put(relativePath, sha256(new FileInputStream(canonicalFile)));
    }

    private static void collectExpectedClasses(File origin, Map<String, String> expectedClasses)
            throws IOException, OwnedGameCopyValidationException {
        if(origin == null || !origin.exists()) {
            throw new IllegalStateException("Trusted compiled-class origin does not exist: " + origin);
        }
        checkRestrictedPath(origin.getName());

        if(origin.isDirectory()) {
            File root = origin.getCanonicalFile();
            collectExpectedClassDirectory(root, root, expectedClasses);
            return;
        }
        if(!origin.isFile()) {
            throw new IllegalStateException("Invalid trusted compiled-class origin: " + origin);
        }

        try(ZipFile zipFile = new ZipFile(origin)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = OwnedGameCopyAssetPolicy.normalize(entry.getName());
                if(path.isEmpty() || entry.isDirectory() || !isClassPath(path)) continue;
                addExpectedClass(path, sha256(zipFile.getInputStream(entry)), expectedClasses);
            }
        }
    }

    private static void collectExpectedClassDirectory(File root, File file,
            Map<String, String> expectedClasses) throws IOException, OwnedGameCopyValidationException {
        File canonicalFile = file.getCanonicalFile();
        if(!canonicalFile.toPath().startsWith(root.toPath())) {
            throw new IllegalStateException("Trusted compiled class escapes origin: " + file);
        }

        if(canonicalFile.isDirectory()) {
            File[] children = canonicalFile.listFiles();
            if(children == null) throw new IOException("Could not list trusted class directory: " + canonicalFile);
            for(File child : children) collectExpectedClassDirectory(root, child, expectedClasses);
            return;
        }
        if(!canonicalFile.isFile()) return;

        String path = OwnedGameCopyAssetPolicy.normalize(
                root.toURI().relativize(canonicalFile.toURI()).getPath());
        if(isClassPath(path)) addExpectedClass(path, sha256(new FileInputStream(canonicalFile)), expectedClasses);
    }

    private static void addExpectedClass(String path, String sha256, Map<String, String> expectedClasses) {
        String existingSha256 = expectedClasses.put(path, sha256);
        if(existingSha256 != null && !existingSha256.equals(sha256)) {
            throw new IllegalStateException("Trusted class origins conflict for entry: " + path);
        }
    }

    private static void auditZip(File artifact, Map<String, String> expectedAssets,
            Map<String, String> expectedClasses)
            throws IOException, OwnedGameCopyValidationException {
        Set<String> seenAssets = new HashSet<String>();
        Set<String> seenClasses = new HashSet<String>();
        try(ZipFile zipFile = new ZipFile(artifact)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = OwnedGameCopyAssetPolicy.normalize(entry.getName());
                if(path.isEmpty() || entry.isDirectory()) continue;

                checkRestrictedPath(path);
                if(isClassPath(path)) {
                    String expectedSha256 = expectedClasses.get(path);
                    if(expectedSha256 == null) {
                        throw new IllegalStateException(
                                "Unexpected compiled class found in release artifact: " + path);
                    }
                    if(!seenClasses.add(path)) {
                        throw new IllegalStateException(
                                "Duplicate compiled class found in release artifact: " + path);
                    }
                    String actualSha256 = sha256(zipFile.getInputStream(entry));
                    if(!expectedSha256.equals(actualSha256)) {
                        throw new IllegalStateException(
                                "Release compiled class differs from trusted build output: " + path);
                    }
                    continue;
                }

                String assetPath = path.startsWith("assets/") ? path.substring("assets/".length()) : path;
                if(!isGameDataPath(assetPath, expectedAssets)) continue;

                String expectedSha256 = expectedAssets.get(assetPath);
                if(expectedSha256 == null) {
                    throw new IllegalStateException(
                            "Unexpected game-data entry found in release artifact: " + path);
                }
                if(!seenAssets.add(assetPath)) {
                    throw new IllegalStateException(
                            "Duplicate game-data entry found in release artifact: " + path);
                }

                String actualSha256 = sha256(zipFile.getInputStream(entry));
                if(!expectedSha256.equals(actualSha256)) {
                    throw new IllegalStateException(
                            "Release game-data entry differs from tracked open-source asset: " + path);
                }
            }
        }

        Set<String> missingAssets = new HashSet<String>(expectedAssets.keySet());
        missingAssets.removeAll(seenAssets);
        if(!missingAssets.isEmpty()) {
            throw new IllegalStateException(
                    "Release artifact is missing tracked open-source assets: " + missingAssets);
        }

        Set<String> missingClasses = new HashSet<String>(expectedClasses.keySet());
        missingClasses.removeAll(seenClasses);
        if(!missingClasses.isEmpty()) {
            throw new IllegalStateException(
                    "Release artifact is missing trusted compiled classes: " + missingClasses);
        }
    }

    private static boolean isClassPath(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".class");
    }

    private static boolean isGameDataPath(String path, Map<String, String> expectedAssets) {
        if(expectedAssets.containsKey(path)) return true;

        String lowerPath = path.toLowerCase(Locale.ROOT);
        int separator = lowerPath.indexOf('/');
        if(separator >= 0 && OWNED_DATA_DIRECTORIES.contains(lowerPath.substring(0, separator))) return true;
        if(!OwnedGameCopyAssetPolicy.isMountableAsset(path)) return false;
        if(separator < 0) return !NON_GAME_DATA_ROOT_FILES.contains(lowerPath);
        return !NON_GAME_DATA_DIRECTORIES.contains(lowerPath.substring(0, separator));
    }

    private static void checkRestrictedPath(String path) {
        String lowerPath = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String[] segments = lowerPath.split("/");
        if(segments.length == 0) return;
        boolean trustedCodePath = isClassPath(lowerPath) && isCodeOrDependencyPath(segments[0]);

        String fileName = segments[segments.length - 1];
        if(RESTRICTED_FILE_NAMES.contains(fileName)
                || fileName.startsWith("steam_api") || fileName.startsWith("steamworks4j")
                || fileName.startsWith("libsteam")) {
            throw new IllegalStateException("Restricted commercial file found in release artifact: " + path);
        }

        for(int i = 0; i < segments.length; i++) {
            if(ALWAYS_PRIVATE_DIRECTORIES.contains(segments[i])
                    || (PROFILE_DATA_DIRECTORIES.contains(segments[i])
                    && !trustedCodePath)) {
                throw new IllegalStateException(
                        "Private profile/cache content found in release artifact: " + path);
            }
        }
    }

    private static boolean isCodeOrDependencyPath(String firstSegment) {
        return firstSegment.equals("com") || firstSegment.equals("java") || firstSegment.equals("javax")
                || firstSegment.equals("meta-inf") || firstSegment.equals("net") || firstSegment.equals("org");
    }

    private static String sha256(InputStream input)
            throws IOException, OwnedGameCopyValidationException {
        MessageDigest digest = NormalizedOwnedGameManifest.newSha256();
        byte[] buffer = new byte[32 * 1024];
        try(InputStream closeableInput = input) {
            int read;
            while((read = closeableInput.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return NormalizedOwnedGameManifest.toHex(digest.digest());
    }
}
