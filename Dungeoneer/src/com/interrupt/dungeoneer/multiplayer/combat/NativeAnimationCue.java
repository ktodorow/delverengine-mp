package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.gfx.animation.*;
import java.io.*;

/** Bounded native frame sound/light, independent from recoverable animation cursor. */
public final class NativeAnimationCue {
    public static final int MAX_BYTES = 700;
    private final byte[] bytes;
    private NativeAnimationCue(byte[] bytes) { this.bytes = bytes.clone(); }
    public byte[] encode() { return bytes.clone(); }

    public static NativeAnimationCue capture(Entity owner, AnimationAction action) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBoolean(action instanceof SoundAction);
            out.writeFloat(owner.x); out.writeFloat(owner.y); out.writeFloat(owner.z);
            if(action instanceof SoundAction) {
                SoundAction sound = (SoundAction)action;
                out.writeUTF(sound.soundFile == null ? "" : sound.soundFile);
                out.writeFloat(sound.volume); out.writeFloat(sound.pitch); out.writeFloat(sound.range);
            } else if(action instanceof LightAnimationAction) {
                LightAnimationAction light = (LightAnimationAction)action;
                color(out, light.getStartColor()); color(out, light.getEndColor());
                out.writeFloat(light.getLightTime());
            } else throw new IllegalArgumentException("Unsupported native animation cue.");
            return decode(bytes.toByteArray());
        } catch(IOException invalid) { throw new IllegalArgumentException("Invalid native frame cue.", invalid); }
    }

    public static NativeAnimationCue decode(byte[] bytes) throws IOException {
        if(bytes == null || bytes.length > MAX_BYTES) throw new IOException("Native cue exceeds bound.");
        NativeAnimationCue cue = new NativeAnimationCue(bytes);
        cue.read(false);
        return cue;
    }

    public void replay() {
        try { read(true); } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private void read(boolean play) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        boolean sound = in.readBoolean();
        Entity position = new Entity(); position.x = number(in); position.y = number(in); position.z = number(in);
        AnimationAction action;
        if(sound) {
            String asset = in.readUTF();
            if(asset.length() > 512) throw new IOException("Native sound name exceeds bound.");
            action = new SoundAction(asset, number(in), number(in), number(in));
        } else {
            LightAnimationAction light = new LightAnimationAction();
            light.setStartColor(color(in)); light.setEndColor(color(in)); light.setLightTime(number(in)); action = light;
        }
        if(in.available() != 0) throw new IOException("Trailing native frame cue data.");
        if(play) action.doAction(position);
    }

    private static float number(DataInputStream in) throws IOException {
        float value = in.readFloat();
        if(Float.isNaN(value) || Float.isInfinite(value)) throw new IOException("Nonfinite native cue value.");
        return value;
    }
    private static void color(DataOutputStream out, Color value) throws IOException {
        out.writeFloat(value.r); out.writeFloat(value.g); out.writeFloat(value.b); out.writeFloat(value.a);
    }
    private static Color color(DataInputStream in) throws IOException {
        return new Color(number(in), number(in), number(in), number(in));
    }
}
