package com.interrupt.dungeoneer.multiplayer.lobby;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class AtomicProperties {
    private AtomicProperties() { }

    static Properties load(File file, String label) {
        Properties properties = new Properties();
        try(InputStream input = new FileInputStream(file)) {
            properties.load(input);
            return properties;
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not read " + label + ": " + file, ex);
        }
    }

    static void store(File file, Properties properties, String comment) {
        File parent = file.getParentFile();
        if(parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Could not create private profile directory: " + parent);
        }

        File temporary;
        try {
            temporary = File.createTempFile(file.getName(), ".tmp", parent);
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not create temporary profile file for: " + file, ex);
        }

        try {
            try(OutputStream output = new FileOutputStream(temporary)) {
                properties.store(output, comment);
                output.flush();
            }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch(AtomicMoveNotSupportedException ex) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not save private profile file: " + file, ex);
        }
        finally {
            if(temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
        restrictToOwner(file);
    }

    static void restrictToOwner(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }
}
