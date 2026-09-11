package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.items.FusedBomb;
import com.interrupt.dungeoneer.entities.projectiles.*;
import java.io.*;

/** Host native projectile/bomb appearance and motion. Explicit data, never serialized engine objects. */
public final class NativeDynamicState {
    public static final int MAX_BYTES = 700;
    public final long id, itemId;
    public final boolean active;
    private final byte[] payload;
    private NativeDynamicState(byte[] payload) throws IOException {
        this.payload = payload.clone();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        id = in.readLong(); itemId = in.readLong(); active = in.readBoolean();
        if(id <= 0 || itemId < 0) throw new IOException("Invalid native entity identity.");
        read(null);
    }
    public byte[] bytes() { return payload.clone(); }
    public boolean sameState(NativeDynamicState other) { return other != null && java.util.Arrays.equals(payload, other.payload); }
    public static boolean supports(Entity entity) {
        if(entity == null) return false;
        Class<?> type = entity.getClass();
        return type == Projectile.class || type == MagicMissileProjectile.class
                || type == BeamProjectile.class || type == Missile.class
                || type == Bomb.class || type == FusedBomb.class;
    }
    public static NativeDynamicState capture(long id, long itemId, Entity e) {
        return capture(id, itemId, e, e.isActive);
    }
    public static NativeDynamicState capture(long id, long itemId, Entity e, boolean active) {
        if(!supports(e)) throw new IllegalArgumentException("Unsupported native dynamic entity.");
        if(e.artType == null || e.collision == null)
            throw new IllegalArgumentException("Native dynamic entity has incomplete presentation data.");
        validateText(e.spriteAtlas, "Native atlas");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(id); out.writeLong(itemId); out.writeBoolean(active);
            int kind = e instanceof MagicMissileProjectile ? 1 : e instanceof BeamProjectile ? 5
                    : e instanceof Projectile ? 0 : e instanceof Missile ? 2
                    : e instanceof Bomb ? 3 : 4;
            out.writeByte(kind);
            out.writeFloat(e.x); out.writeFloat(e.y); out.writeFloat(e.z);
            out.writeFloat(e.xa); out.writeFloat(e.ya); out.writeFloat(e.za);
            out.writeInt(e.tex); out.writeByte(e.artType.ordinal()); out.writeUTF(e.spriteAtlas == null ? "" : e.spriteAtlas);
            Color color = e.color == null ? Color.WHITE : e.color;
            out.writeFloat(color.r); out.writeFloat(color.g); out.writeFloat(color.b); out.writeFloat(color.a);
            out.writeFloat(e.scale); out.writeFloat(e.yOffset); out.writeFloat(e.roll);
            out.writeBoolean(e.fullbrite); out.writeBoolean(e.floating);
            out.writeFloat(e.collision.x); out.writeFloat(e.collision.y); out.writeFloat(e.collision.z);
            if(e instanceof Projectile) {
                Projectile p = (Projectile)e;
                out.writeByte(p.damageType == null ? -1 : p.damageType.ordinal());
                out.writeBoolean(p.makeHitParticles);
                writeDecal(out, p.hitDecal);
            }
            else if(e instanceof Missile) {
                Missile m = (Missile)e;
                out.writeByte(m.damageType == null ? -1 : m.damageType.ordinal());
                out.writeBoolean(m.isStuck()); out.writeBoolean(m.leaveTrail);
                out.writeFloat(m.trailInterval); out.writeFloat(m.effectLifetime);
                DynamicLight light = (DynamicLight)m.getAttached(DynamicLight.class);
                out.writeBoolean(light != null);
                if(light != null) {
                    out.writeFloat(light.lightColor.x); out.writeFloat(light.lightColor.y);
                    out.writeFloat(light.lightColor.z); out.writeFloat(light.range);
                }
            }
            if(kind == 1) {
                MagicMissileProjectile m = (MagicMissileProjectile)e;
                out.writeBoolean(m.leaveTrail); out.writeFloat(m.lightMod); out.writeFloat(m.trailInterval);
                out.writeInt(m.trailParticleTex); out.writeFloat(m.trailParticleLifetime); out.writeFloat(m.trailParticleRandomLifetime);
                out.writeFloat(m.trailParticleStartScale); out.writeFloat(m.trailParticleEndScale);
                out.writeByte(m.getHaloMode().ordinal());
            } else if(kind == 2) {
                Missile m = (Missile)e;
                out.writeFloat(m.rotation.x); out.writeFloat(m.rotation.y); out.writeFloat(m.rotation.z);
            } else if(kind == 5) {
                BeamProjectile b = (BeamProjectile)e;
                out.writeFloat(b.startPos.x); out.writeFloat(b.startPos.y); out.writeFloat(b.startPos.z);
                out.writeFloat(b.length); out.writeInt(b.startTex); out.writeInt(b.endTex);
                out.writeFloat(b.animateTime); out.writeFloat(b.animateTimer);
                out.writeBoolean(b.explosion != null);
                out.writeBoolean(b.destroyTimer != null);
                if(b.destroyTimer != null) out.writeFloat(b.destroyTimer);
                out.writeBoolean(b.destroyDelay != null);
                if(b.destroyDelay != null) out.writeFloat(b.destroyDelay);
            } else if(kind == 3) {
                Bomb b = (Bomb)e; out.writeBoolean(b.countdownStarted); out.writeFloat(b.countdownTimer); out.writeFloat(b.timerStart);
            } else if(kind == 4) {
                FusedBomb b = (FusedBomb)e; out.writeBoolean(b.isLit); out.writeBoolean(b.isDud); out.writeBoolean(b.isWet());
                out.writeFloat(b.countdownTimer); out.writeFloat(b.timerStart);
            }
            return decode(bytes.toByteArray());
        } catch(IOException invalid) { throw new IllegalArgumentException("Invalid native dynamic state.", invalid); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("Invalid native dynamic state.", invalid); }
    }
    public static NativeDynamicState decode(byte[] bytes) throws IOException {
        if(bytes == null || bytes.length > MAX_BYTES) throw new IOException("Native dynamic state exceeds bound.");
        return new NativeDynamicState(bytes);
    }
    public Entity apply(Entity existing) {
        try { return read(existing); } catch(IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private Entity read(Entity existing) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        in.readLong(); in.readLong(); in.readBoolean();
        int kind = in.readUnsignedByte();
        if(kind > 5) throw new IOException("Unknown native dynamic kind.");
        Entity e = existing;
        if(e == null) e = kind == 0 ? new Projectile() : kind == 1 ? new MagicMissileProjectile()
                : kind == 2 ? new Missile() : kind == 3 ? new Bomb()
                : kind == 4 ? new FusedBomb() : new BeamProjectile();
        Class<?> entityType = e.getClass();
        if((kind == 0 && entityType != Projectile.class)
                || (kind == 1 && entityType != MagicMissileProjectile.class)
                || (kind == 2 && entityType != Missile.class)
                || (kind == 3 && entityType != Bomb.class)
                || (kind == 4 && entityType != FusedBomb.class)
                || (kind == 5 && entityType != BeamProjectile.class))
            throw new IOException("Native dynamic template mismatch.");
        e.x = number(in); e.y = number(in); e.z = number(in);
        e.xa = number(in); e.ya = number(in); e.za = number(in);
        e.tex = in.readInt(); int art = in.readUnsignedByte();
        if(e.tex < -1 || e.tex > 65535 || art >= Entity.ArtType.values().length) throw new IOException("Invalid native sprite.");
        e.artType = Entity.ArtType.values()[art]; String atlas = in.readUTF();
        if(atlas.length() > 128) throw new IOException("Native atlas exceeds bound.");
        e.spriteAtlas = atlas.isEmpty() ? null : atlas;
        e.color = new Color(number(in), number(in), number(in), number(in));
        e.scale = number(in); e.yOffset = number(in); e.roll = number(in);
        e.fullbrite = in.readBoolean(); e.floating = in.readBoolean();
        e.collision.set(number(in), number(in), number(in));
        if(e instanceof Projectile) {
            Projectile p = (Projectile)e;
            p.damageType = damageType(in); p.makeHitParticles = in.readBoolean();
            p.hitDecal = readDecal(in, p.hitDecal);
            if(p instanceof BeamProjectile) ((BeamProjectile)p).damageType = p.damageType;
        }
        else if(e instanceof Missile) {
            Missile m = (Missile)e;
            m.damageType = damageType(in); m.setNetworkStuck(in.readBoolean());
            m.leaveTrail = in.readBoolean(); m.trailInterval = number(in); m.effectLifetime = number(in);
            DynamicLight light = (DynamicLight)m.getAttached(DynamicLight.class);
            if(in.readBoolean()) {
                if(light == null) { light = new DynamicLight(); m.attach(light); }
                light.lightColor.set(number(in), number(in), number(in));
                light.range = number(in);
            }
            else if(light != null) m.detach(light);
        }
        if(kind == 1) {
            MagicMissileProjectile m = (MagicMissileProjectile)e;
            m.leaveTrail = in.readBoolean(); m.lightMod = number(in); m.trailInterval = number(in);
            m.trailParticleTex = in.readInt(); m.trailParticleLifetime = number(in); m.trailParticleRandomLifetime = number(in);
            m.trailParticleStartScale = number(in); m.trailParticleEndScale = number(in);
            int halo = in.readUnsignedByte(); if(halo >= Entity.HaloMode.values().length) throw new IOException("Invalid native halo.");
            m.haloMode = Entity.HaloMode.values()[halo];
        } else if(kind == 2) ((Missile)e).setRotation(number(in), number(in), number(in));
        else if(kind == 5) {
            BeamProjectile b = (BeamProjectile)e;
            b.startPos.set(number(in), number(in), number(in));
            b.length = number(in); b.startTex = in.readInt(); b.endTex = in.readInt();
            b.animateTime = number(in); b.animateTimer = number(in);
            boolean hasExplosion = in.readBoolean();
            b.explosion = hasExplosion ? new Explosion() : null;
            b.destroyTimer = in.readBoolean() ? number(in) : null;
            b.destroyDelay = in.readBoolean() ? number(in) : null;
            b.prepareNetworkPresentation();
        }
        else if(kind == 3) {
            Bomb b = (Bomb)e; b.countdownStarted = in.readBoolean(); b.countdownTimer = number(in); b.timerStart = number(in);
        } else if(kind == 4) {
            FusedBomb b = (FusedBomb)e; b.isLit = in.readBoolean(); b.isDud = in.readBoolean(); b.setNetworkWet(in.readBoolean());
            b.countdownTimer = number(in); b.timerStart = number(in);
            b.wasSpawned = true;
        }
        if(in.available() != 0) throw new IOException("Trailing native dynamic data.");
        e.nativePresentationReplica = true; e.isActive = active; e.isSolid = false; e.persists = false;
        return e;
    }
    private static float number(DataInputStream in) throws IOException {
        float value = in.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) > 1000000f)
            throw new IOException("Invalid native entity number.");
        return value;
    }

    private static void validateText(String value, String name) {
        if(value != null && value.length() > 128)
            throw new IllegalArgumentException(name + " exceeds bound.");
    }

    private static com.interrupt.dungeoneer.entities.items.Weapon.DamageType damageType(
            DataInputStream in) throws IOException {
        int ordinal = in.readByte();
        if(ordinal < -1 || ordinal >= com.interrupt.dungeoneer.entities.items.Weapon.DamageType.values().length)
            throw new IOException("Invalid native damage type.");
        return ordinal < 0 ? null : com.interrupt.dungeoneer.entities.items.Weapon.DamageType.values()[ordinal];
    }

    private static void writeDecal(DataOutputStream out, ProjectedDecal decal) throws IOException {
        out.writeBoolean(decal != null);
        if(decal == null) return;
        if(decal.artType == null)
            throw new IllegalArgumentException("Native impact decal has no art type.");
        validateText(decal.spriteAtlas, "Native impact decal atlas");
        out.writeByte(decal.artType.ordinal()); out.writeInt(decal.tex);
        out.writeUTF(decal.spriteAtlas == null ? "" : decal.spriteAtlas);
        out.writeFloat(decal.scale);
        Color color = decal.color == null ? Color.WHITE : decal.color;
        out.writeFloat(color.r); out.writeFloat(color.g); out.writeFloat(color.b); out.writeFloat(color.a);
    }

    private static ProjectedDecal readDecal(DataInputStream in, ProjectedDecal decal) throws IOException {
        if(!in.readBoolean()) return null;
        int art = in.readUnsignedByte(); int texture = in.readInt(); String atlas = in.readUTF();
        float scale = number(in); Color color = new Color(number(in), number(in), number(in), number(in));
        if(art >= Entity.ArtType.values().length || texture < -1 || texture > 65535 || atlas.length() > 128)
            throw new IOException("Invalid native impact decal.");
        if(decal == null) decal = new ProjectedDecal(Entity.ArtType.values()[art], texture, scale);
        decal.artType = Entity.ArtType.values()[art]; decal.tex = texture;
        decal.spriteAtlas = atlas.isEmpty() ? null : atlas; decal.scale = scale; decal.color = color;
        return decal;
    }
}
