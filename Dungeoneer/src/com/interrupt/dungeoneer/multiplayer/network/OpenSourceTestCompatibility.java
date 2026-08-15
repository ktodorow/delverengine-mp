package com.interrupt.dungeoneer.multiplayer.network;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Builds one compatibility identity from the allowlisted packaged open-source assets. */
public final class OpenSourceTestCompatibility {
    public interface AssetSource {
        boolean exists(String path);

        byte[] read(String path) throws IOException;
    }

    private OpenSourceTestCompatibility() { }

    public static DirectConnectCompatibility fromPackagedIndex(String index,
            AssetSource source) throws IOException {
        if(index == null) throw new IllegalArgumentException("Open-source asset index cannot be null.");
        if(source == null) throw new IllegalArgumentException("Open-source asset source cannot be null.");

        Set<String> indexedPaths = new TreeSet<String>();
        for(String line : index.split("\\r?\\n")) {
            String path = line.trim();
            if(path.isEmpty() || path.startsWith("#") || path.equals("./")) continue;
            if(path.startsWith("./")) path = path.substring(2);
            if(path.equals("packaged_files.txt") || path.equals("save")
                    || path.startsWith("save/")) continue;
            if(path.equals(".") || path.equals("..") || path.startsWith("/")
                    || path.contains("\\") || path.contains("../")
                    || path.length() > 512) {
                throw new IllegalArgumentException(
                        "Open-source asset index contains unsafe path: " + path);
            }
            if(indexedPaths.size() >= 8192) {
                throw new IllegalArgumentException(
                        "Open-source asset index contains too many paths.");
            }
            if(!indexedPaths.add(path)) {
                throw new IllegalArgumentException(
                        "Open-source asset index contains duplicate path: " + path);
            }
        }

        Map<String, byte[]> assets = new LinkedHashMap<String, byte[]>();
        for(String path : indexedPaths) {
            if(isDirectory(path, indexedPaths)) continue;
            if(!source.exists(path)) {
                throw new IOException("Open-source asset index references missing file: " + path);
            }
            assets.put(path, source.read(path));
        }
        return DirectConnectCompatibility.forNormalizedOpenSourceAssets(assets);
    }

    private static boolean isDirectory(String path, Set<String> indexedPaths) {
        for(String candidate : indexedPaths) {
            if(candidate.startsWith(path + "/")) return true;
        }
        return false;
    }
}
