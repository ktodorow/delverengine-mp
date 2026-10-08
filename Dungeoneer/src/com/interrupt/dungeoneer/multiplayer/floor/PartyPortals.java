package com.interrupt.dungeoneer.multiplayer.floor;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Stairs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Same native portal name on independently built peers; random travel UUIDs never name areas. */
public final class PartyPortals {
    private PartyPortals() { }
    public static String key(Entity portal) {
        if(portal instanceof Stairs) return "stairs:" + ((Stairs)portal).direction.name();
        return "warp:" + hash(SharedFloorIdentity.placementKey(portal));
    }
    public static String branchArea(String parent, Entity portal) {
        CampaignFloorState.requireAreaKey(parent);
        return "branch:" + hash(parent + "\n" + key(portal));
    }
    private static String hash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for(byte part : hash) key.append(String.format("%02x", part & 255));
            return key.toString();
        }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
