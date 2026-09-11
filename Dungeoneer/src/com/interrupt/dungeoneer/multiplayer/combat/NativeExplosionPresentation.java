package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Explosion;
import com.interrupt.dungeoneer.entities.ProjectedDecal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Immutable allowlisted native visual parameters; no engine object graphs or gameplay fields. */
public final class NativeExplosionPresentation {
    public static final int MAX_BYTES = 900;
    private final byte[] payload;

    private NativeExplosionPresentation(byte[] payload) { this.payload = payload.clone(); }

    public static NativeExplosionPresentation capture(Explosion explosion, float amount) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, explosion, amount);
            return decode(bytes.toByteArray());
        }
        catch(IOException invalid) { throw new IllegalArgumentException("Invalid native explosion presentation", invalid); }
    }

    public static NativeExplosionPresentation decode(byte[] payload) throws IOException {
        if(payload == null || payload.length == 0 || payload.length > MAX_BYTES)
            throw new IOException("Native explosion exceeds presentation bound");
        NativeExplosionPresentation result = new NativeExplosionPresentation(payload);
        result.create(); // Validate every field before accepting network state.
        return result;
    }

    public byte[] bytes() { return payload.clone(); }

    public void replay(com.interrupt.dungeoneer.game.Level level) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            float amount = number(in);
            read(in).playPresentation(level, amount);
        }
        catch(IOException invalid) { throw new IllegalStateException("Validated explosion changed", invalid); }
    }

    public Explosion create() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        float amount = number(in);
        if(amount < 0 || amount > 8) throw new IOException("Invalid particle multiplier");
        Explosion explosion = read(in);
        if(in.available() != 0) throw new IOException("Trailing explosion fields");
        return explosion;
    }

    private static void write(DataOutputStream out, Explosion e, float amount) throws IOException {
        out.writeFloat(amount);
        out.writeFloat(e.x);
        out.writeFloat(e.y);
        out.writeFloat(e.z);
        out.writeFloat(e.xa);
        out.writeFloat(e.ya);
        out.writeFloat(e.za);
        out.writeFloat(e.yOffset);
        out.writeFloat(e.scale);
        out.writeFloat(e.particleForce);
        out.writeFloat(e.explosionAnimSpeed);
        out.writeFloat(e.shakeDistance);
        out.writeFloat(e.shakeAmount);
        out.writeFloat(e.lightMod);
        out.writeFloat(e.explosionLightLifetime);
        out.writeFloat(e.randomRoll);
        out.writeInt(e.particleCount);
        out.writeInt(e.explosionStartTex);
        out.writeInt(e.explosionEndTex);
        out.writeBoolean(e.fullbrite);
        out.writeBoolean(e.makeDustRing);
        out.writeBoolean(e.makeFlyAways);
        color(out, e.color);
        color(out, e.explosionLightStartColor);
        color(out, e.explosionLightEndColor);
        color(out, e.dustRingColor);
        color(out, e.flyAwayColor);
        string(out, e.explodeSound); string(out, e.spriteAtlas);
        out.writeFloat(e.decalDirection.x); out.writeFloat(e.decalDirection.y); out.writeFloat(e.decalDirection.z);
        out.writeBoolean(e.hitDecal != null);
        if(e.hitDecal != null) {
            ProjectedDecal d = e.hitDecal;
            out.writeInt(d.artType.ordinal()); out.writeInt(d.tex); string(out, d.spriteAtlas);
            out.writeFloat(d.decalWidth); out.writeFloat(d.decalHeight);
            out.writeFloat(d.start); out.writeFloat(d.end); out.writeFloat(d.fieldOfView);
            out.writeBoolean(d.isOrtho);
        }
    }

    private static Explosion read(DataInputStream in) throws IOException {
        Explosion e = new Explosion();
        e.x = number(in);
        e.y = number(in);
        e.z = number(in);
        e.xa = number(in);
        e.ya = number(in);
        e.za = number(in);
        e.yOffset = number(in);
        e.scale = number(in);
        e.particleForce = number(in);
        e.explosionAnimSpeed = number(in);
        e.shakeDistance = number(in);
        e.shakeAmount = number(in);
        e.lightMod = number(in);
        e.explosionLightLifetime = number(in);
        e.randomRoll = number(in);
        e.particleCount = integer(in, 512);
        e.explosionStartTex = integer(in, 65535);
        e.explosionEndTex = integer(in, 65535);
        e.fullbrite = in.readBoolean();
        e.makeDustRing = in.readBoolean();
        e.makeFlyAways = in.readBoolean();
        e.color = color(in);
        e.explosionLightStartColor = color(in);
        e.explosionLightEndColor = color(in);
        e.dustRingColor = color(in);
        e.flyAwayColor = color(in);
        if(e.explosionLightEndColor == null) throw new IOException("Missing native light end color");
        if(e.explosionAnimSpeed < 0 || e.explosionLightLifetime < 0 || e.scale < 0)
            throw new IOException("Invalid native visual duration or scale");
        e.explodeSound = string(in); e.spriteAtlas = string(in);
        e.decalDirection.set(number(in), number(in), number(in));
        if(in.readBoolean()) {
            ProjectedDecal d = new ProjectedDecal();
            d.artType = Entity.ArtType.values()[integer(in, Entity.ArtType.values().length - 1)];
            d.tex = integer(in, 65535); d.spriteAtlas = string(in);
            d.decalWidth = number(in); d.decalHeight = number(in);
            d.start = number(in); d.end = number(in); d.fieldOfView = number(in);
            d.isOrtho = in.readBoolean(); e.hitDecal = d;
        }
        return e;
    }

    private static float number(DataInputStream in) throws IOException {
        float value = in.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) > 1000000)
            throw new IOException("Invalid native explosion number");
        return value;
    }
    private static int integer(DataInputStream in, int max) throws IOException {
        int value = in.readInt();
        if(value < 0 || value > max) throw new IOException("Invalid native explosion index/count");
        return value;
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if(value != null) { if(value.length() > 128) throw new IOException("Native visual name too long"); out.writeUTF(value); }
    }
    private static String string(DataInputStream in) throws IOException {
        if(!in.readBoolean()) return null;
        String value = in.readUTF();
        if(value.length() > 128) throw new IOException("Native visual name too long");
        return value;
    }
    private static void color(DataOutputStream out, Color value) throws IOException {
        out.writeBoolean(value != null);
        if(value != null) { out.writeFloat(value.r); out.writeFloat(value.g); out.writeFloat(value.b); out.writeFloat(value.a); }
    }
    private static Color color(DataInputStream in) throws IOException {
        return in.readBoolean() ? new Color(number(in), number(in), number(in), number(in)) : null;
    }
}
