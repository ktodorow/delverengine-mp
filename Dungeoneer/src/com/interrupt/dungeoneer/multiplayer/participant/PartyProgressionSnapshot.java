package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.game.Progression;
import com.badlogic.gdx.utils.ArrayMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Detached campaign facts. No Slot inventory, gold, upgrades or personal knowledge. */
public final class PartyProgressionSnapshot {
    public static final int MAX_ENTRIES = 1024;
    public static final int MAX_KEY_BYTES = 512;
    public static final int MAX_VALUE_BYTES = 2048;
    public static final int MAX_BYTES = 256 * 1024;
    public final long revision;
    public final Map<String, String> persistent;
    public final Map<String, String> untilDeath;
    public final Map<String, String> messages;
    public final Map<String, String> uniqueItems;
    public final Map<String, String> uniqueTiles;
    public final Map<String, String> areas;
    public final boolean tutorialCompleted;
    public final boolean victory;
    public final int secretsFound;

    public PartyProgressionSnapshot(long revision, Map<String, String> persistent,
            Map<String, String> untilDeath, Map<String, String> messages,
            Map<String, String> uniqueItems, Map<String, String> uniqueTiles,
            Map<String, String> areas, boolean tutorialCompleted, boolean victory) {
        this(revision, persistent, untilDeath, messages, uniqueItems, uniqueTiles, areas,
                tutorialCompleted, victory, 0);
    }

