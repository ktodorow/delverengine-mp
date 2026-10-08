package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import java.io.*;
import java.util.Base64;

/** Bounded scalar recipe for native loading. Content and engine objects remain local. */
public final class NativeFloorRecipe {
    public static final int MAX_BYTES = 4096;
    public final int campaignIndex, characterLevel;
    private String file, theme, name, objective, music, ambient;
    private boolean generated, spawnMonsters;
    public boolean isPrepared() { return prepared; }
    private boolean prepared;
    private float fogStart, fogEnd;
    private int fog, tileLight, skyLight;
    private final java.util.List<String> areas = new java.util.ArrayList<>(), tiles = new java.util.ArrayList<>(), items = new java.util.ArrayList<>();

    private NativeFloorRecipe(int campaignIndex, int characterLevel) {
        if(campaignIndex < -1 || campaignIndex > 1023 || characterLevel < 1 || characterLevel > 1000)
            throw new IllegalArgumentException("Native recipe is outside bounds.");
        this.campaignIndex = campaignIndex; this.characterLevel = characterLevel;
    }
    public static NativeFloorRecipe campaign(int index, int characterLevel) {
        if(index < 0 || index >= Game.buildLevelLayout().size)
            throw new IllegalArgumentException("Native campaign floor is outside layout.");
        NativeFloorRecipe recipe = new NativeFloorRecipe(index, characterLevel);
        recipe.captureConstruction(); return recipe;
    }
    public static NativeFloorRecipe warp(TriggeredWarp warp, int characterLevel) {
        NativeFloorRecipe recipe = new NativeFloorRecipe(-1, characterLevel);
        recipe.file = warp.levelToLoad; recipe.theme = warp.levelTheme; recipe.name = warp.levelName;
        recipe.objective = warp.objectivePrefabToSpawn; recipe.music = warp.music; recipe.ambient = warp.ambientSound;
        recipe.generated = warp.generated; recipe.spawnMonsters = warp.spawnMonsters;
        recipe.fogStart = warp.fogStart; recipe.fogEnd = warp.fogEnd;
        recipe.fog = Color.rgba8888(warp.fogColor); recipe.tileLight = Color.rgba8888(warp.ambientLightColor);
        recipe.skyLight = Color.rgba8888(warp.skyLightColor);
        recipe.validateWarp(); recipe.captureConstruction();
        return recipe;
    }
    public static NativeFloorRecipe fromDefinition(Level level, int characterLevel) {
        NativeFloorRecipe recipe = new NativeFloorRecipe(-1, characterLevel);
        recipe.prepared = true;
        recipe.file = level.levelFileName; recipe.theme = level.theme; recipe.name = level.levelName;
        recipe.objective = level.objectivePrefab; recipe.music = level.music; recipe.ambient = level.ambientSound;
        recipe.generated = level.generated; recipe.spawnMonsters = level.spawnMonsters;
        recipe.fogStart = level.fogStart; recipe.fogEnd = level.fogEnd;
        recipe.fog = Color.rgba8888(level.fogColor); recipe.tileLight = Color.rgba8888(level.ambientColor);
        recipe.skyLight = Color.rgba8888(level.skyLightColor);
        recipe.validateWarp(); recipe.captureConstruction(); return recipe;
    }
    private void captureConstruction() {
        if(Game.instance == null || Game.instance.progression == null) return;
        for(String value : Game.instance.progression.dungeonAreasSeen) areas.add(value);
        for(String value : Game.instance.progression.uniqueTilesSeen) tiles.add(value);
        for(String value : Game.instance.progression.uniqueItemsSpawned) items.add(value);
    }
    public com.interrupt.dungeoneer.game.Progression constructionProgression(com.interrupt.dungeoneer.game.Progression current) {
        com.interrupt.dungeoneer.game.Progression copy = new com.interrupt.dungeoneer.game.Progression();
        if(current != null) com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(current, 0L).applyTo(copy);
        copy.dungeonAreasSeen.clear(); copy.uniqueTilesSeen.clear(); copy.uniqueItemsSpawned.clear();
        for(String value : areas) copy.dungeonAreasSeen.add(value);
        for(String value : tiles) copy.uniqueTilesSeen.add(value);
        for(String value : items) copy.uniqueItemsSpawned.add(value);
        return copy;
    }
    public static void mergeConstruction(com.interrupt.dungeoneer.game.Progression current,
            com.interrupt.dungeoneer.game.Progression built) {
        if(current == null || built == null) return;
        for(String value : built.dungeonAreasSeen) if(!current.dungeonAreasSeen.contains(value, false)) current.dungeonAreasSeen.add(value);
        for(String value : built.uniqueTilesSeen) if(!current.uniqueTilesSeen.contains(value, false)) current.uniqueTilesSeen.add(value);
        for(String value : built.uniqueItemsSpawned) if(!current.uniqueItemsSpawned.contains(value, false)) current.uniqueItemsSpawned.add(value);
    }
    public Level definition() {
        if(campaignIndex >= 0) {
            com.badlogic.gdx.utils.Array<Level> layout = Game.buildLevelLayout();
            if(campaignIndex >= layout.size) throw new IllegalArgumentException("Owned campaign layout changed.");
            return layout.get(campaignIndex);
        }
        validateWarp();
        if(prepared) {
            Level raw = com.interrupt.dungeoneer.serializers.KryoSerializer.loadLevel(Game.getInternal(file));
            if(raw == null) throw new IllegalStateException("Owned prepared floor is unavailable.");
            raw.levelFileName = file; raw.theme = theme;
            return raw;
        }
        Level level = new Level();
        level.generated = generated; level.spawnMonsters = spawnMonsters;
        level.levelFileName = file; level.theme = theme; level.levelName = name;
        level.objectivePrefab = objective; level.music = music; level.ambientSound = ambient;
        level.fogStart = fogStart; level.fogEnd = fogEnd; level.viewDistance = fogEnd + 2f;
        level.fogColor = new Color(fog); level.setWarpAmbientTileLighting(new Color(tileLight)); level.skyLightColor = new Color(skyLight);
        level.makeStairsDown = false;
        return level;
    }
    public Level build(long seed) {
        Level level = definition();
        level.multiplayerNativeRecipe = encode();
        SharedFloorBuild shared = new SharedFloorBuild(seed);
        level.sharedFloorBuild = shared;
        Game game = Game.instance;
        int previousLevel = game == null || game.player == null ? 1 : game.player.level;
        com.interrupt.dungeoneer.game.Progression previousProgression = game == null ? null : game.progression;
        try {
            if(game != null) game.progression = constructionProgression(previousProgression);
            if(game != null && game.player != null) game.player.level = characterLevel;
            level.load();
            if(game != null) {
                com.interrupt.dungeoneer.game.Progression committed = new com.interrupt.dungeoneer.game.Progression();
                if(previousProgression != null)
                    com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(previousProgression, 0L).applyTo(committed);
                mergeConstruction(committed, game.progression);
                level.multiplayerBuiltProgression =
                        com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(committed, 0L);
            }
        }
        finally {
            if(game != null) game.progression = previousProgression;
            if(game != null && game.player != null) game.player.level = previousLevel;
        }
        return level;
    }
    public String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(campaignIndex); out.writeInt(characterLevel); out.writeBoolean(prepared);
            writeNames(out, areas); writeNames(out, tiles); writeNames(out, items);
            if(campaignIndex < 0) {
                write(out, file); write(out, theme); write(out, name); write(out, objective); write(out, music); write(out, ambient);
                out.writeBoolean(generated); out.writeBoolean(spawnMonsters); out.writeFloat(fogStart); out.writeFloat(fogEnd);
                out.writeInt(fog); out.writeInt(tileLight); out.writeInt(skyLight);
            }
            String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
            if(encoded.length() > MAX_BYTES) throw new IllegalArgumentException("Native recipe exceeds bounds.");
            return encoded;
        }
        catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static NativeFloorRecipe decode(String encoded) {
        if(encoded == null || encoded.isEmpty() || encoded.length() > MAX_BYTES)
            throw new IllegalArgumentException("Native recipe exceeds bounds.");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            NativeFloorRecipe recipe = new NativeFloorRecipe(in.readInt(), in.readInt());
            recipe.prepared = in.readBoolean();
            if(recipe.prepared && recipe.campaignIndex >= 0) throw new IllegalArgumentException("Campaign recipe cannot use prepared construction.");
            readNames(in, recipe.areas); readNames(in, recipe.tiles); readNames(in, recipe.items);
            if(recipe.campaignIndex < 0) {
                recipe.file = read(in); recipe.theme = read(in); recipe.name = read(in); recipe.objective = read(in);
                recipe.music = read(in); recipe.ambient = read(in); recipe.generated = in.readBoolean();
                recipe.spawnMonsters = in.readBoolean(); recipe.fogStart = in.readFloat(); recipe.fogEnd = in.readFloat();
                recipe.fog = in.readInt(); recipe.tileLight = in.readInt(); recipe.skyLight = in.readInt();
                recipe.validateWarp();
            }
            if(in.available() != 0) throw new IllegalArgumentException("Native recipe has trailing data.");
            return recipe;
        }
        catch(IOException invalid) { throw new IllegalArgumentException("Native recipe is truncated.", invalid); }
    }
    private static void writeNames(DataOutputStream out, java.util.List<String> names) throws IOException {
        if(names.size() > 128) throw new IllegalArgumentException("Native construction history exceeds bounds.");
        out.writeInt(names.size()); for(String name : names) write(out, name);
    }
    private static void readNames(DataInputStream in, java.util.List<String> names) throws IOException {
        int count = in.readInt(); if(count < 0 || count > 128) throw new IllegalArgumentException("Native construction history exceeds bounds.");
        for(int index = 0; index < count; index++) {
            String value = read(in); if(value == null) throw new IllegalArgumentException("Native history name is missing.");
            names.add(value);
        }
    }
    private void validateWarp() {
        if(!generated && (file == null || !file.startsWith("levels/") || file.contains("..") || file.contains("\\"))
                || theme == null || !theme.matches("[A-Za-z0-9_-]{1,64}")
                || !Float.isFinite(fogStart) || !Float.isFinite(fogEnd) || fogStart < 0f || fogEnd < fogStart || fogEnd > 10000f)
            throw new IllegalArgumentException("Invalid native warp recipe.");
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        if(value != null && value.length() > 256) throw new IllegalArgumentException("Native recipe text exceeds bounds.");
        out.writeBoolean(value != null); if(value != null) out.writeUTF(value);
    }
    private static String read(DataInputStream in) throws IOException {
        String value = in.readBoolean() ? in.readUTF() : null;
        if(value != null && value.length() > 256) throw new IllegalArgumentException("Native recipe text exceeds bounds.");
        return value;
    }
}
