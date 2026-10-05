package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.ProjectedDecal;

import java.nio.charset.StandardCharsets;

/**
 * A mark a native hit, death or swing left on the floor or walls. Live clients draw their own
 * from presentations; one joining later rebuilds Host's marks from these.
 */
public final class NativeDecalState {
    public static final int MAX_ATLAS_BYTES = 64;
    private static final float MAX_SIZE = 64f;

    public final float x, y, z, directionX, directionY, directionZ, roll;
    public final float rotationX, rotationY, rotationZ, start, end, fieldOfView, width, height;
    public final boolean ortho;
    public final int artType, tex;
    /** Empty when the decal draws from its art type's atlas. */
    public final String atlas;
    public final float red, green, blue, alpha;

    public NativeDecalState(float x, float y, float z, float directionX, float directionY,
            float directionZ, float roll, float rotationX, float rotationY, float rotationZ,
            float start, float end, float fieldOfView, float width, float height, boolean ortho,
            int artType, int tex, String atlas, float red, float green, float blue, float alpha) {
        float[] values = { x, y, z, directionX, directionY, directionZ, roll, rotationX, rotationY,
                rotationZ, start, end, fieldOfView, width, height, red, green, blue, alpha };
        for(float value : values) {
            if(Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("Native decal state must be finite.");
            }
        }
        if(width <= 0f || width > MAX_SIZE || height <= 0f || height > MAX_SIZE
                || artType < 0 || artType >= Entity.ArtType.values().length
                || tex < 0 || tex > 0xFFFF || atlas == null
                || atlas.getBytes(StandardCharsets.UTF_8).length > MAX_ATLAS_BYTES) {
            throw new IllegalArgumentException("Invalid native decal.");
        }
        this.x = x; this.y = y; this.z = z;
        this.directionX = directionX; this.directionY = directionY; this.directionZ = directionZ;
        this.roll = roll;
        this.rotationX = rotationX; this.rotationY = rotationY; this.rotationZ = rotationZ;
        this.start = start; this.end = end; this.fieldOfView = fieldOfView;
        this.width = width; this.height = height; this.ortho = ortho;
        this.artType = artType; this.tex = tex; this.atlas = atlas;
        this.red = red; this.green = green; this.blue = blue; this.alpha = alpha;
    }

    public static NativeDecalState capture(ProjectedDecal decal) {
        Color color = decal.color == null ? Color.WHITE : decal.color;
        Entity.ArtType art = decal.artType == null ? Entity.ArtType.texture : decal.artType;
        return new NativeDecalState(decal.x, decal.y, decal.z, decal.direction.x,
                decal.direction.y, decal.direction.z, decal.roll, decal.rotation.x,
                decal.rotation.y, decal.rotation.z, decal.start, decal.end, decal.fieldOfView,
                decal.decalWidth, decal.decalHeight, decal.isOrtho, art.ordinal(), decal.tex,
                decal.spriteAtlas == null ? "" : decal.spriteAtlas,
                color.r, color.g, color.b, color.a);
    }

    /** Native decal to add to a level; draws exactly as the one Host captured. */
    public ProjectedDecal materialize() {
        ProjectedDecal decal = new ProjectedDecal(Entity.ArtType.values()[artType], tex, width);
        decal.decalHeight = height;
        decal.spriteAtlas = atlas.isEmpty() ? null : atlas;
        decal.x = x; decal.y = y; decal.z = z;
        decal.direction.set(directionX, directionY, directionZ);
        decal.roll = roll;
        decal.rotation.set(rotationX, rotationY, rotationZ);
        decal.start = start; decal.end = end; decal.fieldOfView = fieldOfView;
        decal.isOrtho = ortho;
        decal.color = new Color(red, green, blue, alpha);
        return decal;
    }
}
