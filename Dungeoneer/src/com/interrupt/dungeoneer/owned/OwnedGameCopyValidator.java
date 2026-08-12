package com.interrupt.dungeoneer.owned;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class OwnedGameCopyValidator {
    private static final int MAX_ARCHIVE_ENTRIES = 100000;
    private static final long MAX_MOUNTABLE_ASSET_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_TOTAL_MOUNTABLE_BYTES = 2L * 1024L * 1024L * 1024L;

    private static final Set<String> REQUIRED_ASSETS = new HashSet<String>(Arrays.asList(
            "data/entities.dat",
            "data/game.dat",
            "data/items.dat",
            "data/monsters.dat",
            "data/player.dat"));

    private final ApprovedOwnedGameCopyRegistry approvedVariants;

    public OwnedGameCopyValidator(ApprovedOwnedGameCopyRegistry approvedVariants) {
        if(approvedVariants == null) throw new IllegalArgumentException("Approved variant registry cannot be null.");
        this.approvedVariants = approvedVariants;
    }

    public OwnedGameCopyInspection inspect(File archive) throws OwnedGameCopyValidationException {
        File canonicalArchive = checkArchiveFile(archive);
        String sha256 = sha256(canonicalArchive);
        NormalizedOwnedGameManifest normalizedManifest = inspectEntries(canonicalArchive);

        Set<String> missingAssets = new HashSet<String>(REQUIRED_ASSETS);
        missingAssets.removeAll(normalizedManifest.getAssetPaths());
        if(!missingAssets.isEmpty()) {
            throw new OwnedGameCopyValidationException(
                    "Owned Game Copy is missing required v1.08 data entries: " + missingAssets);
        }

        return new OwnedGameCopyInspection(canonicalArchive, sha256, normalizedManifest);
    }

    public OwnedGameCopy validate(File archive) throws OwnedGameCopyValidationException {
        OwnedGameCopyInspection inspection = inspect(archive);
        ApprovedOwnedGameCopyVariant approvedVariant =
                approvedVariants.find(inspection.getNormalizedManifest());
        if(approvedVariant == null) {
            throw new OwnedGameCopyValidationException(
                    "Uncertified Delver content variant. Nothing was mounted. Normalized manifest SHA-256: "
                            + inspection.getNormalizedManifest().getSha256() + " ("
                            + inspection.getNormalizedManifest().getFormat() + "). Select an approved v1.08 copy, "
                            + "or send only this manifest ID plus storefront and displayed game version for review. "
                            + "Never share delver.jar, extracted assets, or private cache content.");
        }

        File inspectedArchive = inspection.getArchive();
        String confirmationSha256 = sha256(inspectedArchive);
        if(!inspection.getSha256().equals(confirmationSha256)) {
            throw new OwnedGameCopyValidationException("Owned Game Copy changed while it was being validated.");
        }

        return new OwnedGameCopy(inspectedArchive, confirmationSha256, inspectedArchive.length(),
                inspectedArchive.lastModified(), approvedVariant, inspection.getNormalizedManifest());
    }

    public ApprovedOwnedGameCopyVariant findApprovedVariant(OwnedGameCopyInspection inspection) {
        return inspection == null ? null : approvedVariants.find(inspection.getNormalizedManifest());
    }

    public boolean isApprovedManifest(String normalizedManifestSha256) {
        return approvedVariants.find(normalizedManifestSha256) != null;
    }

    public static String sha256(File file) throws OwnedGameCopyValidationException {
        MessageDigest digest = NormalizedOwnedGameManifest.newSha256();

        byte[] buffer = new byte[32 * 1024];
        try(InputStream input = new FileInputStream(file)) {
            int read;
            while((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException("Could not read Owned Game Copy: " + file, ex);
        }

        return NormalizedOwnedGameManifest.toHex(digest.digest());
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

    private NormalizedOwnedGameManifest inspectEntries(File archive)
            throws OwnedGameCopyValidationException {
        Set<String> mountableAssets = new HashSet<String>();
        List<NormalizedOwnedGameManifest.AssetDigest> assetDigests =
                new ArrayList<NormalizedOwnedGameManifest.AssetDigest>();
        long totalMountableBytes = 0;
        int archiveEntryCount = 0;
        try(ZipFile zipFile = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                archiveEntryCount++;
                if(archiveEntryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new OwnedGameCopyValidationException(
                            "Owned Game Copy contains too many archive entries.");
                }

                String normalizedPath = OwnedGameCopyAssetPolicy.normalize(entry.getName());
                if(entry.isDirectory() || !OwnedGameCopyAssetPolicy.isMountableAsset(normalizedPath)) continue;
                if(!mountableAssets.add(normalizedPath)) {
                    throw new OwnedGameCopyValidationException(
                            "Owned Game Copy contains duplicate asset path: " + normalizedPath);
                }

                NormalizedOwnedGameManifest.AssetDigest assetDigest =
                        digestAsset(zipFile, entry, normalizedPath);
                if(totalMountableBytes > MAX_TOTAL_MOUNTABLE_BYTES - assetDigest.length) {
                    throw new OwnedGameCopyValidationException(
                            "Owned Game Copy mountable data exceeds the safe size limit.");
                }
                totalMountableBytes += assetDigest.length;
                assetDigests.add(assetDigest);
            }
        }
        catch(ZipException ex) {
            throw new OwnedGameCopyValidationException("Selected delver.jar is not a readable ZIP archive.", ex);
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException("Could not inspect Owned Game Copy: " + archive, ex);
        }
        return NormalizedOwnedGameManifest.create(assetDigests);
    }

    private NormalizedOwnedGameManifest.AssetDigest digestAsset(ZipFile zipFile, ZipEntry entry,
            String normalizedPath) throws IOException, OwnedGameCopyValidationException {
        if(entry.getSize() > MAX_MOUNTABLE_ASSET_BYTES) {
            throw new OwnedGameCopyValidationException(
                    "Owned Game Copy asset exceeds the safe size limit: " + normalizedPath);
        }

        MessageDigest digest = NormalizedOwnedGameManifest.newSha256();
        byte[] buffer = new byte[32 * 1024];
        long length = 0;
        try(InputStream input = zipFile.getInputStream(entry)) {
            int read;
            while((read = input.read(buffer)) != -1) {
                if(length > MAX_MOUNTABLE_ASSET_BYTES - read) {
                    throw new OwnedGameCopyValidationException(
                            "Owned Game Copy asset exceeds the safe size limit: " + normalizedPath);
                }
                digest.update(buffer, 0, read);
                length += read;
            }
        }

        if(entry.getSize() >= 0 && entry.getSize() != length) {
            throw new OwnedGameCopyValidationException(
                    "Owned Game Copy asset size changed while reading: " + normalizedPath);
        }
        return new NormalizedOwnedGameManifest.AssetDigest(normalizedPath, length, digest.digest());
    }
}
