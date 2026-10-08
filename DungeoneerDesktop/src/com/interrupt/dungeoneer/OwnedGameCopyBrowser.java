package com.interrupt.dungeoneer;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.GraphicsEnvironment;
import java.io.File;

final class OwnedGameCopyBrowser {
    private OwnedGameCopyBrowser() { }

    static File selectForMenu(File initialSelection, String reason) {
        if(GraphicsEnvironment.isHeadless()) return null;
        Object[] actions = initialSelection == null
                ? new Object[] {"Browse...", "Quit"}
                : new Object[] {"Browse...", "Retry", "Quit"};
        int action = JOptionPane.showOptionDialog(null, reason,
                "Delver Multiplayer - Owned Game Copy", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, actions, actions[0]);
        if(action == 0) return browse(initialSelection);
        if(initialSelection != null && action == 1) return initialSelection;
        return null;
    }

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
