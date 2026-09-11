package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.spells.Spell;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded live-only native cast feedback, bound to accepted source and item identity. */
public final class NativeSpellPresentation {
    public static final int MAX_BYTES = 800;
    private static final int MAX_SOUND_BYTES = 512;

    public final String sourceId;
    public final long itemId;
    public final boolean zap;
    public final float x, y, z;
    public final String sound;
    public final float volume, range;
    private final byte[] payload;

    private NativeSpellPresentation(byte[] payload) throws IOException {
        this.payload = payload.clone();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        sourceId = input.readUTF();
        itemId = input.readLong();
        zap = input.readBoolean();
        x = number(input); y = number(input); z = number(input);
        sound = input.readUTF();
        volume = number(input); range = number(input);
        if(input.available() != 0) throw new IOException("Trailing native spell presentation data.");
        if(sourceId.isEmpty() || sourceId.getBytes(StandardCharsets.UTF_8).length
                > com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES
                || itemId < 0L || sound.getBytes(StandardCharsets.UTF_8).length > MAX_SOUND_BYTES
                || volume < 0f || volume > 4f || range < 0f || range > 128f) {
            throw new IOException("Native spell presentation is outside protocol bounds.");
        }
    }

    public static NativeSpellPresentation capture(String sourceId, long itemId,
            Spell spell, Vector3 position, boolean zap) {
        if(spell == null || position == null) {
            throw new IllegalArgumentException("Native spell presentation requires spell and position.");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(sourceId == null ? "" : sourceId);
            output.writeLong(itemId); output.writeBoolean(zap);
            output.writeFloat(position.x); output.writeFloat(position.y); output.writeFloat(position.z);
            output.writeUTF(spell.getCastSoundAsset() == null ? "" : spell.getCastSoundAsset());
            output.writeFloat(spell.getCastSoundVolume());
            output.writeFloat(spell.getCastSoundRange());
            return decode(bytes.toByteArray());
        }
        catch(IOException invalid) {
            throw new IllegalArgumentException("Invalid native spell presentation.", invalid);
        }
    }

    public static NativeSpellPresentation decode(byte[] bytes) throws IOException {
        if(bytes == null || bytes.length > MAX_BYTES) {
            throw new IOException("Native spell presentation exceeds bound.");
        }
        return new NativeSpellPresentation(bytes);
    }

    public byte[] bytes() { return payload.clone(); }

    private static float number(DataInputStream input) throws IOException {
        float value = input.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IOException("Nonfinite native spell presentation value.");
        }
        return value;
    }
}
