package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded live-only Sword presentation bound to accepted source and physical item. */
public final class NativeMeleePresentation {
    public static final int MAX_BYTES = 160;
    public enum Kind { SWING, ENTITY_HIT, WORLD_HIT }

    public final String sourceId;
    public final long itemId, targetObjectId;
    public final Kind kind;
    public final float x, y, z, directionX, directionY, directionZ;
    private final byte[] payload;

    private NativeMeleePresentation(byte[] payload) throws IOException {
        this.payload = payload.clone();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        sourceId = input.readUTF(); itemId = input.readLong();
        int ordinal = input.readUnsignedByte();
        if(ordinal >= Kind.values().length) throw new IOException("Unknown native melee presentation.");
        kind = Kind.values()[ordinal];
        x = number(input); y = number(input); z = number(input);
        directionX = number(input); directionY = number(input); directionZ = number(input);
        targetObjectId = input.readLong();
        if(input.available() != 0) throw new IOException("Trailing native melee presentation data.");
        float directionLength = directionX * directionX + directionY * directionY
                + directionZ * directionZ;
        if(sourceId.isEmpty() || sourceId.getBytes(StandardCharsets.UTF_8).length
                > com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES
                || itemId <= 0L || targetObjectId < 0L
                || directionLength < 0.000001f || directionLength > 4f) {
            throw new IOException("Native melee presentation is outside protocol bounds.");
        }
    }

    public static NativeMeleePresentation capture(String sourceId, long itemId, Kind kind,
            Vector3 position, Vector3 direction) {
        return capture(sourceId, itemId, kind, position, direction, 0L);
    }

    public static NativeMeleePresentation capture(String sourceId, long itemId, Kind kind,
            Vector3 position, Vector3 direction, long targetObjectId) {
        if(kind == null || position == null || direction == null) {
            throw new IllegalArgumentException("Native melee presentation is incomplete.");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(sourceId == null ? "" : sourceId); output.writeLong(itemId);
            output.writeByte(kind.ordinal());
            output.writeFloat(position.x); output.writeFloat(position.y); output.writeFloat(position.z);
            output.writeFloat(direction.x); output.writeFloat(direction.y); output.writeFloat(direction.z);
            output.writeLong(targetObjectId);
            return decode(bytes.toByteArray());
        }
        catch(IOException invalid) {
            throw new IllegalArgumentException("Invalid native melee presentation.", invalid);
        }
    }

    public static NativeMeleePresentation decode(byte[] bytes) throws IOException {
        if(bytes == null || bytes.length > MAX_BYTES) {
            throw new IOException("Native melee presentation exceeds bound.");
        }
        return new NativeMeleePresentation(bytes);
    }

    public byte[] bytes() { return payload.clone(); }

    private static float number(DataInputStream input) throws IOException {
        float value = input.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IOException("Nonfinite native melee presentation value.");
        }
        return value;
    }
}
