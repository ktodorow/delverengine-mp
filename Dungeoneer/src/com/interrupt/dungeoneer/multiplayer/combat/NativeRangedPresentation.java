package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded live-only Bow release bound to accepted source and physical item. */
public final class NativeRangedPresentation {
    public static final int MAX_BYTES = 128;

    public final String sourceId;
    public final long itemId;
    public final float x, y, z;
    private final byte[] payload;

    private NativeRangedPresentation(byte[] payload) throws IOException {
        this.payload = payload.clone();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        sourceId = input.readUTF(); itemId = input.readLong();
        x = number(input); y = number(input); z = number(input);
        if(input.available() != 0) throw new IOException("Trailing native ranged presentation data.");
        if(sourceId.isEmpty() || sourceId.getBytes(StandardCharsets.UTF_8).length
                > com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES
                || itemId <= 0L) {
            throw new IOException("Native ranged presentation is outside protocol bounds.");
        }
    }

    public static NativeRangedPresentation capture(String sourceId, long itemId,
            Vector3 position) {
        if(position == null) {
            throw new IllegalArgumentException("Native ranged presentation is incomplete.");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(sourceId == null ? "" : sourceId); output.writeLong(itemId);
            output.writeFloat(position.x); output.writeFloat(position.y); output.writeFloat(position.z);
            return decode(bytes.toByteArray());
        }
        catch(IOException invalid) {
            throw new IllegalArgumentException("Invalid native ranged presentation.", invalid);
        }
    }

    public static NativeRangedPresentation decode(byte[] bytes) throws IOException {
        if(bytes == null || bytes.length > MAX_BYTES) {
            throw new IOException("Native ranged presentation exceeds bound.");
        }
        return new NativeRangedPresentation(bytes);
    }

    public byte[] bytes() { return payload.clone(); }

    private static float number(DataInputStream input) throws IOException {
        float value = input.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IOException("Nonfinite native ranged presentation value.");
        }
        return value;
    }
}
