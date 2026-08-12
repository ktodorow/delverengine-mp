package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.owned.KnownV108OwnedGameCopies;
import com.interrupt.dungeoneer.owned.OwnedGameCopy;
import com.interrupt.dungeoneer.owned.OwnedGameCopyInspection;
import com.interrupt.dungeoneer.owned.OwnedGameCopyLocator;
import com.interrupt.dungeoneer.owned.OwnedGameCopyMount;
import com.interrupt.dungeoneer.owned.OwnedGameCopySelectionStore;
import com.interrupt.dungeoneer.owned.OwnedGameCopyValidationException;
import com.interrupt.dungeoneer.owned.OwnedGameCopyValidator;
import com.interrupt.utils.OSUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class OwnedGameCopyLauncher {
    private OwnedGameCopyLauncher() { }

    static void inspect(DesktopLaunchOptions options) throws OwnedGameCopyValidationException {
        File archive = options.ownedCopy;
        if(archive == null) {
            List<File> detected = OSUtils.isWindows()
                    ? OwnedGameCopyLocator.findWindowsCandidates()
                    : new ArrayList<File>();
            if(!detected.isEmpty()) archive = detected.get(0);
        }
        if(archive == null) archive = OwnedGameCopyBrowser.browse(null);

        OwnedGameCopyValidator validator = KnownV108OwnedGameCopies.validator();
        OwnedGameCopyInspection inspection = validator.inspect(archive);
        System.out.println("Owned Game Copy: " + inspection.getArchive());
        System.out.println("SHA-256: " + inspection.getSha256());
        System.out.println("Mountable asset entries: " + inspection.getMountableAssets().size());
        System.out.println("Certification: "
                + (validator.isApprovedFingerprint(inspection.getSha256()) ? "approved exact v1.08" : "unknown"));
        System.out.println("No archive data was copied, mounted, or transmitted.");
        if(!validator.isApprovedFingerprint(inspection.getSha256())) {
            System.out.println("Share only SHA-256 and storefront/version details for certification; never share delver.jar.");
        }
    }

    static OwnedGameCopy validateAndMount(DesktopLaunchOptions options)
            throws OwnedGameCopyValidationException {
        OwnedGameCopyValidator validator = KnownV108OwnedGameCopies.validator();

        if(options.ownedCopy != null) return validateMountAndRemember(validator, options.ownedCopy);

        if(!options.browseOwnedCopy) {
            Set<File> candidates = new LinkedHashSet<File>();
            File storedSelection = OwnedGameCopySelectionStore.load();
            if(storedSelection != null && storedSelection.isFile()) candidates.add(storedSelection);
            if(OSUtils.isWindows()) candidates.addAll(OwnedGameCopyLocator.findWindowsCandidates());

            List<String> rejectedCandidates = new ArrayList<String>();
            for(File candidate : candidates) {
                try {
                    return validateMountAndRemember(validator, candidate);
                }
                catch(OwnedGameCopyValidationException ex) {
                    rejectedCandidates.add(candidate + ": " + ex.getMessage());
                }
            }

            File browsedArchive = OwnedGameCopyBrowser.browse(storedSelection);
            if(browsedArchive != null) return validateMountAndRemember(validator, browsedArchive);
            if(!rejectedCandidates.isEmpty()) {
                throw new OwnedGameCopyValidationException(
                        "Detected Delver archives were rejected: " + rejectedCandidates);
            }
        }
        else {
            File browsedArchive = OwnedGameCopyBrowser.browse(OwnedGameCopySelectionStore.load());
            if(browsedArchive != null) return validateMountAndRemember(validator, browsedArchive);
        }

        throw new OwnedGameCopyValidationException(
                "No delver.jar selected. Install Delver, use Browse, or pass --owned-copy=\"C:\\path\\to\\delver.jar\".");
    }

    private static OwnedGameCopy validateMountAndRemember(OwnedGameCopyValidator validator, File archive)
            throws OwnedGameCopyValidationException {
        OwnedGameCopy ownedGameCopy = validator.validate(archive);
        OwnedGameCopyMount.mount(ownedGameCopy);
        try {
            OwnedGameCopySelectionStore.save(ownedGameCopy);
        }
        catch(OwnedGameCopyValidationException ex) {
            OwnedGameCopyMount.unmount();
            throw ex;
        }
        return ownedGameCopy;
    }
}
