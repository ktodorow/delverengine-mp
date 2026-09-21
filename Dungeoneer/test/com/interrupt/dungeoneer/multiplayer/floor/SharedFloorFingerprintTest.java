package com.interrupt.dungeoneer.multiplayer.floor;

import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Sprite;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.game.Level;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SharedFloorFingerprintTest {
    @Test public void identicalFloorsMatchDespiteListOrderAndGroupIdPrefixes() {
        Level host = new Level(8, 8);
        host.entities.add(at(new Door(), 1f, 1f, "door"));
        host.static_entities.add(at(new Breakable(), 2.25f, 3.5f, null));
        host.entities.add(monster("Slime", 4f, 4f));
        host.non_collidable_entities.add(at(new Sprite(), 5f, 5f, null));
        Level client = new Level(8, 8);
        client.non_collidable_entities.add(at(new Sprite(), 5f, 5f, null));
        client.entities.add(monster("Slime", 4f, 4f));
        client.static_entities.add(at(new Breakable(), 2.25f, 3.5f, null));
        client.entities.add(at(new Door(), 1f, 1f,
                "0f8fad5b-d9cb-469f-a165-70867728950e_door"));

        assertEquals(SharedFloorFingerprint.capture(host), SharedFloorFingerprint.capture(client));
    }

    @Test public void hostOwnedItemsDoNotAffectFloorFingerprint() {
        Level host = new Level(8, 8);
        host.static_entities.add(at(new Breakable(), 2f, 2f, null));
        Level client = new Level(8, 8);
        client.static_entities.add(at(new Breakable(), 2f, 2f, null));
        client.entities.add(at(new Armor(), 3f, 3f, null));

        assertEquals(SharedFloorFingerprint.capture(host), SharedFloorFingerprint.capture(client));
    }

    @Test public void missingOrShiftedCrateIsReportedAsWorldObjectDifference() {
        Level host = new Level(8, 8);
        host.static_entities.add(at(new Breakable(), 2f, 2f, null));
        host.static_entities.add(at(new Breakable(), 6f, 2f, null));
        Level missing = new Level(8, 8);
        missing.static_entities.add(at(new Breakable(), 2f, 2f, null));
        Level shifted = new Level(8, 8);
        shifted.static_entities.add(at(new Breakable(), 2f, 2f, null));
        shifted.static_entities.add(at(new Breakable(), 6.2f, 2f, null));

        SharedFloorFingerprint hostFloor = SharedFloorFingerprint.capture(host);
        assertEquals("world objects 2/1",
                hostFloor.describeDifferences(SharedFloorFingerprint.capture(missing)));
        assertEquals("world objects 2/2 placed differently",
                hostFloor.describeDifferences(SharedFloorFingerprint.capture(shifted)));
    }

    @Test public void differentDecorationOrMonsterIsReportedInItsCategory() {
        Level host = new Level(8, 8);
        Sprite hostRock = at(new Sprite(), 5f, 5f, null);
        hostRock.tex = 3;
        host.non_collidable_entities.add(hostRock);
        host.entities.add(monster("Slime", 4f, 4f));
        Level client = new Level(8, 8);
        Sprite clientRock = at(new Sprite(), 5f, 5f, null);
        clientRock.tex = 4;
        client.non_collidable_entities.add(clientRock);
        client.entities.add(monster("Spider", 4f, 4f));

        String differences = SharedFloorFingerprint.capture(host)
                .describeDifferences(SharedFloorFingerprint.capture(client));

        assertTrue(differences, differences.contains("monsters 1/1 placed differently"));
        assertTrue(differences, differences.contains("scenery 1/1 placed differently"));
        assertNotEquals(SharedFloorFingerprint.capture(host), SharedFloorFingerprint.capture(client));
    }

    @Test(expected = IllegalArgumentException.class)
    public void countsOutsideBoundsAreRejected() {
        new SharedFloorFingerprint(new int[] { 0, 0, 0, 0, SharedFloorFingerprint.MAX_COUNT + 1 },
                new long[5]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void incompleteCategoriesAreRejected() {
        new SharedFloorFingerprint(new int[4], new long[4]);
    }

    private static <T extends Entity> T at(T entity, float x, float y, String id) {
        entity.x = x;
        entity.y = y;
        entity.z = 0.5f;
        entity.id = id;
        return entity;
    }

    private static Monster monster(String name, float x, float y) {
        Monster monster = at(new Monster(), x, y, null);
        monster.name = name;
        return monster;
    }
}
