package com.interrupt.dungeoneer.owned;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OwnedGameCopyLocator {
    private static final Pattern VDF_PAIR = Pattern.compile("\\\"([^\\\"]+)\\\"\\s+\\\"([^\\\"]+)\\\"");

    private OwnedGameCopyLocator() { }

    public static List<File> findWindowsCandidates() {
        return findWindowsCandidates(System.getenv(), System.getProperty("user.home"));
    }

    public static List<File> findWindowsCandidates(Map<String, String> environment, String userHome) {
        Set<File> candidates = new LinkedHashSet<File>();
        Set<File> steamRoots = new LinkedHashSet<File>();

        addSteamRoot(steamRoots, environment.get("STEAM_PATH"));
        addSteamRoot(steamRoots, child(environment.get("ProgramFiles(x86)"), "Steam"));
        addSteamRoot(steamRoots, child(environment.get("PROGRAMFILES(X86)"), "Steam"));
        addSteamRoot(steamRoots, child(environment.get("ProgramFiles"), "Steam"));
        addSteamRoot(steamRoots, child(environment.get("PROGRAMFILES"), "Steam"));

        for(File steamRoot : steamRoots) {
            addCandidate(candidates, new File(steamRoot, "steamapps/common/Delver/delver.jar"));
            for(File libraryRoot : readSteamLibraries(new File(steamRoot, "steamapps/libraryfolders.vdf"))) {
                addCandidate(candidates, new File(libraryRoot, "steamapps/common/Delver/delver.jar"));
            }
        }

        addGogCandidates(candidates, environment.get("ProgramFiles(x86)"));
        addGogCandidates(candidates, environment.get("PROGRAMFILES(X86)"));
        addGogCandidates(candidates, environment.get("ProgramFiles"));
        addGogCandidates(candidates, environment.get("PROGRAMFILES"));
        addCandidate(candidates, new File("C:/GOG Games/Delver/delver.jar"));

        if(userHome != null && !userHome.trim().isEmpty()) {
            addCandidate(candidates, new File(userHome, "AppData/Local/itch/apps/Delver/delver.jar"));
        }

        return new ArrayList<File>(candidates);
    }

    static List<File> readSteamLibraries(File libraryFoldersFile) {
        List<File> libraries = new ArrayList<File>();
        if(!libraryFoldersFile.isFile()) return libraries;

        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(libraryFoldersFile), "UTF-8"))) {
            String line;
            while((line = reader.readLine()) != null) {
                Matcher matcher = VDF_PAIR.matcher(line);
                if(!matcher.find()) continue;

                String key = matcher.group(1);
                if(!key.equalsIgnoreCase("path") && !key.matches("\\d+")) continue;

                String value = matcher.group(2).replace("\\\\", "\\");
                File library = new File(value);
                if(!libraries.contains(library)) libraries.add(library);
            }
        }
        catch(IOException ignored) { }
        return libraries;
    }

    private static void addGogCandidates(Set<File> candidates, String programFiles) {
        if(programFiles == null || programFiles.trim().isEmpty()) return;
        addCandidate(candidates, new File(programFiles, "GOG Galaxy/Games/Delver/delver.jar"));
        addCandidate(candidates, new File(programFiles, "Delver/delver.jar"));
    }

    private static void addSteamRoot(Set<File> roots, Object value) {
        if(value == null) return;
        String path = value.toString();
        if(!path.trim().isEmpty()) roots.add(new File(path));
    }

    private static String child(String parent, String child) {
        if(parent == null || parent.trim().isEmpty()) return null;
        return new File(parent, child).getPath();
    }

    private static void addCandidate(Set<File> candidates, File file) {
        if(file != null && file.isFile()) candidates.add(file.getAbsoluteFile());
    }
}
