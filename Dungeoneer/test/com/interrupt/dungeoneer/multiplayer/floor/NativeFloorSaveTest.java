package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.tiles.Tile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NativeFloorSaveTest {
    @Test public void checkpointKeepsNativeStateAndIdsLikeASinglePlayerSave() {
        Level live = floor();
        Door door = new Door();
        door.x = door.y = 1.5f;
        door.startLoc = new Vector3(1.5f, 1.4f, 0f);
        door.doorState = Door.DoorState.OPEN;
        door.multiplayerIdentity = "object:4242";
        Breakable vase = new Breakable();
        vase.x = vase.y = 2.5f;
        vase.hp = 1;
        Monster slime = new Monster();
        slime.name = "SLIME";
        slime.hostile = true;
        slime.hp = 3;
        slime.maxHp = 4;
        slime.x = slime.y = 3.5f;
        slime.multiplayerIdentity = "monster:2";
        Entity effect = new Entity();
        effect.persists = false;
        Entity broken = new Breakable();
        broken.isActive = false;
        live.entities.add(door);
        live.entities.add(vase);
        live.entities.add(slime);
        live.entities.add(broken);
        live.non_collidable_entities.add(effect);
        Array<Entity> liveEntities = live.entities;

        Level restored = NativeFloorSave.restore(NativeFloorSave.capture(live));

        assertSame("Checkpoint must not disturb the running floor", liveEntities, live.entities);
        assertEquals(4, live.entities.size);
        assertEquals(1, live.non_collidable_entities.size);
        assertTrue(restored.restoredCampaignFloor);
        assertEquals("Removed and non-persisting entities stay out, as single-player cleanup does",
                3, restored.entities.size);
        assertEquals(0, restored.non_collidable_entities.size);
        Door savedDoor = (Door)restored.entities.get(0);
        assertEquals(Door.DoorState.OPEN, savedDoor.doorState);
        assertEquals(1.4f, savedDoor.startLoc.y, 0f);
        assertEquals("object:4242", savedDoor.multiplayerIdentity);
        assertEquals(1, ((Breakable)restored.entities.get(1)).hp);
        Monster savedSlime = (Monster)restored.entities.get(2);
        assertEquals(3, savedSlime.hp);
        assertEquals(4, savedSlime.maxHp);
        assertEquals("monster:2", savedSlime.multiplayerIdentity);
    }

    @Test public void checkpointThatWouldNotResumeCompleteIsRefused() {
        Level live = floor();
        // A saved object still referencing a client avatar, which never loads back.
        Monster holder = new Monster();
        holder.projectile = new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(
                new com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor(1L,
                        new com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId(2L),
                        new com.interrupt.dungeoneer.multiplayer.participant.ParticipantId(
                                "campaign-slot-2"), 2, "Friend", "humanoid-2"));
        live.entities.add(holder);
        live.entities.add(new Breakable());

        try {
            NativeFloorSave.capture(live);
            fail("A checkpoint that loses entities on load must not be stored.");
        }
        catch(IllegalStateException expected) { assertNotNull(expected.getMessage()); }
        assertEquals("Refused checkpoint leaves the running floor intact", 2, live.entities.size);
    }

    @Test public void corruptOrOversizedCheckpointFailsBeforeAFloorExists() {
        try {
            NativeFloorSave.restore(new byte[] { 1, 2, 3 });
            fail("Expected corrupt checkpoint rejection.");
        }
        catch(IllegalStateException expected) { assertNotNull(expected.getMessage()); }
        try {
            NativeFloorSave.restore(new byte[NativeFloorSave.MAX_BYTES + 1]);
            fail("Expected oversized checkpoint rejection.");
        }
        catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void freshFloorEntitiesCarryNoSavedIdentity() {
        assertNull(new Door().multiplayerIdentity);
        assertFalse(floor().restoredCampaignFloor);
    }

    private static Level floor() {
        Level level = new Level(4, 4);
        for(int index = 0; index < level.tiles.length; index++) level.tiles[index] = Tile.EmptyTile();
        return level;
    }
}
