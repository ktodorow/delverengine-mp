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
    @Test public void dormantNativeStatusKeepsRemainingTimeAndPresentationCursor() {
        java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> strings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings = new java.util.HashMap<>();
        try {
            Level live = floor();
            Monster slime = new Monster();
            slime.hp = slime.maxHp = 20;
            slime.multiplayerIdentity = "monster:7";
            com.interrupt.dungeoneer.statuseffects.PoisonEffect poison =
                    new com.interrupt.dungeoneer.statuseffects.PoisonEffect(500, 60, 1, false);
            poison.timer = 137f;
            poison.restoreMultiplayerCursor(4242L, 363f, 3L);
            slime.statusEffects = new Array<>();
            slime.statusEffects.add(poison);
            live.entities.add(slime);

            Level returned = NativeFloorSave.restore(NativeFloorSave.capture(live));
            Monster saved = (Monster)returned.entities.first();
            com.interrupt.dungeoneer.statuseffects.StatusEffect effect = saved.statusEffects.first();
            assertEquals(137f, effect.timer, 0f);
            assertEquals("Return must not invent a new status start", 4242L,
                    effect.getMultiplayerInstanceId());
            assertEquals(363f, effect.multiplayerElapsed, 0f);
            assertEquals(3L, effect.getMultiplayerPulseCount());
            assertEquals("Reconstruction applies no poison damage", 20, saved.hp);
            assertEquals("Capture leaves live effect unchanged", 137f, poison.timer, 0f);
        }
        finally { com.interrupt.managers.StringManager.localizedStrings = strings; }
    }

    @Test public void returningPersistentFireKeepsLifetimeAndRuntimeOrigin() {
        Level live = floor();
        com.interrupt.dungeoneer.entities.Fire fire = new com.interrupt.dungeoneer.entities.Fire();
        fire.setRuntimeSpawned(true);
        fire.multiplayerIdentity = "world-fire:17";
        fire.lifeTime = 400f; fire.randomLifeTime = 100f;
        fire.lifeTimeTimer = 117f; fire.hurtTimer = 13f; fire.spreadTimer = 29f;
        fire.setPresentationFlags(true, false, false, false);
        live.non_collidable_entities.add(fire);
        Level returned = NativeFloorSave.restore(NativeFloorSave.capture(live));
        com.interrupt.dungeoneer.entities.Fire restored =
                (com.interrupt.dungeoneer.entities.Fire)returned.non_collidable_entities.first();
        restored.init(returned, Level.Source.LEVEL_LOAD);
        assertEquals("Return cannot reroll persistent effect lifetime", 400f, restored.lifeTime, 0f);
        assertEquals(117f, restored.lifeTimeTimer, 0f);
        assertEquals(13f, restored.hurtTimer, 0f);
        assertEquals(29f, restored.spreadTimer, 0f);
        assertTrue("Observers must reconstruct runtime fire after return", restored.isRuntimeSpawned());
        assertEquals("world-fire:17", restored.multiplayerIdentity);
    }

    @Test public void returningAttackKeepsNativeAnimationInstanceAndFrameCursor() throws Exception {
        Level live = floor();
        Monster monster = new Monster(); monster.hp = monster.maxHp = 20;
        monster.multiplayerIdentity = "monster:animation";
        java.lang.reflect.Field attackField = Monster.class.getDeclaredField("attackAnimation");
        attackField.setAccessible(true);
        com.interrupt.dungeoneer.gfx.animation.SpriteAnimation attack =
                new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(0, 2, 60f, null);
        attackField.set(monster, attack);
        attack.play(); attack.animate(25f, monster);
        long instance = attack.getPlaybackId();
        live.entities.add(monster);
        Monster returned = (Monster)NativeFloorSave.restore(NativeFloorSave.capture(live)).entities.first();
        com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationState state = returned.captureNativeAnimation();
        assertEquals(com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationState.Kind.ATTACK, state.kind);
        assertEquals(instance, state.instanceId);
        assertEquals(25f, state.time, 0f);
        assertTrue(state.playing);
        // Next active frame continues cursor instead of replaying earlier action frames.
        ((com.interrupt.dungeoneer.gfx.animation.SpriteAnimation)attackField.get(returned)).animate(1f, returned);
        assertEquals(26f, returned.captureNativeAnimation().time, 0f);
        assertEquals(instance, returned.captureNativeAnimation().instanceId);
    }

    @Test public void dormantDelayedTriggerKeepsRemainingDelayAndOriginalActivator() {
        com.interrupt.dungeoneer.game.Game previous = com.interrupt.dungeoneer.game.Game.instance;
        try {
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(com.interrupt.dungeoneer.game.Game.class);
            game.player = new com.interrupt.dungeoneer.entities.Player();
            game.progression = new com.interrupt.dungeoneer.game.Progression();
            game.level = floor(); com.interrupt.dungeoneer.game.Game.instance = game;
            com.interrupt.dungeoneer.entities.triggers.BasicTrigger trigger =
                    new com.interrupt.dungeoneer.entities.triggers.BasicTrigger();
            trigger.triggerDelay = 10f; trigger.triggerResets = false;
            trigger.multiplayerIdentity = "object:delayed";
            game.level.entities.add(trigger);
            com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext remote =
                    new com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext(
                            new com.interrupt.dungeoneer.multiplayer.participant.ParticipantId("campaign-slot-2"),
                            new com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState(1f, 1f, 0f, 0f),
                            com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter.fromGame()
                                    .getPartyProgression());
            trigger.fire(remote, "remembered"); trigger.tick(game.level, 2f);
            game.level = NativeFloorSave.restore(NativeFloorSave.capture(game.level));
            final java.util.List<String> callbacks = new java.util.ArrayList<>();
            game.level.nativeTriggerPresentationListener = (source, activator, value) -> callbacks.add(activator.getValue());
            com.interrupt.dungeoneer.entities.triggers.BasicTrigger returned =
                    (com.interrupt.dungeoneer.entities.triggers.BasicTrigger)game.level.entities.first();
            returned.init(game.level, Level.Source.LEVEL_LOAD);
            assertTrue("Load never replays trigger event", callbacks.isEmpty());
            returned.tick(game.level, 7f);
            assertTrue("Remaining dormant delay freezes", callbacks.isEmpty());
            returned.tick(game.level, 2f);
            assertEquals(java.util.Collections.singletonList("campaign-slot-2"), callbacks);
            returned.tick(game.level, 20f);
            assertEquals("Completed delayed callback cannot run again", 1, callbacks.size());
        }
        finally { com.interrupt.dungeoneer.game.Game.instance = previous; }
    }

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
