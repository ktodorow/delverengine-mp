package com.interrupt.dungeoneer.multiplayer.knowledge;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Potion.PotionType;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Detached facts belonging to one Campaign Slot, never Party Progression. */
public final class PersonalKnowledge {
    public static final int MAX_FLOORS = 64;
    public static final int MAX_BYTES = MAX_FLOORS * (MapKnowledge.MAX_TILE_BYTES + 528) + 16;
    public final long revision;
    public final int potionMask;
    public final java.util.Map<String, MapKnowledge> maps;

    public PersonalKnowledge(long revision, int potionMask) {
        this(revision, potionMask, java.util.Collections.<String, MapKnowledge>emptyMap());
    }

    public PersonalKnowledge(long revision, int potionMask, java.util.Map<String, MapKnowledge> maps) {
        if(revision < 0 || potionMask < 0 || potionMask >= (1 << PotionType.values().length))
            throw new IllegalArgumentException("Invalid personal knowledge.");
        this.revision = revision; this.potionMask = potionMask;
        if(maps == null || maps.size() > MAX_FLOORS) throw new IllegalArgumentException("Too many personal floors.");
        if(revision == 0 && (potionMask != 0 || !maps.isEmpty()))
            throw new IllegalArgumentException("Personal facts require a revision.");
        for(java.util.Map.Entry<String, MapKnowledge> entry : maps.entrySet()) {
            String floor = entry.getKey();
            if(floor == null || floor.trim().isEmpty() || floor.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256
                    || entry.getValue() == null) throw new IllegalArgumentException("Invalid personal floor.");
        }
        this.maps = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(maps));
    }

    public static PersonalKnowledge empty() { return new PersonalKnowledge(0, 0); }

    public PersonalKnowledge learn(Player owner) {
        int mask = potionMask;
        for(PotionType type : owner.discoveredPotions) mask |= 1 << type.ordinal();
        return mask == potionMask ? this : new PersonalKnowledge(revision + 1, mask, maps);
    }

    public MapKnowledge map(String floor, int width, int height) {
        MapKnowledge known = maps.get(floor);
        return known == null ? MapKnowledge.empty(width, height) : known;
    }

    public PersonalKnowledge learnMap(String floor, MapKnowledge explored) {
        MapKnowledge old = maps.get(floor);
        MapKnowledge next = old == null ? explored : old.merge(explored);
        if(next.equals(old)) return this;
        java.util.Map<String, MapKnowledge> copy = new java.util.LinkedHashMap<>(maps);
        copy.put(floor, next);
        return new PersonalKnowledge(revision + 1, potionMask, copy);
    }

    public void applyTo(Player viewer) {
        viewer.discoveredPotions.clear();
        for(PotionType type : PotionType.values())
            if((potionMask & (1 << type.ordinal())) != 0) viewer.discoveredPotions.add(type);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeLong(revision); out.writeInt(potionMask);
        out.writeInt(maps.size());
        for(java.util.Map.Entry<String, MapKnowledge> entry : maps.entrySet()) {
            out.writeUTF(entry.getKey()); entry.getValue().writeTo(out);
        }
    }

    public static PersonalKnowledge readFrom(DataInputStream in) throws IOException {
        long revision = in.readLong(); int mask = in.readInt(), count = in.readInt();
        if(count < 0 || count > MAX_FLOORS) throw new IllegalArgumentException("Too many personal floors.");
        java.util.Map<String, MapKnowledge> maps = new java.util.LinkedHashMap<>();
        for(int index = 0; index < count; index++) {
            String floor = in.readUTF();
            if(maps.put(floor, MapKnowledge.readFrom(in)) != null) throw new IllegalArgumentException("Duplicate personal floor.");
        }
        return new PersonalKnowledge(revision, mask, maps);
    }
}
