package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationState;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;
import com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CampaignSaveStoreTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void roundTripKeepsCampaignIdentityCharacterFloorAndItemState() throws Exception {
        File root = temporaryFolder.newFolder("campaigns");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave expected = save("friends", compatibility("build-49"));

        store.save(expected);
        CampaignSave loaded = store.load("friends", expected.getCompatibility());

        assertEquals(Arrays.asList("friends"), store.listCampaigns());
        assertEquals(4, loaded.getCapacity());
        assertEquals(5, loaded.getStartingLives());
        assertEquals("levels/start.bin", loaded.getFloorId());
        assertEquals(0x5eedL, loaded.getFloorSeed());
        assertEquals(7L, loaded.getNativeWorldGeneration());
        assertEquals(2, loaded.getSlots().size());
        assertEquals(2, loaded.getParticipant(2).getCampaignSlot());
        assertEquals(41, loaded.getParticipant(2).getProgress().gold);
        assertEquals(12.5f, loaded.getParticipant(2).getMovement().getX(), 0f);
        assertTrue(loaded.getParticipant(2).isHoldingOrb());
        assertEquals(1, loaded.getPhysicalItems().size());
        assertEquals("ARMOR", loaded.getPhysicalItems().get(0).equipmentSlot);
        assertNotNull(loaded.getFloorFingerprint());
        assertEquals(4, loaded.getDoors().get(0).state);
        assertEquals(0, loaded.getBreakables().get(0).hp);
        assertEquals(321f, loaded.getActorEffects().get(0).effects.get(0).remaining, 0f);
        assertEquals(9L, loaded.getActorEffects().get(0).effects.get(0).pulses);
        assertEquals("monster:4", loaded.getMonsterSpawns().get(0).monsterId);
        assertEquals("SLIME", loaded.getMonsterSpawns().get(0).name);
        assertEquals(3, loaded.getMonsterSpawns().get(0).health);
        assertEquals(Arrays.asList("spawner:slime"), loaded.getConsumedMonsterSpawners());
    }

    @Test public void floorCheckpointRoundTripsAndFormatTwoStillRebuilds() throws Exception {
        File root = temporaryFolder.newFolder("floors");
        CampaignSaveStore store = new CampaignSaveStore(root);
        DirectConnectCompatibility compatibility = compatibility("build-49");
        CampaignSave base = save("floor", compatibility);
        store.save(withFloor(base, new byte[] { 7, 8, 9 }));

        assertTrue(Arrays.equals(new byte[] { 7, 8, 9 },
                store.load("floor", compatibility).getNativeFloor()));

        // Format 2 wrote no checkpoint: the same record ends without the trailing absent flag.
        store.save(save("legacy", compatibility));
        File legacy = new File(new File(root, "legacy"), "campaign.save");
        try(RandomAccessFile file = new RandomAccessFile(legacy, "rw")) {
            file.seek(4L); file.writeInt(2);
            file.setLength(file.length() - 1L);
        }
        CampaignSave loaded = store.load("legacy", compatibility);
        assertFalse(loaded.hasNativeFloor());
        assertEquals("SLIME", loaded.getMonsterSpawns().get(0).name);
    }

    private CampaignSave withFloor(CampaignSave base, byte[] floor) {
        return new CampaignSave(base.getCompatibility(), base.getCampaignId(), base.getCapacity(),
                base.getStartingLives(), base.getOutcome(), base.getFloorId(), base.getFloorSeed(),
                base.getFloorFingerprint(), base.getNativeWorldGeneration(), base.getSlots(),
                base.getParticipants(), base.getPhysicalItems(), base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects(), base.getMonsterSpawns(),
                base.getConsumedMonsterSpawners(), floor);
    }

    @Test public void independentCampaignsNeverExposeEachOthersState() throws Exception {
        CampaignSaveStore store = new CampaignSaveStore(temporaryFolder.newFolder("library"));
        DirectConnectCompatibility compatibility = compatibility("build-49");
        store.save(save("alpha", compatibility));
        store.save(save("beta", compatibility));

        assertEquals(Arrays.asList("alpha", "beta"), store.listCampaigns());
        assertEquals("alpha", store.load("alpha", compatibility).getCampaignId());
        assertEquals("beta", store.load("beta", compatibility).getCampaignId());
    }

    @Test public void corruptFutureAndIncompatibleSavesFailBeforeReturningState() throws Exception {
        File root = temporaryFolder.newFolder("rejections");
        CampaignSaveStore store = new CampaignSaveStore(root);
        DirectConnectCompatibility compatibility = compatibility("build-49");
        store.save(save("corrupt", compatibility));
        File corrupt = new File(new File(root, "corrupt"), "campaign.save");
        try(FileOutputStream output = new FileOutputStream(corrupt)) { output.write(new byte[] { 1, 2 }); }
        assertFailure(store, "corrupt", compatibility, "truncated");

        store.save(save("future", compatibility));
        File future = new File(new File(root, "future"), "campaign.save");
        try(RandomAccessFile file = new RandomAccessFile(future, "rw")) {
            file.seek(4L); file.writeInt(CampaignSave.FORMAT + 1);
        }
        assertFailure(store, "future", compatibility, "future format");

        store.save(save("older", compatibility));
        File older = new File(new File(root, "older"), "campaign.save");
        try(RandomAccessFile file = new RandomAccessFile(older, "rw")) {
            file.seek(4L); file.writeInt(1);
        }
        assertFailure(store, "older", compatibility, "create a new Campaign");

        store.save(save("mixed", compatibility));
        assertFailure(store, "mixed", compatibility("another-build"), "incompatible engine build");
        assertTrue(store.exists("mixed"));
        assertFalse(store.exists("missing"));
    }

    private CampaignSave save(String campaignId, DirectConnectCompatibility compatibility) {
        CampaignSlot host = slot(1, '1', "Host", AvatarCatalog.HUMANOID_1);
        CampaignSlot friend = slot(2, '2', "Friend", AvatarCatalog.HUMANOID_2);
        CampaignSave.ParticipantState first = participant(host, 9f, 10, false);
        CampaignSave.ParticipantState second = participant(friend, 12.5f, 41, true);
        ParticipantId owner = new ParticipantId("campaign-slot-2");
        PhysicalItemState armor = new PhysicalItemState(19L, 4L, "armor-template", owner,
                0f, 0f, 0f, new ItemProperties(3, 2, "guarding", "iron", 1),
                false, "ARMOR");
        int size = SharedFloorFingerprint.Category.values().length;
        int[] counts = new int[size]; long[] digests = new long[size];
        for(int index = 0; index < size; index++) { counts[index] = index + 1; digests[index] = 10L + index; }
        DoorSnapshot door = new DoorSnapshot(1001L, 3L, 4, true,
                false, false, 2f, 3f, 0.5f, 90f, 1f);
        BreakableSnapshot breakable = new BreakableSnapshot(1002L, 5L, 0,
                false, false, 4f, 5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f);
        NativeStatusEffectState effect = new NativeStatusEffectState(7L,
                NativeStatusEffectState.Kind.POISON, 321f, 0.8f, "", true,
                18f, 1f, 9L);
        ActorEffectsSnapshot actorEffects = new ActorEffectsSnapshot(
                "participant:campaign-slot-1", 11L,
                false, Collections.singletonList(effect), 0f, 1f, 1f,
                false, 0f, new NativeAnimationState(NativeAnimationState.Kind.WALK,
                        13L, 2.5f, true, true, 4));
        return new CampaignSave(compatibility, campaignId, 4, 5,
                CampaignSave.Outcome.ACTIVE, "levels/start.bin", 0x5eedL,
                new SharedFloorFingerprint(counts, digests), 7L,
                Arrays.asList(host, friend), Arrays.asList(first, second),
                Arrays.asList(armor), null, Collections.singletonList(door),
                Collections.singletonList(breakable), Collections.singletonList(actorEffects),
                Collections.singletonList(new NativeMonsterSpawn("monster:4", "DUNGEON",
                        "SLIME", 6f, 7f, 0.5f, 3, 4)),
                Collections.singletonList("spawner:slime"));
    }

    private CampaignSave.ParticipantState participant(CampaignSlot slot, float x,
            int gold, boolean orb) {
        ParticipantId id = new ParticipantId("campaign-slot-" + slot.getNumber());
        NetworkEntityId entity = new NetworkEntityId(slot.getNumber());
        PartyMemberStatus party = new PartyMemberStatus(slot.getNumber(), entity,
                slot.getPresentation().getNickname(), slot.getPresentation().getAvatarId(),
                7, 11, 4, PartyMemberState.CONNECTED);
        MovementEntityState movement = new MovementEntityState(entity, 3L, 9L,
                x, 4f, 0.5f, 0f, 0f, 0f, 1.2f, MovementState.IDLE, 0.25f);
        ParticipantProgress progress = new ParticipantProgress(id, 6L, gold, 12, 2,
                5, 4, 3, 2, 1, 6, 1, 11, 22, 6);
        return new CampaignSave.ParticipantState(slot.getNumber(), party, movement, progress, orb);
    }

    private CampaignSlot slot(int number, char value, String nickname, String avatar) {
        return new CampaignSlot(number, new LauncherIdentity(repeat(value)), repeat(value),
                new SlotPresentation(nickname, avatar));
    }

    private DirectConnectCompatibility compatibility(String build) {
        return new DirectConnectCompatibility(build, "owned-v108", repeat('a'));
    }

    private void assertFailure(CampaignSaveStore store, String campaign,
            DirectConnectCompatibility compatibility, String message) {
        try {
            store.load(campaign, compatibility);
            fail("Expected Campaign Save rejection.");
        }
        catch(IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        while(result.length() < 64) result.append(value);
        return result.toString();
    }
}
