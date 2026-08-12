package com.interrupt.dungeoneer;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.GraphicsEnvironment;
import java.io.File;

final class OwnedGameCopyBrowser {
    private OwnedGameCopyBrowser() { }

    static File browse(File initialSelection) {
        if(GraphicsEnvironment.isHeadless()) return null;

        File initialDirectory = initialSelection == null ? null
                : (initialSelection.isDirectory() ? initialSelection : initialSelection.getParentFile());
        JFileChooser chooser = initialDirectory == null
                ? new JFileChooser()
                : new JFileChooser(initialDirectory);
        chooser.setDialogTitle("Select your original Delver delver.jar");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Delver archive (delver.jar)", "jar"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile()
                : null;
    }
}
