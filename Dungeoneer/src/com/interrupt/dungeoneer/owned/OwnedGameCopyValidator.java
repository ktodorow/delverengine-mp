package com.interrupt.dungeoneer.owned;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class OwnedGameCopyValidator {
    private static final Set<String> REQUIRED_ASSETS = new HashSet<String>(Arrays.asList(
            "data/entities.dat",
            "data/game.dat",
            "data/items.dat",
            "data/monsters.dat",
            "data/player.dat",
            "packaged_files.txt"));

    private final Set<String> approvedSha256;

    public OwnedGameCopyValidator(Set<String> approvedSha256) {
        this.approvedSha256 = new HashSet<String>();
        for(String fingerprint : approvedSha256) {
            if(fingerprint != null) this.approvedSha256.add(fingerprint.toLowerCase(Locale.ROOT));
        }
    }

    public OwnedGameCopyInspection inspect(File archive) throws OwnedGameCopyValidationException {
        File canonicalArchive = checkArchiveFile(archive);
        String sha256 = sha256(canonicalArchive);
        Set<String> mountableAssets = inspectEntries(canonicalArchive);

        Set<String> missingAssets = new HashSet<String>(REQUIRED_ASSETS);
        missingAssets.removeAll(mountableAssets);
        if(!missingAssets.isEmpty()) {
            throw new OwnedGameCopyValidationException(
                    "Owned Game Copy is missing required v1.08 data entries: " + missingAssets);
        }

        return new OwnedGameCopyInspection(canonicalArchive, sha256, mountableAssets);
    }

    public OwnedGameCopy validate(File archive) throws OwnedGameCopyValidationException {
        OwnedGameCopyInspection inspection = inspect(archive);
        if(!isApprovedFingerprint(inspection.getSha256())) {
            throw new OwnedGameCopyValidationException(
                    "Unknown Delver archive. Nothing was mounted. SHA-256: " + inspection.getSha256()
                            + ". Share only this fingerprint and storefront/version details for certification; never share delver.jar.");
        }

        File inspectedArchive = inspection.getArchive();
        String confirmationSha256 = sha256(inspectedArchive);
        if(!inspection.getSha256().equals(confirmationSha256)) {
            throw new OwnedGameCopyValidationException("Owned Game Copy changed while it was being validated.");
        }

        return new OwnedGameCopy(inspectedArchive, confirmationSha256, inspectedArchive.length(),
                inspectedArchive.lastModified());
    }

    public boolean isApprovedFingerprint(String sha256) {
        return sha256 != null && approvedSha256.contains(sha256.toLowerCase(Locale.ROOT));
    }

    public static String sha256(File file) throws OwnedGameCopyValidationException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException ex) {
            throw new OwnedGameCopyValidationException("SHA-256 is unavailable in this Java runtime.", ex);
        }

        byte[] buffer = new byte[32 * 1024];
        try(InputStream input = new FileInputStream(file)) {
            int read;
            while((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException("Could not read Owned Game Copy: " + file, ex);
        }

        StringBuilder hex = new StringBuilder(64);
        for(byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private File checkArchiveFile(File archive) throws OwnedGameCopyValidationException {
        if(archive == null) throw new OwnedGameCopyValidationException("No Owned Game Copy was selected.");

        File canonicalArchive;
        try {
            canonicalArchive = archive.getCanonicalFile();
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException("Could not resolve Owned Game Copy path: " + archive, ex);
        }

        if(!canonicalArchive.isFile()) {
            throw new OwnedGameCopyValidationException("Owned Game Copy does not exist: " + canonicalArchive);
        }
        if(!canonicalArchive.getName().equalsIgnoreCase("delver.jar")) {
            throw new OwnedGameCopyValidationException("Select the original delver.jar, not a directory or executable.");
        }
        return canonicalArchive;
    }

    private Set<String> inspectEntries(File archive) throws OwnedGameCopyValidationException {
        Set<String> mountableAssets = new HashSet<String>();
        try(ZipFile zipFile = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String normalizedPath = OwnedGameCopyAssetPolicy.normalize(entry.getName());
                if(entry.isDirectory() || !OwnedGameCopyAssetPolicy.isMountableAsset(normalizedPath)) continue;
                if(!mountableAssets.add(normalizedPath)) {
                    throw new OwnedGameCopyValidationException(
                            "Owned Game Copy contains duplicate asset path: " + normalizedPath);
                }
            }
        }
        catch(ZipException ex) {
            throw new OwnedGameCopyValidationException("Selected delver.jar is not a readable ZIP archive.", ex);
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException("Could not inspect Owned Game Copy: " + archive, ex);
        }
        return mountableAssets;
    }
}
