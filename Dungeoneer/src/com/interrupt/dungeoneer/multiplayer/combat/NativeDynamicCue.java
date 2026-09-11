package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.items.FusedBomb;
import com.interrupt.dungeoneer.entities.projectiles.BeamProjectile;
import com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile;
import com.interrupt.dungeoneer.entities.projectiles.Missile;
import com.interrupt.dungeoneer.entities.projectiles.Projectile;
import com.interrupt.dungeoneer.game.Level;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Bounded live-only native presentation for dynamic world entities. */
public final class NativeDynamicCue {
    public static final int MAX_BYTES = NativeDynamicState.MAX_BYTES + 48;
    public enum Kind { PROJECTILE_IMPACT, FUSED_BOMB_FIZZLE }

    public final Kind kind;
    public final NativeDynamicState state;
    public final boolean entityHit, secondaryExplosion;
    public final float x, y, z;
    private final byte[] payload;

    private NativeDynamicCue(byte[] payload) throws IOException {
        this.payload = payload.clone();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        int ordinal = input.readUnsignedByte();
        if(ordinal >= Kind.values().length) throw new IOException("Unknown native dynamic cue.");
        kind = Kind.values()[ordinal];
        int stateLength = input.readUnsignedShort();
        if(stateLength < 1 || stateLength > NativeDynamicState.MAX_BYTES)
            throw new IOException("Invalid native dynamic cue state.");
        byte[] stateBytes = new byte[stateLength]; input.readFully(stateBytes);
        state = NativeDynamicState.decode(stateBytes);
        entityHit = input.readBoolean(); secondaryExplosion = input.readBoolean();
        x = number(input); y = number(input); z = number(input);
        if(input.available() != 0) throw new IOException("Trailing native dynamic cue data.");
        Entity entity = state.apply(null);
        if(kind == Kind.PROJECTILE_IMPACT
                && !(entity instanceof Projectile) && !(entity instanceof Missile))
            throw new IOException("Impact cue requires native projectile state.");
        if(kind == Kind.FUSED_BOMB_FIZZLE && !(entity instanceof FusedBomb))
            throw new IOException("Fizzle cue requires native fused-bomb state.");
    }

    public static NativeDynamicCue captureImpact(long id, long itemId, Entity projectile,
            boolean entityHit, float x, float y, float z) {
        if(!(projectile instanceof Projectile) && !(projectile instanceof Missile))
            throw new IllegalArgumentException("Native impact requires a projectile.");
        boolean explosion = projectile instanceof MagicMissileProjectile
                || projectile instanceof BeamProjectile
                    && ((BeamProjectile)projectile).explosion != null;
        return create(Kind.PROJECTILE_IMPACT,
                NativeDynamicState.capture(id, itemId, projectile, true),
                entityHit, explosion, x, y, z);
    }

    public static NativeDynamicCue captureFizzle(long id, long itemId, FusedBomb bomb) {
        return create(Kind.FUSED_BOMB_FIZZLE,
                NativeDynamicState.capture(id, itemId, bomb, true),
                false, false, bomb.x, bomb.y, bomb.z);
    }

    private static NativeDynamicCue create(Kind kind, NativeDynamicState state,
            boolean entityHit, boolean secondaryExplosion, float x, float y, float z) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            byte[] stateBytes = state.bytes();
            output.writeByte(kind.ordinal()); output.writeShort(stateBytes.length);
            output.write(stateBytes); output.writeBoolean(entityHit);
            output.writeBoolean(secondaryExplosion);
            output.writeFloat(x); output.writeFloat(y); output.writeFloat(z);
            return decode(bytes.toByteArray());
        }
        catch(IOException invalid) {
            throw new IllegalArgumentException("Invalid native dynamic cue.", invalid);
        }
    }

    public static NativeDynamicCue decode(byte[] bytes) throws IOException {
        if(bytes == null || bytes.length > MAX_BYTES)
            throw new IOException("Native dynamic cue exceeds bound.");
        return new NativeDynamicCue(bytes);
    }

    public byte[] bytes() { return payload.clone(); }

    public void replay(Level level) {
        if(level == null) return;
        Entity entity = state.apply(null);
        entity.x = x; entity.y = y; entity.z = z;
        if(kind == Kind.PROJECTILE_IMPACT) {
            if(entity instanceof Projectile)
                ((Projectile)entity).playNetworkImpactPresentation(
                        level, entityHit, secondaryExplosion);
            else ((Missile)entity).playNetworkImpactPresentation(level);
        }
        else ((FusedBomb)entity).playNetworkFizzle(level);
    }

    private static float number(DataInputStream input) throws IOException {
        float value = input.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value))
            throw new IOException("Nonfinite native dynamic cue position.");
        return value;
    }
}