    public PartyProgressionSnapshot(long revision, Map<String, String> persistent,
            Map<String, String> untilDeath, Map<String, String> messages,
            Map<String, String> uniqueItems, Map<String, String> uniqueTiles,
            Map<String, String> areas, boolean tutorialCompleted, boolean victory, int secretsFound) {
        if(secretsFound < 0 || secretsFound > 1000000) throw new IllegalArgumentException("Invalid Party secret count.");
        this.secretsFound = secretsFound;
        if(revision < 0) throw new IllegalArgumentException("Invalid Party Progression revision.");
        this.revision = revision;
        this.persistent = copy(persistent);
        this.untilDeath = copy(untilDeath);
        this.messages = copy(messages);
        this.uniqueItems = copy(uniqueItems);
        this.uniqueTiles = copy(uniqueTiles);
        this.areas = copy(areas);
        this.tutorialCompleted = tutorialCompleted;
        this.victory = victory;
        int bytes = 14 + 6 * 4;
        for(Map<String, String> map : maps()) for(Map.Entry<String, String> entry : map.entrySet()) {
            bytes += 4 + entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if(bytes > MAX_BYTES) throw new IllegalArgumentException("Party Progression exceeds byte bound.");
        for(String value : this.messages.values()) {
            try { if(Integer.parseInt(value) < 0) throw new NumberFormatException(); }
            catch(NumberFormatException invalid) { throw new IllegalArgumentException("Invalid dialogue history.", invalid); }
        }
    }

    public static PartyProgressionSnapshot empty() {
        Map<String, String> empty = Collections.emptyMap();
        return new PartyProgressionSnapshot(0L, empty, empty, empty, empty, empty, empty, false, false);
    }

    public static PartyProgressionSnapshot capture(Progression nativeState, long revision) {
        Map<String, String> messages = new LinkedHashMap<String, String>();
        for(Map.Entry<String, Integer> message : nativeState.messagesSeen.entrySet()) {
            messages.put(message.getKey(), Integer.toString(message.getValue()));
        }
        return new PartyProgressionSnapshot(revision, map(nativeState.progressionTriggers),
                map(nativeState.untilDeathProgressionTriggers), messages,
                names(nativeState.uniqueItemsSpawned), names(nativeState.uniqueTilesSeen),
                names(nativeState.dungeonAreasSeen), nativeState.sawTutorial, nativeState.won, nativeState.partySecretsFound);
    }

    /** Restore shared facts before native initialization or observer presentation. */
    public void applyTo(Progression nativeState) {
        nativeState.progressionTriggers.clear();
        for(Map.Entry<String, String> entry : persistent.entrySet()) nativeState.progressionTriggers.put(entry.getKey(), entry.getValue());
        nativeState.untilDeathProgressionTriggers.clear();
        for(Map.Entry<String, String> entry : untilDeath.entrySet()) nativeState.untilDeathProgressionTriggers.put(entry.getKey(), entry.getValue());
        nativeState.messagesSeen.clear();
        for(Map.Entry<String, String> entry : messages.entrySet()) nativeState.messagesSeen.put(entry.getKey(), Integer.parseInt(entry.getValue()));
        nativeState.uniqueItemsSpawned.clear();
        for(String name : uniqueItems.keySet()) nativeState.uniqueItemsSpawned.add(name);
        nativeState.uniqueTilesSeen.clear();
        for(String name : uniqueTiles.keySet()) nativeState.uniqueTilesSeen.add(name);
        nativeState.dungeonAreasSeen.clear();
        for(String name : areas.keySet()) nativeState.dungeonAreasSeen.add(name);
        nativeState.sawTutorial = tutorialCompleted;
        nativeState.won = victory;
        nativeState.partySecretsFound = secretsFound;
    }

    public boolean sameFacts(PartyProgressionSnapshot other) {
        return other != null && persistent.equals(other.persistent) && untilDeath.equals(other.untilDeath)
                && messages.equals(other.messages) && uniqueItems.equals(other.uniqueItems)
                && uniqueTiles.equals(other.uniqueTiles) && areas.equals(other.areas)
                && tutorialCompleted == other.tutorialCompleted && victory == other.victory
                && secretsFound == other.secretsFound;
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeLong(revision);
        out.writeBoolean(tutorialCompleted);
        out.writeBoolean(victory);
        for(Map<String, String> map : maps()) {
            out.writeInt(map.size());
            for(Map.Entry<String, String> entry : map.entrySet()) {
                writeString(out, entry.getKey());
                writeString(out, entry.getValue());
            }
        }
        out.writeInt(secretsFound);
    }

    public static PartyProgressionSnapshot readFrom(DataInputStream in) throws IOException {
        long revision = in.readLong();
        boolean tutorial = in.readBoolean(), victory = in.readBoolean();
        return new PartyProgressionSnapshot(revision, readMap(in), readMap(in), readMap(in),
                readMap(in), readMap(in), readMap(in), tutorial, victory, in.readInt());
    }

    private static Map<String, String> readMap(DataInputStream in) throws IOException {
        int count = in.readInt();
        if(count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Party Progression count outside bounds.");
        Map<String, String> map = new LinkedHashMap<String, String>();
        for(int index = 0; index < count; index++) {
            String key = readString(in, MAX_KEY_BYTES), value = readString(in, MAX_VALUE_BYTES);
            if(map.put(key, value) != null) throw new IllegalArgumentException("Duplicate Party Progression key.");
        }
        return map;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in, int bound) throws IOException {
        int size = in.readUnsignedShort();
        if(size > bound) throw new IllegalArgumentException("Party Progression string outside bounds.");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
        catch(java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid Party Progression UTF-8.", invalid); }
    }

    private static Map<String, String> copy(Map<String, String> source) {
        if(source == null || source.size() > MAX_ENTRIES) throw new IllegalArgumentException("Party Progression count outside bounds.");
        for(Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if(key == null || key.trim().isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES
                    || value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("Invalid Party Progression entry.");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(source));
    }

    private static Map<String, String> map(ArrayMap<String, String> source) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for(int index = 0; index < source.size; index++) map.put(source.getKeyAt(index), source.getValueAt(index));
        return map;
    }

    private static Map<String, String> names(com.badlogic.gdx.utils.Array<String> source) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for(String name : source) map.put(name, "");
        return map;
    }

    private java.util.List<Map<String, String>> maps() {
        return java.util.Arrays.asList(persistent, untilDeath, messages, uniqueItems, uniqueTiles, areas);
    }
}
