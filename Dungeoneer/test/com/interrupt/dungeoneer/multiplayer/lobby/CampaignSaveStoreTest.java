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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CampaignSaveStoreTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void formatSevenNameMigrationBacksUpExactBytesAndKeepsOwnershipAndWorld() throws Exception {
        File root = temporaryFolder.newFolder("format-seven-name");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = withFloor(save("friends", compatibility("mp-v108-prototype-party-travel-55")),
                new byte[] { 7, 8, 9 });
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(7);
            legacy.writeInt(50);
            // Format 8 appends modified UTF: two-byte length plus seven ASCII bytes of "friends".
            legacy.setLength(legacy.length() - 9);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility(com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.BUILD_ID);
        assertFailure(store, "friends", new DirectConnectCompatibility(next.getBuildId(), "owned-v108", repeat('b')), "incompatible");
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        assertFalse(new File(file.getParentFile(), "campaign.save.before-format-8").exists());
        CampaignSave migrated = store.load("friends", next);
        assertEquals("friends", migrated.getCampaignId());
        assertEquals("friends", migrated.getCampaignName());
        assertEquals(next.getBuildId(), migrated.getCompatibility().getBuildId());
        assertEquals(original.getSlots().get(0).getLauncherIdentity(), migrated.getSlots().get(0).getLauncherIdentity());
        assertEquals(original.getSlots().get(1).getReconnectToken(), migrated.getSlots().get(1).getReconnectToken());
        assertEquals(5, migrated.getStartingLives());
        assertEquals(41, migrated.getParticipant(2).getProgress().gold);
        assertEquals(19L, migrated.getPhysicalItems().get(0).entityId);
        assertTrue(Arrays.equals(original.getNativeFloor(), migrated.getNativeFloor()));
        File backup = new File(file.getParentFile(), "campaign.save.before-format-8");
        assertTrue(Arrays.equals(before, Files.readAllBytes(backup.toPath())));
        assertEquals("friends", store.load("friends", next).getCampaignName());
        assertTrue(Arrays.equals(before, Files.readAllBytes(backup.toPath())));
    }

    @Test public void lobbyWireUpgradeKeepsNamedCampaignOwnershipCharactersAndNativeWorld() throws Exception {
        File root = temporaryFolder.newFolder("lobby-build-upgrade");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = withFloor(save("friends", compatibility("mp-v108-prototype-named-campaigns-56")),
                new byte[] { 7, 8, 9 }).withCampaignName("Friday Delver");
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile predecessor = new RandomAccessFile(file, "rw")) {
            predecessor.seek(8); predecessor.writeInt(50);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility(com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.BUILD_ID);
        assertFailure(store, "friends", new DirectConnectCompatibility(next.getBuildId(), "owned-v108", repeat('b')), "incompatible");
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        CampaignSave resumed = store.load("friends", next);
        assertEquals(next.getBuildId(), resumed.getCompatibility().getBuildId());
        assertEquals(next.getContentIdentity(), resumed.getCompatibility().getContentIdentity());
        assertEquals("Friday Delver", resumed.getCampaignName());
        assertEquals(original.getSlots().get(0).getLauncherIdentity(), resumed.getSlots().get(0).getLauncherIdentity());
        assertEquals(original.getSlots().get(1).getReconnectToken(), resumed.getSlots().get(1).getReconnectToken());
        assertEquals(original.getSlots().get(1).getPresentation(), resumed.getSlots().get(1).getPresentation());
        assertEquals(5, resumed.getStartingLives());
        assertEquals(41, resumed.getParticipant(2).getProgress().gold);
        assertEquals(19L, resumed.getPhysicalItems().get(0).entityId);
        assertTrue(Arrays.equals(original.getNativeFloor(), resumed.getNativeFloor()));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        store.save(resumed);
        assertTrue(Arrays.equals(before, Files.readAllBytes(new File(file.getParentFile(), "campaign.previous").toPath())));
        assertTrue("Lobby wire upgrade cannot change any saved gameplay body bytes",
                Arrays.equals(savedGameplayBody(before), savedGameplayBody(Files.readAllBytes(file.toPath()))));
        assertEquals("Friday Delver", store.load("friends", next).getCampaignName());
    }

    @Test public void manualReadyWireUpgradePreservesWholeSavedGameplayBody() throws Exception {
        File root = temporaryFolder.newFolder("ready-build-upgrade");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = withFloor(save("friends", compatibility("mp-v108-prototype-shared-lobby-57")),
                new byte[] { 7, 8, 9 }).withCampaignName("Friday Delver");
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile predecessor = new RandomAccessFile(file, "rw")) {
            predecessor.seek(8); predecessor.writeInt(51);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility(com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.BUILD_ID);
        assertFailure(store, "friends", new DirectConnectCompatibility(next.getBuildId(), "owned-v108", repeat('b')), "incompatible");
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        CampaignSave resumed = store.load("friends", next);
        assertEquals(next.getBuildId(), resumed.getCompatibility().getBuildId());
        assertEquals(next.getContentIdentity(), resumed.getCompatibility().getContentIdentity());
        assertEquals("Friday Delver", resumed.getCampaignName());
        assertEquals(original.getSlots().get(0).getLauncherIdentity(), resumed.getSlots().get(0).getLauncherIdentity());
        assertEquals(original.getSlots().get(1).getReconnectToken(), resumed.getSlots().get(1).getReconnectToken());
        assertEquals(original.getSlots().get(1).getPresentation(), resumed.getSlots().get(1).getPresentation());
        assertEquals(5, resumed.getStartingLives());
        assertEquals(41, resumed.getParticipant(2).getProgress().gold);
        assertEquals(19L, resumed.getPhysicalItems().get(0).entityId);
        assertTrue(Arrays.equals(original.getNativeFloor(), resumed.getNativeFloor()));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        store.save(resumed);
        assertTrue(Arrays.equals(before, Files.readAllBytes(new File(file.getParentFile(), "campaign.previous").toPath())));
        assertTrue("Ready wire upgrade cannot change any saved gameplay body bytes",
                Arrays.equals(savedGameplayBody(before), savedGameplayBody(Files.readAllBytes(file.toPath()))));
        assertEquals("Friday Delver", store.load("friends", next).getCampaignName());
    }

    @Test public void presentationWireUpgradePreservesWholeSavedGameplayBody() throws Exception {
        File root = temporaryFolder.newFolder("edit-build-upgrade");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = withFloor(save("friends", compatibility("mp-v108-prototype-manual-ready-58")),
                new byte[] { 7, 8, 9 }).withCampaignName("Friday Delver");
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile predecessor = new RandomAccessFile(file, "rw")) {
            predecessor.seek(8); predecessor.writeInt(52);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility("mp-v108-prototype-lobby-presentation-59");
        assertFailure(store, "friends", new DirectConnectCompatibility(next.getBuildId(), "owned-v108", repeat('b')), "incompatible");
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        CampaignSave resumed = store.load("friends", next);
        assertEquals(next.getBuildId(), resumed.getCompatibility().getBuildId());
        assertEquals("Friday Delver", resumed.getCampaignName());
        assertEquals(original.getSlots().get(0).getLauncherIdentity(), resumed.getSlots().get(0).getLauncherIdentity());
        assertEquals(original.getSlots().get(1).getReconnectToken(), resumed.getSlots().get(1).getReconnectToken());
        assertEquals(41, resumed.getParticipant(2).getProgress().gold);
        assertEquals(19L, resumed.getPhysicalItems().get(0).entityId);
        assertTrue(Arrays.equals(original.getNativeFloor(), resumed.getNativeFloor()));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
        store.save(resumed);
        assertTrue(Arrays.equals(before, Files.readAllBytes(new File(file.getParentFile(), "campaign.previous").toPath())));
        assertTrue("Presentation wire upgrade cannot change saved gameplay body bytes",
                Arrays.equals(savedGameplayBody(before), savedGameplayBody(Files.readAllBytes(file.toPath()))));
        assertEquals("Friday Delver", store.load("friends", next).getCampaignName());
    }

    private static byte[] savedGameplayBody(byte[] bytes) throws IOException {
        try(java.io.DataInputStream input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            input.readInt(); input.readInt(); input.readInt();
            input.readUTF(); input.readUTF(); input.readUTF();
            byte[] body = new byte[input.available()];
            input.readFully(body);
            return body;
        }
    }

    @Test public void campaignRoundTripRetainsDifferentAreasAndTheirNativeWorldState() throws Exception {
        CampaignSaveStore store = new CampaignSaveStore(temporaryFolder.newFolder("dormant"));
        DirectConnectCompatibility compatibility = compatibility("build-49");
        CampaignSave active = withFloor(save("friends", compatibility), new byte[] { 1, 2 })
                .withCampaignName("Friday friends / dungeon");
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState prior =
                com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState.fromCampaign(
                        "dungeon:1", withFloor(save("friends", compatibility), new byte[] { 7, 8, 9 }));
        CampaignSave campaign = active.withFloorHistory("dungeon:2",
                Collections.singletonList(prior), Collections.<Long, Long>emptyMap());
        store.save(campaign);

        CampaignSave loaded = store.load("friends", compatibility);
        assertEquals("Friday friends / dungeon", loaded.getCampaignName());
        assertEquals("dungeon:2", loaded.getActiveAreaKey());
        assertTrue(Arrays.equals(new byte[] { 1, 2 }, loaded.getNativeFloor()));
        assertEquals(1, loaded.getDormantFloors().size());
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState dormant =
                loaded.getDormantFloors().get(0);
        assertEquals("dungeon:1", dormant.getAreaKey());
        assertEquals("levels/start.bin", dormant.getFloorId());
        assertEquals(0x5eedL, dormant.getSeed());
        assertTrue(Arrays.equals(new byte[] { 7, 8, 9 }, dormant.getNativeFloor()));
        assertEquals(4, dormant.getDoors().get(0).state);
        assertEquals(0, dormant.getBreakables().get(0).hp);
        assertEquals("monster:4", dormant.getMonsterSpawns().get(0).monsterId);
        assertEquals(Arrays.asList("spawner:slime"), dormant.getConsumedMonsterSpawners());
        assertTrue("Carried inventory belongs to Campaign Slot, not Dormant Floor",
                dormant.getWorldItems().isEmpty());
        assertTrue("Participant effects do not become dormant world actors",
                dormant.getActorEffects().isEmpty());
        byte[] copy = dormant.getNativeFloor();
        copy[0] = 0;
        assertEquals(7, dormant.getNativeFloor()[0]);
    }

    @Test public void formatSixMigrationRetainsHistoryWithoutInventingScatterProvenance() throws Exception {
        File root = temporaryFolder.newFolder("format-six");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = save("friends", compatibility("mp-v108-prototype-dormant-floors-54"));
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(6); legacy.writeInt(49);
            legacy.setLength(legacy.length() - 13);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility("mp-v108-prototype-party-travel-55");
        CampaignSave migrated = store.load("friends", next);
        assertTrue(migrated.getScatterOwners().isEmpty());
        assertEquals(original.getActiveAreaKey(), migrated.getActiveAreaKey());
        assertEquals(41, migrated.getParticipant(2).getProgress().gold);
        assertTrue(Arrays.equals(before, Files.readAllBytes(new File(file.getParentFile(),
                "campaign.save.before-format-8").toPath())));
    }

    @Test public void formatFiveMigrationRetainsActiveFloorAndBacksUpOriginalBeforeAddingHistory() throws Exception {
        File root = temporaryFolder.newFolder("format-five");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = withFloor(save("friends", compatibility("mp-v108-prototype-late-admission-53")),
                new byte[] { 7, 8, 9 });
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(5); legacy.writeInt(49);
            // Historical format 5 ends before area key, timer/dormant counts and format 7 provenance.
            legacy.setLength(legacy.length() - 39);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility("mp-v108-prototype-dormant-floors-54");
        CampaignSave migrated = store.load("friends", next);
        assertEquals(next.getBuildId(), migrated.getCompatibility().getBuildId());
        assertEquals("levels/start.bin", migrated.getActiveAreaKey());
        assertTrue(migrated.getDormantFloors().isEmpty());
        assertTrue(migrated.getDropTimers().isEmpty());
        assertTrue(Arrays.equals(new byte[] { 7, 8, 9 }, migrated.getNativeFloor()));
        assertEquals(41, migrated.getParticipant(2).getProgress().gold);
        assertEquals(19L, migrated.getPhysicalItems().get(0).entityId);
        assertTrue(Arrays.equals(before, Files.readAllBytes(new File(file.getParentFile(),
                "campaign.save.before-format-8").toPath())));
    }

    @Test public void previousBuildMigratesWithoutInventingPersonalKnowledge() throws Exception {
        File root = temporaryFolder.newFolder("knowledge-migration");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = save("friends", compatibility("mp-v108-prototype-campaign-library-51"));
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(4); legacy.writeInt(47);
            // Format 5: 48 bytes of personal records/mapping; format 6: 26 bytes of empty floor history; format 7: 4-byte empty provenance count.
            legacy.setLength(legacy.length() - 87);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        DirectConnectCompatibility next = compatibility("mp-v108-prototype-campaign-library-52");
        CampaignSave migrated = store.load("friends", next);
        assertEquals(next.getBuildId(), migrated.getCompatibility().getBuildId());
        for(CampaignSave.ParticipantState participant : migrated.getParticipants()) {
            assertEquals(0, participant.getPersonalKnowledge().potionMask);
            assertTrue(participant.getPersonalKnowledge().maps.isEmpty());
        }
        assertEquals(41, migrated.getParticipant(2).getProgress().gold);
        assertTrue(Arrays.equals(before, Files.readAllBytes(new File(file.getParentFile(),
                "campaign.save.before-format-" + CampaignSave.FORMAT).toPath())));
        assertEquals(next.getBuildId(), store.load("friends", next).getCompatibility().getBuildId());
    }

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
            file.setLength(file.length() - 137L);
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

    @Test public void failedAtomicReplacementRetainsPreviousNativeState() throws Exception {
        File root = temporaryFolder.newFolder("atomic");
        CampaignSave original = withFloor(save("friends", compatibility("build-49")), new byte[] { 1 });
        CampaignSaveStore store = new CampaignSaveStore(root);
        store.save(original);
        CampaignSaveStore failing = new CampaignSaveStore(root, (source, target) -> {
            if(target.getFileName().toString().equals("campaign.save")) throw new IOException("Injected replacement failure");
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        try { failing.save(withFloor(original, new byte[] { 2 })); fail("Replacement must fail."); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("Could not save")); }
        assertTrue(Arrays.equals(new byte[] { 1 }, store.load("friends", original.getCompatibility()).getNativeFloor()));
        assertTrue(new File(new File(root, "friends"), "campaign.previous").isFile());
    }

    @Test public void formatThreeMigrationRetainsNativeFloorAndBacksUpOriginalBytes() throws Exception {
        File root = temporaryFolder.newFolder("format-three");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = withFloor(save("friends", compatibility("build-49")), new byte[] { 7, 8, 9 });
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(3);
            // Formats 4/5: 98 bytes of shared/personal facts; format 6: 26 bytes of empty floor history; format 7: 4-byte empty provenance count.
            legacy.setLength(legacy.length() - 137);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        CampaignSave migrated = store.load("friends", original.getCompatibility());
        assertTrue(Arrays.equals(new byte[] { 7, 8, 9 }, migrated.getNativeFloor()));
        assertEquals(0, migrated.getPartyKeys()); assertEquals(0L, migrated.getKeyRevision());
        assertTrue(migrated.getPartyProgression().sameFacts(
                com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.empty()));
        File backup = new File(file.getParentFile(), "campaign.save.before-format-" + CampaignSave.FORMAT);
        assertTrue(Arrays.equals(before, Files.readAllBytes(backup.toPath())));
    }

    @Test public void migrationBacksUpExactOldBytesBeforeChangingFormat() throws Exception {
        File root = temporaryFolder.newFolder("migration");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = save("friends", compatibility("build-49"));
        store.save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(2); legacy.setLength(legacy.length() - 138);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        store.load("friends", original.getCompatibility());
        File backup = new File(file.getParentFile(), "campaign.save.before-format-" + CampaignSave.FORMAT);
        assertTrue(Arrays.equals(before, Files.readAllBytes(backup.toPath())));
        try(RandomAccessFile migrated = new RandomAccessFile(file, "r")) {
            migrated.seek(4); assertEquals(CampaignSave.FORMAT, migrated.readInt());
        }
        store.load("friends", original.getCompatibility());
        assertTrue(Arrays.equals(before, Files.readAllBytes(backup.toPath())));
    }

    @Test public void rotatingRecoveryRequiresUncleanShutdownAndSkipsCorruptNewest() throws Exception {
        File root = temporaryFolder.newFolder("recovery");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = save("friends", compatibility("build-49"));
        store.save(original);
        store.beginSession("friends");
        for(int index = 1; index <= 5; index++) store.snapshot(withFloor(original, new byte[] { (byte)index }));
        assertFalse(store.needsRecovery("friends"));
        store.endSession("friends", false); // Process loss releases lock but retains dirty marker.
        CampaignSaveStore restarted = new CampaignSaveStore(root);
        assertTrue(restarted.needsRecovery("friends"));
        File newest = null;
        int count = 0;
        for(File file : new File(root, "friends").listFiles()) {
            if(!file.getName().startsWith("recovery-")) continue;
            count++;
            if(newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        assertEquals(3, count);
        try(FileOutputStream corrupt = new FileOutputStream(newest)) { corrupt.write(0); }
        CampaignSave recovered = restarted.recover("friends", original.getCompatibility());
        assertTrue(Arrays.equals(new byte[] { 4 }, recovered.getNativeFloor()));
        assertEquals(321f, recovered.getActorEffects().get(0).effects.get(0).remaining, 0f);
        assertEquals(9L, recovered.getActorEffects().get(0).effects.get(0).pulses);
        assertEquals(19L, recovered.getPhysicalItems().get(0).entityId);
        assertFalse(restarted.needsRecovery("friends"));
        try { restarted.recover("friends", original.getCompatibility()); fail("Clean run cannot select checkpoints."); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("only after unclean")); }
    }

    @Test public void failedMigrationBackupDoesNotChangeOriginalSave() throws Exception {
        File root = temporaryFolder.newFolder("failed-migration");
        CampaignSave original = save("friends", compatibility("build-49"));
        new CampaignSaveStore(root).save(original);
        File file = new File(new File(root, "friends"), "campaign.save");
        try(RandomAccessFile legacy = new RandomAccessFile(file, "rw")) {
            legacy.seek(4); legacy.writeInt(2); legacy.setLength(legacy.length() - 138);
        }
        byte[] before = Files.readAllBytes(file.toPath());
        CampaignSaveStore failing = new CampaignSaveStore(root, (source, target) -> {
            throw new IOException("Injected backup failure");
        });
        try { failing.load("friends", original.getCompatibility()); fail("Migration requires successful backup."); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("preserve Campaign file")); }
        assertTrue(Arrays.equals(before, Files.readAllBytes(file.toPath())));
    }

    @Test public void cleanShutdownHidesSnapshotsAndLiveHostBlocksRecovery() throws Exception {
        File root = temporaryFolder.newFolder("live-lock");
        CampaignSaveStore store = new CampaignSaveStore(root);
        CampaignSave original = save("friends", compatibility("build-49"));
        store.save(original); store.beginSession("friends"); store.snapshot(original);
        try {
            new CampaignSaveStore(root).recover("friends", original.getCompatibility());
            fail("Running Host must retain authority.");
        }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("still running")); }
        finally { store.endSession("friends", true); }
        assertFalse(new CampaignSaveStore(root).needsRecovery("friends"));
    }

    @Test public void terminalArchivesRejectWritesAndOldRecoveryForBothOutcomes() throws Exception {
        for(CampaignSave.Outcome outcome : new CampaignSave.Outcome[] {
                CampaignSave.Outcome.DEFEATED, CampaignSave.Outcome.COMPLETED }) {
            CampaignSaveStore store = new CampaignSaveStore(temporaryFolder.newFolder(outcome.name()));
            CampaignSave original = save("friends", compatibility("build-49"));
            store.save(original); store.beginSession("friends"); store.snapshot(original);
            CampaignSave terminal = new CampaignSave(original.getCompatibility(), original.getCampaignId(),
                    original.getCapacity(), original.getStartingLives(), outcome, original.getFloorId(),
                    original.getFloorSeed(), original.getFloorFingerprint(), original.getNativeWorldGeneration(),
                    original.getSlots(), original.getParticipants(), original.getPhysicalItems(), original.getCombat(),
                    original.getDoors(), original.getBreakables(), original.getActorEffects(), original.getMonsterSpawns(),
                    original.getConsumedMonsterSpawners(), original.getNativeFloor());
            store.save(terminal); store.endSession("friends", false);
            assertTrue(store.isArchived("friends")); assertFalse(store.needsRecovery("friends"));
            assertEquals(outcome, store.load("friends", original.getCompatibility()).getOutcome());
            try { store.save(original); fail("Archive cannot be overwritten."); }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("read-only")); }
            try { store.recover("friends", original.getCompatibility()); fail("Old snapshot cannot revive archive."); }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("only after unclean")); }
        }
    }

    @Test public void hostExportImportsIntoNewProfileWithoutTransferringAuthority() throws Exception {
        File sourceRoot = temporaryFolder.newFolder("export-source");
        CampaignSaveStore source = new CampaignSaveStore(sourceRoot);
        CampaignSave original = withFloor(save("friends", compatibility("build-49")), new byte[] { 9 });
        source.save(original);
        LauncherIdentity host = original.getSlots().get(0).getLauncherIdentity();
        File export = new File(temporaryFolder.getRoot(), "friends.delvercampaign");
        source.exportCampaign("friends", host, export, original.getCompatibility());
        CampaignRosterStore destination = new CampaignRosterStore(temporaryFolder.newFolder("import-target"), new java.security.SecureRandom());
        try {
            destination.importCampaign(export, new LauncherIdentity(repeat('3')),
                    AvatarCatalog.ownedV108Humanoids(), original.getCompatibility());
            fail("Different Host cannot adopt export.");
        }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("another Host")); }
        CampaignRoster imported = destination.importCampaign(export, host,
                AvatarCatalog.ownedV108Humanoids(), original.getCompatibility());
        assertEquals(host, imported.getSlot(1).getLauncherIdentity());
        assertEquals(original.getSlots().get(1).getReconnectToken(), imported.getSlot(2).getReconnectToken());
        assertTrue(Arrays.equals(new byte[] { 9 }, destination.campaignSaves().load("friends", original.getCompatibility()).getNativeFloor()));
        try {
            destination.importCampaign(export, host, AvatarCatalog.ownedV108Humanoids(), original.getCompatibility());
            fail("Import cannot overwrite Campaign.");
        }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        assertTrue(source.exists("friends")); assertTrue(export.isFile());
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
