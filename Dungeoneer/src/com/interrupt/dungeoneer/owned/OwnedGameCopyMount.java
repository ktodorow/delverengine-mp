package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class OwnedGameCopyMount implements AutoCloseable {
    private static OwnedGameCopyMount active;

    private final OwnedGameCopy ownedGameCopy;
    private final ZipFile zipFile;
    private final Map<String, ZipEntry> entries = new HashMap<String, ZipEntry>();
    private final Set<String> directories = new HashSet<String>();

    private OwnedGameCopyMount(OwnedGameCopy ownedGameCopy) throws OwnedGameCopyValidationException {
        this.ownedGameCopy = ownedGameCopy;
        verifyArchiveUnchanged(ownedGameCopy);

        try {
            zipFile = new ZipFile(ownedGameCopy.getArchive());
        }
        catch(IOException ex) {
            throw new OwnedGameCopyValidationException(
                    "Could not open validated Owned Game Copy: " + ownedGameCopy.getArchive(), ex);
        }

        try {
            indexMountableEntries();
        }
        catch(OwnedGameCopyValidationException ex) {
            try {
                zipFile.close();
            }
            catch(IOException ignored) { }
            throw ex;
        }
    }

    public static synchronized void mount(OwnedGameCopy ownedGameCopy)
            throws OwnedGameCopyValidationException {
        unmount();
        active = new OwnedGameCopyMount(ownedGameCopy);
    }

    public static synchronized FileHandle resolve(String path) {
        return active == null ? null : active.resolveHandle(path);
    }

    public static synchronized boolean isMounted() {
        return active != null;
    }

    public static synchronized File getMountedArchive() {
        return active == null ? null : active.ownedGameCopy.getArchive();
    }

    public static synchronized void unmount() {
        if(active == null) return;
        try {
            active.close();
        }
        catch(Exception ignored) { }
        active = null;
    }

    @Override
    public void close() throws IOException {
        zipFile.close();
    }

    private void indexMountableEntries() throws OwnedGameCopyValidationException {
        directories.add("");
        Enumeration<? extends ZipEntry> archiveEntries = zipFile.entries();
        while(archiveEntries.hasMoreElements()) {
            ZipEntry entry = archiveEntries.nextElement();
            String normalizedPath = OwnedGameCopyAssetPolicy.normalize(entry.getName());
            if(entry.isDirectory() || !OwnedGameCopyAssetPolicy.isMountableAsset(normalizedPath)) continue;

            if(entries.put(normalizedPath, entry) != null) {
                throw new OwnedGameCopyValidationException(
                        "Owned Game Copy contains duplicate asset path: " + normalizedPath);
            }
            addParentDirectories(normalizedPath);
        }
    }

    private void addParentDirectories(String path) {
        int separator = path.lastIndexOf('/');
        while(separator >= 0) {
            directories.add(path.substring(0, separator));
            separator = path.lastIndexOf('/', separator - 1);
        }
    }

    private FileHandle resolveHandle(String path) {
        String normalizedPath;
        try {
            normalizedPath = OwnedGameCopyAssetPolicy.normalizeLookup(path);
        }
        catch(OwnedGameCopyValidationException ex) {
            return null;
        }

        if(!entries.containsKey(normalizedPath) && !directories.contains(normalizedPath)) return null;
        return new OwnedArchiveFileHandle(normalizedPath);
    }

    private InputStream read(String path) {
        ZipEntry entry = entries.get(path);
        if(entry == null) throw new GdxRuntimeException("Cannot read Owned Game Copy directory: " + path);
        try {
            return zipFile.getInputStream(entry);
        }
        catch(IOException ex) {
            throw new GdxRuntimeException("Could not read Owned Game Copy asset: " + path, ex);
        }
    }

    private long length(String path) {
        ZipEntry entry = entries.get(path);
        return entry == null ? 0 : Math.max(0, entry.getSize());
    }

    private FileHandle[] list(String directory) {
        String prefix = directory.isEmpty() ? "" : directory + "/";
        Set<String> childNames = new HashSet<String>();

        for(String entryPath : entries.keySet()) addDirectChild(prefix, entryPath, childNames);
        for(String directoryPath : directories) addDirectChild(prefix, directoryPath, childNames);

        List<String> sortedNames = new ArrayList<String>(childNames);
        Collections.sort(sortedNames);
        FileHandle[] children = new FileHandle[sortedNames.size()];
        for(int i = 0; i < sortedNames.size(); i++) {
            String childPath = prefix + sortedNames.get(i);
            children[i] = new OwnedArchiveFileHandle(childPath);
        }
        return children;
    }

    private void addDirectChild(String prefix, String path, Set<String> childNames) {
        if(path.isEmpty() || !path.startsWith(prefix) || path.equals(prefix)) return;
        String relativePath = path.substring(prefix.length());
        int separator = relativePath.indexOf('/');
        childNames.add(separator < 0 ? relativePath : relativePath.substring(0, separator));
    }

    private static void verifyArchiveUnchanged(OwnedGameCopy ownedGameCopy)
            throws OwnedGameCopyValidationException {
        File archive = ownedGameCopy.getArchive();
        if(!archive.isFile()
                || archive.length() != ownedGameCopy.getLength()
                || archive.lastModified() != ownedGameCopy.getLastModified()
                || !OwnedGameCopyValidator.sha256(archive).equals(ownedGameCopy.getSha256())) {
            throw new OwnedGameCopyValidationException(
                    "Owned Game Copy changed after validation. Select and validate delver.jar again.");
        }
    }

    private final class OwnedArchiveFileHandle extends FileHandle {
        private final String archivePath;

        private OwnedArchiveFileHandle(String archivePath) {
            super(archivePath, Files.FileType.Internal);
            this.archivePath = archivePath;
        }

        @Override
        public InputStream read() {
            return OwnedGameCopyMount.this.read(archivePath);
        }

        @Override
        public ByteBuffer map() {
            return map(FileChannel.MapMode.READ_ONLY);
        }

        @Override
        public ByteBuffer map(FileChannel.MapMode mode) {
            if(mode != FileChannel.MapMode.READ_ONLY) throw readOnly();
            byte[] bytes = readBytes();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        }

        @Override
        public FileHandle[] list() {
            return OwnedGameCopyMount.this.list(archivePath);
        }

        @Override
        public boolean isDirectory() {
            return directories.contains(archivePath);
        }

        @Override
        public FileHandle child(String name) {
            String childPath = archivePath.isEmpty() ? name : archivePath + "/" + name;
            FileHandle child = resolveHandle(childPath);
            return child == null ? new OwnedArchiveFileHandle(childPath) : child;
        }

        @Override
        public FileHandle sibling(String name) {
            if(archivePath.isEmpty()) throw new GdxRuntimeException("Cannot get sibling of archive root.");
            int separator = archivePath.lastIndexOf('/');
            String siblingPath = separator < 0 ? name : archivePath.substring(0, separator + 1) + name;
            FileHandle sibling = resolveHandle(siblingPath);
            return sibling == null ? new OwnedArchiveFileHandle(siblingPath) : sibling;
        }

        @Override
        public FileHandle parent() {
            int separator = archivePath.lastIndexOf('/');
            String parentPath = separator < 0 ? "" : archivePath.substring(0, separator);
            return new OwnedArchiveFileHandle(parentPath);
        }

        @Override
        public boolean exists() {
            return entries.containsKey(archivePath) || directories.contains(archivePath);
        }

        @Override
        public long length() {
            return OwnedGameCopyMount.this.length(archivePath);
        }

        @Override
        public long lastModified() {
            return ownedGameCopy.getLastModified();
        }

        @Override
        public OutputStream write(boolean append) {
            throw readOnly();
        }

        @Override
        public void write(InputStream input, boolean append) {
            throw readOnly();
        }

        @Override
        public Writer writer(boolean append, String charset) {
            throw readOnly();
        }

        @Override
        public void writeString(String string, boolean append) {
            throw readOnly();
        }

        @Override
        public void writeString(String string, boolean append, String charset) {
            throw readOnly();
        }

        @Override
        public void writeBytes(byte[] bytes, boolean append) {
            throw readOnly();
        }

        @Override
        public void writeBytes(byte[] bytes, int offset, int length, boolean append) {
            throw readOnly();
        }

        @Override
        public void mkdirs() {
            throw readOnly();
        }

        @Override
        public boolean delete() {
            throw readOnly();
        }

        @Override
        public boolean deleteDirectory() {
            throw readOnly();
        }

        @Override
        public void emptyDirectory(boolean preserveTree) {
            throw readOnly();
        }

        @Override
        public void copyTo(FileHandle destination) {
            throw readOnly();
        }

        @Override
        public void moveTo(FileHandle destination) {
            throw readOnly();
        }

        private GdxRuntimeException readOnly() {
            return new GdxRuntimeException("Owned Game Copy is mounted read-only: " + archivePath);
        }
    }
}
