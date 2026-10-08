package com.interrupt.dungeoneer.multiplayer.knowledge;

import com.badlogic.gdx.math.Vector2;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.tiles.Tile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;

/** Immutable explored tiles for one stable Campaign floor. */
public final class MapKnowledge {
    public static final int MAX_DIMENSION = 512;
    public static final int MAX_TILE_BYTES = MAX_DIMENSION * MAX_DIMENSION / 8;
    public final int width, height;
    private final BitSet explored;

    public MapKnowledge(int width, int height, BitSet explored) {
        checkDimensions(width, height);
        if(explored == null || explored.length() > width * height)
            throw new IllegalArgumentException("Explored tiles outside floor bounds.");
        this.width = width; this.height = height; this.explored = (BitSet)explored.clone();
    }

    public static MapKnowledge empty(int width, int height) { return new MapKnowledge(width, height, new BitSet()); }

    public static MapKnowledge capture(Level level) {
        BitSet bits = new BitSet();
        for(int y = 0; y < level.height; y++) for(int x = 0; x < level.width; x++) {
            Tile tile = level.getTileOrNull(x, y);
            if(tile != null && tile.seen) bits.set(x + y * level.width);
        }
        return new MapKnowledge(level.width, level.height, bits);
    }

    public boolean isExplored(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && explored.get(x + y * width);
    }

    public MapKnowledge merge(MapKnowledge other) {
        if(width != other.width || height != other.height) throw new IllegalArgumentException("Floor map dimensions changed.");
        BitSet bits = (BitSet)explored.clone(); bits.or(other.explored);
        return new MapKnowledge(width, height, bits);
    }

    public void applyTo(Level level) { applyTo(level, true); }

    /** Temporary native cast contexts leave renderer's dirty queue untouched. */
    public void applyTo(Level level, boolean dirty) {
        if(level.width != width || level.height != height) throw new IllegalArgumentException("Map belongs to another floor shape.");
        for(int y = 0; y < height; y++) for(int x = 0; x < width; x++) {
            Tile tile = level.getTileOrNull(x, y);
            boolean seen = isExplored(x, y);
            if(tile == null || tile.seen == seen) continue;
            if(dirty) {
                if(tile.seen && !seen) level.mapIsDirty = true;
                else level.dirtyMapTiles.add(new Vector2(x, y));
            }
            tile.seen = seen;
        }
    }

    public void writeTo(DataOutputStream out) throws IOException {
        byte[] bytes = explored.toByteArray();
        out.writeInt(width); out.writeInt(height); out.writeInt(bytes.length); out.write(bytes);
    }

    public static MapKnowledge readFrom(DataInputStream in) throws IOException {
        int width = in.readInt(), height = in.readInt(); checkDimensions(width, height);
        int size = in.readInt();
        if(size < 0 || size > (width * height + 7) / 8) throw new IllegalArgumentException("Map bytes outside floor bounds.");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        return new MapKnowledge(width, height, BitSet.valueOf(bytes));
    }

    @Override public boolean equals(Object value) {
        if(!(value instanceof MapKnowledge)) return false;
        MapKnowledge other = (MapKnowledge)value;
        return width == other.width && height == other.height && explored.equals(other.explored);
    }

    @Override public int hashCode() { return 31 * (31 * width + height) + explored.hashCode(); }

    private static void checkDimensions(int width, int height) {
        if(width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IllegalArgumentException("Map dimensions outside bounds.");
    }
}
