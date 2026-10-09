package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.MonsterSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;
import com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorIdentity;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSave;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSlot;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotClaimRequest;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.readyClient;
import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.startReadySession;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DirectConnectCampaignPersistenceTest {
    private static final long TIMEOUT_MILLIS = 8000L;

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void abandonedLastLifeReturnsFreshAtNewFloorBeforeAtomicInstall() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("abandon-fresh"), new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        store.campaignSaves().save(save(compatibility(), roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectClient friend = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                roster.getSlot(2).getPresentation(), 2, new ReconnectTokenStore() {
                    public String load(String campaign) { return roster.getSlot(2).getReconnectToken(); }
                    public void save(String campaign, String token) { }
                }, compatibility());
        try {
            readyClient(friend);
            startReadySession(host); awaitPhase(friend, DirectConnectPhase.READY); host.setSessionPaused(true);
            final Level[] active = { nativeDormancyFloor() };
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            host.activatePartyDestination("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> {
                        active[0] = level;
                        CampaignSave atomic = store.campaignSaves().load("friends", compatibility());
                        assertEquals("Abandoned last Life returns immediately at new destination", 3,
                                atomic.getParticipant(2).getParty().getRemainingLives());
                        assertEquals(8, atomic.getParticipant(2).getParty().getHealth());
                        assertEquals(0, atomic.getParticipant(2).getProgress().gold);
                        assertEquals(0, atomic.getParticipant(2).getParty().getBleedoutTicks());
                        assertTrue(atomic.getActorEffects().isEmpty());
                    }, slot -> new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter(
                            new ParticipantProgress(new ParticipantId("campaign-slot-" + slot), 0L,
                                    0, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 12, 5), Collections.emptyList()));
            assertEquals(PartyMemberState.CONNECTED, host.getPartyStatus().getMember(2).getState());
        }
        finally { friend.close(); host.close(); }
    }

    @Test public void visitedArrivalAbandonsDownedWithoutFreshReturn() throws Exception {
        for(int oldLives : new int[] { 1, 2 }) {
            CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("visited-abandon-" + oldLives), new SecureRandom());
            CampaignRoster roster = persistenceRoster(store);
            CampaignSave source = withFloor(save(compatibility(), roster), com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(nativeDormancyFloor()));
            List<CampaignSave.ParticipantState> participants = new ArrayList<>(source.getParticipants());
            CampaignSave.ParticipantState old = participants.get(1);
            participants.set(1, new CampaignSave.ParticipantState(2,
                    old.getParty().withIncapacitation(PartyMemberState.DOWNED, oldLives, 99, 0, 0),
                    old.getMovement(), old.getProgress(), old.isHoldingOrb(), old.getPersonalKnowledge()));
            source = new CampaignSave(source.getCompatibility(), source.getCampaignId(), source.getCapacity(), source.getStartingLives(),
                    source.getOutcome(), source.getFloorId(), source.getFloorSeed(), source.getFloorFingerprint(), source.getNativeWorldGeneration(),
                    source.getSlots(), participants, source.getPhysicalItems(), source.getCombat(), source.getDoors(), source.getBreakables(),
                    source.getActorEffects(), source.getMonsterSpawns(), source.getConsumedMonsterSpawners(), source.getNativeFloor(),
                    source.getPartyKeys(), source.getKeyRevision(), source.getPartyProgression(), source.getPotionMapping());
            source = source.withFloorHistory("origin", Collections.singletonList(
                    com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState.fromCampaign("visited", source)), Collections.emptyMap());
            store.campaignSaves().save(source);
            DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
            DirectConnectClient friend = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'), roster.getSlot(2).getPresentation(), 2,
                    new ReconnectTokenStore() {
                        public String load(String campaign) { return roster.getSlot(2).getReconnectToken(); }
                        public void save(String campaign, String token) { }
                    }, compatibility());
            try {
                readyClient(friend); startReadySession(host); awaitPhase(friend, DirectConnectPhase.READY); host.setSessionPaused(true);
                host.activatePartyDestination("visited", source.getFloorId(), 101L, null,
                        () -> { throw new AssertionError("Visited destination must restore"); }, level -> { },
                        slot -> { throw new AssertionError("Visited destination must not roll Fresh Return"); });
                CampaignSave installed = store.campaignSaves().load("friends", compatibility());
                PartyMemberStatus returned = installed.getParticipant(2).getParty();
                assertEquals(oldLives - 1, returned.getRemainingLives());
                assertEquals(oldLives == 1 ? 0 : 4, returned.getHealth());
                assertEquals(oldLives == 1 ? PartyMemberState.SPECTATING : PartyMemberState.CONNECTED, returned.getState());
                assertTrue("Life loss applies gold penalty without fresh character reset", installed.getParticipant(2).getProgress().gold > 0);
                assertTrue(installed.getActorEffects().isEmpty());
            }
            finally { friend.close(); host.abortCampaignRecovery("Visited travel test cleanup."); host.close(); }
        }
    }

    @Test public void reconnectGraceKeepsFreshBodyAndRefusedWriteKeepsAbandonedSourceIntact() throws Exception {
        File root = temporaryFolder.newFolder("travel-grace");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = persistenceRoster(store); store.campaignSaves().save(save(compatibility(), roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        ReconnectTokenStore tokens = new ReconnectTokenStore() {
            public String load(String campaign) { return roster.getSlot(2).getReconnectToken(); }
            public void save(String campaign, String token) { }
        };
        DirectConnectClient friend = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                roster.getSlot(2).getPresentation(), 2, tokens, compatibility());
        try {
            readyClient(friend); startReadySession(host); awaitPhase(friend, DirectConnectPhase.READY);
            host.setSessionPaused(true); friend.close();
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(host.getPartyStatus().getMember(2).getState() != PartyMemberState.RECONNECTING
                    && System.currentTimeMillis() < deadline) Thread.sleep(10L);
            final Level[] active = { nativeDormancyFloor() };
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            java.util.function.IntFunction<com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter> starter = slot ->
                    new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter(new ParticipantProgress(
                            new ParticipantId("campaign-slot-" + slot), 0, 0, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 12, 5), Collections.emptyList());
            File primary = new File(new File(root, "friends"), "campaign.save");
            byte[] before = java.nio.file.Files.readAllBytes(primary.toPath());
            File refusedBackup = new File(primary.getParentFile(), "campaign.previous");
            assertTrue(refusedBackup.mkdir()); File blocker = new File(refusedBackup, "blocked"); assertTrue(blocker.createNewFile());
            long generation = host.getNativeWorldGeneration();
            try {
                host.activatePartyDestination("new", "levels/new.bin", 101L, null,
                        DirectConnectCampaignPersistenceTest::nativeDormancyFloor,
                        level -> { throw new AssertionError("Refused save cannot install destination"); }, starter);
                org.junit.Assert.fail("Refused atomic replacement must fail");
            }
            catch(IllegalStateException expected) { }
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(primary.toPath()));
            assertEquals(generation, host.getNativeWorldGeneration());
            assertEquals(1, host.getPartyStatus().getMember(2).getRemainingLives());
            assertEquals(37, host.getEconomy().get(new ParticipantId("campaign-slot-2")).gold);
            assertTrue(blocker.delete()); assertTrue(refusedBackup.delete());
            host.activatePartyDestination("new", "levels/new.bin", 101L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> active[0] = level, starter);
            assertEquals("Frozen body remains reclaimable during grace", PartyMemberState.RECONNECTING,
                    host.getPartyStatus().getMember(2).getState());
            assertTrue(host.getPartyStatus().getMember(2).getEntityId() != null);
            friend = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                    roster.getSlot(2).getPresentation(), 2, tokens, compatibility());
            awaitPhase(friend, DirectConnectPhase.READY);
            assertEquals(3, friend.getPartyStatus().getMember(2).getRemainingLives());
            assertEquals(8, friend.getPartyStatus().getMember(2).getHealth());
            assertEquals(PartyMemberState.CONNECTED, friend.getPartyStatus().getMember(2).getState());
        }
        finally { friend.close(); host.close(); }
    }

    @Test public void firstArrivalResetsDisconnectedSpectatorInsideAtomicTravelSave() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("fresh-absent"), new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        CampaignSave original = save(compatibility(), roster);
        List<CampaignSave.ParticipantState> slots = new ArrayList<>(original.getParticipants());
        slots.set(1, new CampaignSave.ParticipantState(2,
                new PartyMemberStatus(2, null, "Friend", AvatarCatalog.HUMANOID_2, 0, 8, 0, PartyMemberState.DISCONNECTED),
                original.getParticipant(2).getMovement(), original.getParticipant(2).getProgress(), false,
                original.getParticipant(2).getPersonalKnowledge()));
        store.campaignSaves().save(new CampaignSave(compatibility(), roster.getCampaignId(), 2, 3,
                CampaignSave.Outcome.ACTIVE, original.getFloorId(), original.getFloorSeed(), null, 4L,
                roster.getSlots(), slots, original.getPhysicalItems(),
                new CombatSnapshot(9L, 120L, Collections.emptyList(), Arrays.asList(
                        original.getCombat().getCombatant("participant:campaign-slot-1"),
                        new CombatantSnapshot("participant:campaign-slot-2", CombatantKind.PARTICIPANT, 0, 8))),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            final Level[] active = { nativeDormancyFloor() };
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            ParticipantId friend = new ParticipantId("campaign-slot-2");
            host.getItemWorld().registerParticipant(friend, 12);
            PhysicalItemState oldGear = host.getItemWorld().spawn("old-armor", friend, 1f, 1f, 0f);
            final CampaignSave[] atomic = { null };
            host.activatePartyDestination("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> {
                        active[0] = level;
                        atomic[0] = store.campaignSaves().load("friends", compatibility());
                    }, slot -> new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter(
                            new ParticipantProgress(new ParticipantId("campaign-slot-" + slot), 0L,
                                    0, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 12, 5),
                            Collections.singletonList(new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter.Starter(
                                    "new-sword", ItemProperties.DEFAULT, ""))));
            CampaignSave.ParticipantState fresh = atomic[0].getParticipant(2);
            assertEquals(3, fresh.getParty().getRemainingLives());
            assertEquals(8, fresh.getParty().getHealth());
            assertEquals(0, fresh.getProgress().gold);
            assertEquals(1, fresh.getProgress().level);
            assertEquals(0, fresh.getProgress().experience);
            assertFalse(atomic[0].getPhysicalItems().stream().anyMatch(i -> i.entityId == oldGear.entityId && !i.consumed));
            assertEquals(1, atomic[0].getPhysicalItems().stream().filter(i -> friend.equals(i.owner)).count());
            assertTrue("Fresh Return belongs to first atomic destination write", atomic[0].getNativeWorldGeneration() > 1L);
        }
        finally { host.close(); }
    }

    @Test public void freshReturnRemovesOnlyOwnUnclaimedScatterAcrossPreservedFloors() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("fresh-scatter"), new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        CampaignSave original = save(compatibility(), roster);
        List<CampaignSave.ParticipantState> participants = new ArrayList<>(original.getParticipants());
        CampaignSave.ParticipantState old = original.getParticipant(2);
        participants.set(1, new CampaignSave.ParticipantState(2,
                new PartyMemberStatus(2, null, "Friend", AvatarCatalog.HUMANOID_2, 0, 8, 0, PartyMemberState.DISCONNECTED),
                old.getMovement(), old.getProgress(), false, old.getPersonalKnowledge()));
        Level past = nativeDormancyFloor();
        Item nativeDrop = new Item(); nativeDrop.multiplayerIdentity = "item:202"; past.entities.add(nativeDrop);
        PhysicalItemState ownPast = new PhysicalItemState(202, 1, "past-scatter", null, 1f, 1f, 0f, ItemProperties.DEFAULT, false);
        PhysicalItemState otherPast = new PhysicalItemState(203, 1, "other-scatter", null, 1f, 1f, 0f, ItemProperties.DEFAULT, false);
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState prior = new com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState(
                "past", "levels/past.bin", 99L, null, com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(past),
                Arrays.asList(ownPast, otherPast), null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.singletonMap(202L, 100L));
        List<PhysicalItemState> items = new ArrayList<>(original.getPhysicalItems());
        items.add(new PhysicalItemState(201, 1, "source-scatter", null, 1f, 1f, 0f, ItemProperties.DEFAULT, false));
        Map<Long, Integer> owners = new HashMap<>(); owners.put(201L, 2); owners.put(202L, 2); owners.put(203L, 1);
        CampaignSave saved = new CampaignSave(compatibility(), "friends", 2, 3, CampaignSave.Outcome.ACTIVE,
                original.getFloorId(), original.getFloorSeed(), null, 4L, roster.getSlots(), participants, items,
                new CombatSnapshot(9L, 120L, Collections.emptyList(), Arrays.asList(
                        original.getCombat().getCombatant("participant:campaign-slot-1"),
                        new CombatantSnapshot("participant:campaign-slot-2", CombatantKind.PARTICIPANT, 0, 8))),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList())
                .withFloorHistory("source", Collections.singletonList(prior), Collections.emptyMap()).withScatterOwners(owners);
        store.campaignSaves().save(saved);
        assertEquals("Ownership survives cold save", owners, store.campaignSaves().load("friends", compatibility()).getScatterOwners());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(nativeDormancyFloor()));
            host.activatePartyDestination("new", "levels/new.bin", 101L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> { }, slot ->
                            new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter(new ParticipantProgress(
                                    new ParticipantId("campaign-slot-" + slot), 0, 0, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 12, 5), Collections.emptyList()));
            CampaignSave atomic = store.campaignSaves().load("friends", compatibility());
            assertEquals(Collections.singletonMap(203L, 1), atomic.getScatterOwners());
            for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor : atomic.getDormantFloors()) {
                assertFalse(floor.getWorldItems().stream().anyMatch(item -> item.entityId == 201 || item.entityId == 202));
                assertTrue(floor.getDropTimers().isEmpty());
                if(floor.getAreaKey().equals("past")) {
                    assertEquals(203L, floor.getWorldItems().get(0).entityId);
                    assertTrue("Lost scatter must not respawn from native checkpoint",
                            com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(floor.getNativeFloor()).entities.size == 0);
                }
            }
        }
        finally { host.close(); }
        DirectConnectHost resumed = DirectConnectHost.start(0, compatibility(), roster, store,
                new com.interrupt.dungeoneer.multiplayer.movement.LevelMovementCollisionWorld(
                        com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(
                                store.campaignSaves().load("friends", compatibility()).getNativeFloor())));
        DirectConnectClient friend = DirectConnectClient.connect("127.0.0.1", resumed.getBoundPort(), identity('2'),
                roster.getSlot(2).getPresentation(), 2, new ReconnectTokenStore() {
                    public String load(String campaign) { return roster.getSlot(2).getReconnectToken(); }
                    public void save(String campaign, String token) { }
                }, compatibility());
        try {
            readyClient(friend); startReadySession(resumed); awaitPhase(friend, DirectConnectPhase.READY);
            assertEquals(3, friend.getPartyStatus().getMember(2).getRemainingLives());
            assertEquals(8, friend.getPartyStatus().getMember(2).getHealth());
            assertEquals(0, resumed.getEconomy().get(new ParticipantId("campaign-slot-2")).gold);
        }
        finally { friend.close(); resumed.close(); }
    }

    @Test public void hostActivatesOneAreaAndReturnsNativeWorldWithoutMovingItsDrops() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("floor-history"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            final Level[] active = { nativeDormancyFloor() };
            Door door = new Door(); door.multiplayerIdentity = "object:4141";
            door.doorState = Door.DoorState.OPEN; active[0].entities.add(door);
            com.interrupt.dungeoneer.entities.Monster survivor = new com.interrupt.dungeoneer.entities.Monster();
            survivor.hp = 3; survivor.maxHp = 8; survivor.multiplayerIdentity = "monster:5151";
            survivor.x = 1.5f; survivor.y = 2.5f; active[0].entities.add(survivor);
            Entity defeated = new com.interrupt.dungeoneer.entities.Monster(); defeated.isActive = false;
            active[0].entities.add(defeated);
            active[0].tiles[0].floorHeight = -0.75f;
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            ParticipantId participant = new ParticipantId("campaign-slot-1");
            host.getItemWorld().registerParticipant(participant, 12);
            PhysicalItemState carried = host.getItemWorld().spawn("kept-sword", participant, 1f, 1f, 0f);
            PhysicalItemState dropped = host.getItemWorld().spawn("old-floor-drop", null, 2f, 2f, 0f);
            String originalArea = host.persistCampaign().getActiveAreaKey();
            long generation = host.getNativeWorldGeneration();

            assertTrue(host.activateCampaignFloor("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> active[0] = level));
            assertTrue(host.getNativeWorldGeneration() > generation);
            assertEquals("levels/second.bin", host.getStatus().getFloorId());
            assertNull("Prior-floor drop must not be active on destination", host.getItemWorld().get(dropped.entityId));
            assertEquals(participant, host.getItemWorld().get(carried.entityId).owner);
            assertEquals(1, host.persistCampaign().getDormantFloors().size());
            assertEquals("Only destination is available to native simulation", 0, active[0].entities.size);
            PhysicalItemState secondDrop = host.getItemWorld().spawn("new-floor-drop", null, 2f, 2f, 0f);
            assertTrue("Global item identity cannot reuse dormant item ID", secondDrop.entityId > dropped.entityId);

            assertFalse(host.activateCampaignFloor(originalArea, GameApplication.OPEN_SOURCE_TEST_LEVEL, 999L, null,
                    () -> { throw new AssertionError("Visited area cannot regenerate"); }, level -> active[0] = level));
            assertEquals(2, active[0].entities.size);
            assertEquals("object:4141", active[0].entities.get(0).multiplayerIdentity);
            assertEquals(Door.DoorState.OPEN, ((Door)active[0].entities.get(0)).doorState);
            assertEquals("monster:5151", active[0].entities.get(1).multiplayerIdentity);
            assertEquals(3, ((com.interrupt.dungeoneer.entities.Monster)active[0].entities.get(1)).hp);
            assertEquals(-0.75f, active[0].tiles[0].floorHeight, 0f);
            assertEquals("old-floor-drop", host.getItemWorld().get(dropped.entityId).templateId);
            assertNull(host.getItemWorld().get(secondDrop.entityId));
            CampaignSave saved = host.persistCampaign();
            assertEquals(originalArea, saved.getActiveAreaKey());
            assertEquals(1, saved.getDormantFloors().size());
            assertEquals("dungeon:2", saved.getDormantFloors().get(0).getAreaKey());
            assertEquals("new-floor-drop", saved.getDormantFloors().get(0).getWorldItems().get(0).templateId);
            assertTrue(host.isSessionPaused());
        }
        finally { host.close(); }
        DirectConnectHost resumed = DirectConnectHost.start(0, compatibility, roster, store,
                new com.interrupt.dungeoneer.multiplayer.movement.LevelMovementCollisionWorld(
                        com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(
                                store.campaignSaves().load("friends", compatibility).getNativeFloor())));
        try {
            startReadySession(resumed); resumed.setSessionPaused(true);
            CampaignSave recovered = resumed.persistCampaign();
            assertEquals(1, recovered.getDormantFloors().size());
            assertEquals("dungeon:2", recovered.getDormantFloors().get(0).getAreaKey());
        }
        finally { resumed.close(); }
    }

    @Test public void nativeBridgeReturnKeepsSavedMonsterEffectInsteadOfReplayingBegin() throws Exception {
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> strings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings = new HashMap<>();
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("native-status-return"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        store.campaignSaves().save(save(compatibility(), roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController combat =
                new com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController(host, false);
        try {
            startReadySession(host); host.setSessionPaused(true);
            Level nativeFloor = nativeDormancyFloor();
            com.interrupt.dungeoneer.entities.Monster monster = new com.interrupt.dungeoneer.entities.Monster();
            monster.multiplayerIdentity = "monster:9";
            monster.hp = monster.maxHp = 20;
            com.interrupt.dungeoneer.statuseffects.PoisonEffect poison =
                    new com.interrupt.dungeoneer.statuseffects.PoisonEffect(500, 60, 1, false);
            poison.showParticleEffect = false;
            poison.restoreMultiplayerCursor(7171L, 59f, 0L);
            monster.statusEffects = new com.badlogic.gdx.utils.Array<>(); monster.statusEffects.add(poison);
            poison.doTick(monster, 59f);
            nativeFloor.entities.add(monster);
            Game game = partyStoryGame();
            game.level = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(
                    com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(nativeFloor));
            com.interrupt.dungeoneer.entities.Monster returned =
                    (com.interrupt.dungeoneer.entities.Monster)game.level.entities.first();
            com.interrupt.dungeoneer.statuseffects.StatusEffect saved = returned.statusEffects.first();
            host.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture("monster:9", 1L, returned));
            combat.prepare(game);
            // Native checkpoint is newer and richer than the DTO; bridge must not replace it.
            assertTrue("Return retains native effect, including private damage cadence",
                    saved == returned.statusEffects.first());
            returned.statusEffects.first().doTick(returned, 2f);
            assertEquals("Poison resumes after two active ticks, not another complete period", 19, returned.hp);
            assertEquals(7171L, returned.statusEffects.first().getMultiplayerInstanceId());
        }
        finally { combat.dispose(); host.close(); Game.instance = previous;
            com.interrupt.managers.StringManager.localizedStrings = strings; }
    }

    @Test public void itemBridgeReturnBindsOriginalDropWithoutCloningOrResettingNativeMotion() throws Exception {
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> strings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings = new HashMap<>();
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("native-items-return"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        // New campaign: native items are authoritative, without resumed placeholder inventory.
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", roster.getSlot(2).getReconnectToken());
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                identity('2'), roster.getSlot(2).getPresentation(), 2, tokens, compatibility());
        try {
            readyClient(client);
            startReadySession(host); awaitPhase(client, DirectConnectPhase.READY); host.setSessionPaused(true);
            Game game = partyStoryGame(); game.level = nativeDormancyFloor();
            com.interrupt.dungeoneer.entities.items.Gold gold = new com.interrupt.dungeoneer.entities.items.Gold(6);
            gold.x = gold.y = 2.5f; gold.z = 0.5f;
            game.level.entities.add(gold);
            DirectConnectItemController items = new DirectConnectItemController(host);
            items.prepare(game);
            long id = host.getPhysicalItems().get(0).entityId;
            gold.xa = 0.17f; gold.ya = -0.09f; gold.za = 0.21f;
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(game.level));
            String origin = host.persistCampaign().getActiveAreaKey();
            host.activateCampaignFloor("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> game.level = level);
            items.prepare(game); items.update(game);
            assertEquals("Old floor drops cannot reappear through stale native bindings", 0,
                    game.level.entities.size);
            host.activateCampaignFloor(origin, GameApplication.OPEN_SOURCE_TEST_LEVEL, 999L, null,
                    () -> { throw new AssertionError("Visited floor cannot rebuild"); }, level -> game.level = level);
            Item nativeDrop = (Item)game.level.entities.first();
            items.prepare(game); items.update(game);
            assertEquals("One native drop, one durable physical identity", 1, game.level.entities.size);
            assertEquals(1, host.getPhysicalItems().size());
            assertEquals(id, host.getPhysicalItems().get(0).entityId);
            assertTrue(game.level.entities.first() == nativeDrop);
            assertEquals(0.17f, nativeDrop.xa, 0f);
            assertEquals(-0.09f, nativeDrop.ya, 0f);
            assertEquals(0.21f, nativeDrop.za, 0f);
        }
        finally { client.close(); host.close(); Game.instance = previous;
            com.interrupt.managers.StringManager.localizedStrings = strings; }
    }

    @Test public void nativeColdResumeUsesChangedSavedTerrainInsteadOfPristineDefinition() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("saved-terrain"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        CampaignSave base = save(compatibility(), roster);
        Level played = new Level(32, 32), pristine = new Level(32, 32);
        for(int i = 0; i < played.tiles.length; i++) {
            played.tiles[i] = com.interrupt.dungeoneer.tiles.Tile.EmptyTile();
            played.tiles[i].floorHeight = 0f;
            pristine.tiles[i] = com.interrupt.dungeoneer.tiles.Tile.EmptyTile();
            pristine.tiles[i].floorHeight = 2f;
            pristine.tiles[i].ceilHeight = 4f;
        }
        store.campaignSaves().save(new CampaignSave(base.getCompatibility(), base.getCampaignId(),
                base.getCapacity(), base.getStartingLives(), base.getOutcome(), base.getFloorId(),
                base.getFloorSeed(), base.getFloorFingerprint(), base.getNativeWorldGeneration(),
                base.getSlots(), base.getParticipants(), base.getPhysicalItems(), base.getCombat(),
                base.getDoors(), base.getBreakables(), base.getActorEffects(), base.getMonsterSpawns(),
                base.getConsumedMonsterSpawners(),
                com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(played)));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store,
                new com.interrupt.dungeoneer.multiplayer.movement.LevelMovementCollisionWorld(pristine));
        try {
            startReadySession(host); host.setSessionPaused(true);
            CampaignSave saved = host.persistCampaign();
            assertEquals(16.5f, saved.getParticipant(1).getMovement().getX(), 0f);
            assertEquals(0.5f, saved.getParticipant(1).getMovement().getZ(), 0f);
            assertTrue(saved.hasNativeFloor());
        }
        finally { host.close(); }
    }

    @Test public void nativeParticipantStatusFollowsBodyWithoutRestartOnFloorSwap() throws Exception {
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> strings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings = new HashMap<>();
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("participant-status-travel"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        store.campaignSaves().save(save(compatibility(), roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController combat =
                new com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController(host, false);
        try {
            startReadySession(host); host.setSessionPaused(true);
            Game game = partyStoryGame(); game.level = nativeDormancyFloor();
            String actor = "participant:campaign-slot-1";
            host.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(actor, 1L, game.player));
            combat.prepare(game);
            com.interrupt.dungeoneer.statuseffects.PoisonEffect poison =
                    new com.interrupt.dungeoneer.statuseffects.PoisonEffect(500, 60, 1, false);
            poison.showParticleEffect = false;
            game.player.statusEffects.add(poison);
            poison.tick(game.player, 59f);
            host.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(actor, 1L, game.player));
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(game.level));
            host.activateCampaignFloor("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> game.level = level);
            combat.prepare(game);
            assertTrue("Carried native body keeps private status timer and identity",
                    game.player.statusEffects.first() == poison);
            assertEquals(441f, poison.timer, 0f);
        }
        finally { combat.dispose(); host.close(); Game.instance = previous;
            com.interrupt.managers.StringManager.localizedStrings = strings; }
    }

    @Test public void floorGenerationRemovesDormantDropsAndQueuedCuesOnConnectedObserver() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("observer-floor"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        store.campaignSaves().save(save(compatibility(), roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", roster.getSlot(2).getReconnectToken());
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                identity('2'), roster.getSlot(2).getPresentation(), 2, tokens, compatibility());
        try {
            readyClient(client);
            startReadySession(host); awaitPhase(client, DirectConnectPhase.READY); host.setSessionPaused(true);
            final Level[] active = { nativeDormancyFloor() };
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            PhysicalItemState dropped = host.getItemWorld().spawn("dormant-drop", null, 2f, 2f, 0f);
            host.publishPhysicalItems();
            awaitCondition(() -> client.getPhysicalItems().size() == 2);
            host.publishTriggerSound(1001L);
            long generation = host.getNativeWorldGeneration();
            host.activateCampaignFloor("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> active[0] = level);
            awaitCondition(() -> client.getNativeWorldGeneration() > generation);
            awaitCondition(() -> client.getCombatSnapshot() != null
                    && client.getCombatSnapshot().getSequence() >= host.getCombatSnapshot().getSequence());
            assertFalse("Destination receives reliable movement while paused", client.getMovementSnapshots().isEmpty());
            for(com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot snapshot : client.getMovementSnapshots())
                for(MovementEntityState body : snapshot.getEntities())
                    assertTrue("Old-floor coordinates cannot remain in interpolation history", body.getX() < 4f);
            assertEquals("Carried inventory follows Slot; dormant world drops leave observer", 1,
                    client.getPhysicalItems().size());
            assertEquals(8L, client.getPhysicalItems().get(0).entityId);
            assertTrue(client.drainTriggerPresentations().isEmpty());
            assertTrue(client.getDoorSnapshots().isEmpty());
            assertTrue(client.getBreakableSnapshots().isEmpty());
            assertTrue(host.isSessionPaused());
            assertTrue(host.getItemWorld().get(dropped.entityId) == null);
        }
        finally { client.close(); host.close(); }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(5L);
        assertTrue("Timed out waiting for authoritative state", condition.getAsBoolean());
    }

    @Test public void exhaustedDropTimerFreezesWhileAnotherFloorRunsAndSurvivesColdSave() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("frozen-drop-timer"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        CampaignSave base = save(compatibility(), roster);
        PhysicalItemState drop = new PhysicalItemState(19L, 5L, "timed-scatter", null,
                2f, 2f, 0f, new ItemProperties(2, 1, "", "", 1));
        List<PhysicalItemState> items = new ArrayList<>(base.getPhysicalItems()); items.add(drop);
        CampaignSave initial = new CampaignSave(base.getCompatibility(), base.getCampaignId(),
                base.getCapacity(), base.getStartingLives(), base.getOutcome(), base.getFloorId(),
                base.getFloorSeed(), base.getFloorFingerprint(), base.getNativeWorldGeneration(),
                base.getSlots(), base.getParticipants(), items, base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects());
        store.campaignSaves().save(initial.withFloorHistory(base.getFloorId(), Collections.emptyList(),
                Collections.singletonMap(19L, 120L)));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            final Level[] active = { nativeDormancyFloor() };
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            long remaining = host.persistCampaign().getDropTimers().get(19L);
            String origin = host.persistCampaign().getActiveAreaKey();
            host.activateCampaignFloor("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> active[0] = level);
            long startingTick = host.getCombatSnapshot().getHostTick();
            host.setSessionPaused(false);
            awaitCondition(() -> host.getCombatSnapshot().getHostTick() >= startingTick + 150L);
            host.setSessionPaused(true);
            CampaignSave checkpoint = host.persistCampaign();
            assertEquals(remaining, checkpoint.getDormantFloors().get(0).getDropTimers().get(19L).longValue());
            assertFalse(checkpoint.getDormantFloors().get(0).getWorldItems().get(0).consumed);
            assertEquals(remaining, store.campaignSaves().load("friends", compatibility())
                    .getDormantFloors().get(0).getDropTimers().get(19L).longValue());
            host.activateCampaignFloor(origin, base.getFloorId(), 999L, null,
                    () -> { throw new AssertionError("Visited floor must restore"); }, level -> active[0] = level);
            assertEquals(remaining, host.persistCampaign().getDropTimers().get(19L).longValue());
            assertFalse(host.getItemWorld().get(19L).consumed);
        }
        finally { host.close(); }
    }

    private static Level nativeDormancyFloor() {
        Level floor = new Level(4, 4);
        for(int index = 0; index < floor.tiles.length; index++)
            floor.tiles[index] = com.interrupt.dungeoneer.tiles.Tile.EmptyTile();
        return floor;
    }

    @Test public void dormantItemIdentityCannotWrapOnColdResume() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("item-id-exhaustion"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        CampaignSave base = save(compatibility(), roster);
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState dormant =
                new com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState("dungeon:old", "levels/old.bin",
                        321L, null, com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(nativeDormancyFloor()),
                        Collections.singletonList(new PhysicalItemState(Long.MAX_VALUE, 1L, "old-drop", null,
                                1f, 1f, 0f, new ItemProperties(2, 1, "", "", 1))), null,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        store.campaignSaves().save(base.withFloorHistory(base.getFloorId(),
                Collections.singletonList(dormant), Collections.emptyMap()));
        DirectConnectHost host = null;
        try {
            host = DirectConnectHost.start(0, compatibility(), roster, store);
            org.junit.Assert.fail("Exhausted global identity cannot wrap and reuse existing drops");
        }
        catch(IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("identity space exhausted"));
        }
        finally { if(host != null) host.close(); }
    }

    @Test public void sourceCommandsQueuedAtPauseCannotMovePartyOnDestination() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("floor-command-fence"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        store.campaignSaves().save(save(compatibility(), roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            final Level[] active = { nativeDormancyFloor() };
            host.setNativeFloorCapture(() -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(active[0]));
            java.lang.reflect.Field field = DirectConnectHost.class.getDeclaredField("movementSession");
            field.setAccessible(true);
            com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSession commands =
                    (com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSession)field.get(host);
            long priorInput = host.persistCampaign().getParticipant(1).getMovement().getLastProcessedInputTick();
            // Represents input accepted immediately before Party pause, still waiting for next tick.
            commands.submit(new com.interrupt.dungeoneer.multiplayer.movement.MovementInputCommand(
                    new ParticipantId("campaign-slot-1"),
                    new com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame(
                            priorInput + 1L, 1f, 0f, 0f, false)));
            host.activateCampaignFloor("dungeon:2", "levels/second.bin", 123L, null,
                    DirectConnectCampaignPersistenceTest::nativeDormancyFloor, level -> active[0] = level);
            long start = commands.getHostTick();
            host.setSessionPaused(false);
            awaitCondition(() -> commands.getHostTick() >= start + 12L);
            host.setSessionPaused(true);
            assertEquals("Prior-floor command cannot run on destination", priorInput,
                    host.persistCampaign().getParticipant(1).getMovement().getLastProcessedInputTick());
        }
        finally { host.close(); }
    }

    @Test public void coldResumeRetainsUnspentPartyKeysWithHostSubset() throws Exception {
        File root = temporaryFolder.newFolder("party-keys");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost first = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(first);
            first.getItemWorld().initializePartyKeys(3);
            assertTrue(first.getItemWorld().spendPartyKey());
            assertEquals(2, first.getPartyKeys());
            first.persistCampaign();
        }
        finally { first.close(); }
        DirectConnectHost resumed = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(resumed);
            assertEquals("Absent collector does not lose Party's remaining keys", 2,
                    resumed.getPartyKeys());
        }
        finally { resumed.close(); }
    }

    @Test public void nativeStoryHistorySurvivesColdResumeWithoutReplacingSlotGoldOrUpgrades()
            throws Exception {
        File root = temporaryFolder.newFolder("party-story");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings =
                new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
        DirectConnectHost first = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(first);
            Game game = partyStoryGame();
            DirectConnectItemController items = new DirectConnectItemController(first);
            items.prepare(game);
            game.progression.progressionTriggers.put("quest:gate", "opened");
            game.progression.untilDeathProgressionTriggers.put("story:lever", "pulled");
            game.progression.messagesSeen.put("campfire", 2);
            game.progression.uniqueItemsSpawned.add("Axe of Testing");
            game.progression.uniqueTilesSeen.add("secret-room");
            game.progression.dungeonAreasSeen.add("CAVES");
            game.progression.sawTutorial = true;
            game.progression.partySecretsFound = 2;
            items.update(game);
            first.persistCampaign();
        }
        finally { first.close(); }
        DirectConnectHost resumed = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(resumed);
            Game game = partyStoryGame();
            game.progression.gold = 97;
            game.progression.inventoryUpgrades = 3;
            game.progression.hotbarUpgrades = 2;
            game.initializePartyProgression(resumed.getPartyProgression());
            com.interrupt.dungeoneer.entities.triggers.TriggeredMessage dialogue =
                    new com.interrupt.dungeoneer.entities.triggers.TriggeredMessage();
            dialogue.messageFile = "first.dat,second.dat,third.dat,last.dat";
            dialogue.progressionKey = "campfire";
            dialogue.init(game.level, Level.Source.LEVEL_START);
            assertEquals("last.dat", dialogue.messageFile);
            game.progression.progressionTriggers.put("native:initialized", "yes");
            new DirectConnectItemController(resumed).prepare(game);
            assertEquals("Native init facts survive bridge attach", "yes",
                    game.progression.progressionTriggers.get("native:initialized"));
            assertEquals(2, game.progression.partySecretsFound);
            assertEquals("opened", game.progression.progressionTriggers.get("quest:gate"));
            assertEquals("pulled", game.progression.untilDeathProgressionTriggers.get("story:lever"));
            assertEquals(Integer.valueOf(2), game.progression.messagesSeen.get("campfire"));
            assertTrue(game.progression.uniqueItemsSpawned.contains("Axe of Testing", false));
            assertTrue(game.progression.uniqueTilesSeen.contains("secret-room", false));
            assertTrue(game.progression.dungeonAreasSeen.contains("CAVES", false));
            assertTrue(game.progression.sawTutorial);
            assertEquals(97, game.progression.gold);
            assertEquals(3, game.progression.inventoryUpgrades);
            assertEquals(2, game.progression.hotbarUpgrades);
        }
        finally {
            resumed.close();
            Game.instance = previous;
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
        }
    }

    @Test public void nativeVictoryArchivesCompletedPartyFactsAtCaptureBoundary() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporaryFolder.newFolder("party-victory"),
                new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host);
            com.interrupt.dungeoneer.game.Progression nativeState = new com.interrupt.dungeoneer.game.Progression();
            nativeState.won = true;
            nativeState.sawTutorial = true;
            host.setNativeFloorCapture(() -> {
                host.publishPartyProgression(nativeState);
                return new byte[] { 1, 2, 3 };
            });
            CampaignSave captured = host.persistCampaign();
            assertEquals(CampaignSave.Outcome.COMPLETED, captured.getOutcome());
            assertTrue(captured.getPartyProgression().victory);
            assertTrue(store.campaignSaves().isArchived(roster.getCampaignId()));
        }
        finally { host.close(); }
    }

    private Game partyStoryGame() {
        Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.player.inventory.clear();
        game.progression = new com.interrupt.dungeoneer.game.Progression();
        game.level = new Level(4, 4);
        Game.instance = game;
        return game;
    }

    @Test public void coldResumeStartsWithHostSubsetAndPreservesAbsentSlotState()
            throws Exception {
        File root = temporaryFolder.newFolder("campaigns");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        CampaignSlot friend = roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            host.setPlayerReady(true);
            assertTrue(host.canStartSession());
            startReadySession(host);

            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            assertEquals(1, host.getConnectedParticipantCount());
            assertEquals(PartyMemberState.DISCONNECTED,
                    host.getPartyStatus().getMember(friend.getNumber()).getState());
            assertEquals(37, gold(host.getParticipantProgress(), new ParticipantId("campaign-slot-2")));
            assertEquals(1, host.getPhysicalItems().size());
            assertEquals(1, host.getDoorSnapshots().size());
            assertEquals(1, host.getBreakableSnapshots().size());
            assertEquals(1, host.getActorEffects().size());

            CampaignSave checkpoint = host.persistCampaign();
            assertEquals(PartyMemberState.DOWNED,
                    checkpoint.getParticipant(2).getParty().getState());
            assertEquals(3, checkpoint.getParticipant(2).getParty().getHealth());
        }
        finally {
            host.close();
        }

        CampaignSave reloaded = store.campaignSaves().load("friends", compatibility);
        assertEquals(PartyMemberState.DOWNED, reloaded.getParticipant(2).getParty().getState());
        assertEquals(37, reloaded.getParticipant(2).getProgress().gold);
        assertEquals(3, reloaded.getCombat().getCombatant(
                "participant:campaign-slot-2").getHealth());
        assertEquals(400f, reloaded.getActorEffects().get(0).effects.get(0).remaining, 0f);
    }

    @Test public void coldResumeSendsSavedWorldGenerationAndStateBeforeClientInteraction()
            throws Exception {
        File root = temporaryFolder.newFolder("resumed-world");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        CampaignSlot friend = roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        CampaignSave base = save(compatibility, roster);
        com.interrupt.dungeoneer.game.Progression story = new com.interrupt.dungeoneer.game.Progression();
        story.progressionTriggers.put("resume:party-gate", "opened");
        story.sawTutorial = true; story.partySecretsFound = 2;
        store.campaignSaves().save(new CampaignSave(base.getCompatibility(), base.getCampaignId(),
                base.getCapacity(), base.getStartingLives(), base.getOutcome(), base.getFloorId(),
                base.getFloorSeed(), base.getFloorFingerprint(), base.getNativeWorldGeneration(),
                base.getSlots(), base.getParticipants(), base.getPhysicalItems(), base.getCombat(),
                base.getDoors(), base.getBreakables(), base.getActorEffects(), base.getMonsterSpawns(),
                base.getConsumedMonsterSpawners(), base.getNativeFloor(), 2, 3L,
                com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(story, 7L)));
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", friend.getReconnectToken());

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1",
                host.getBoundPort(), identity('2'), friend.getPresentation(), 2,
                tokens, compatibility);
        try {
            readyClient(client);
            startReadySession(host);
            awaitPhase(client, DirectConnectPhase.READY);

            assertEquals(4L, host.getNativeWorldGeneration());
            assertEquals(4L, client.getNativeWorldGeneration());
            assertEquals("opened", client.getPartyProgression().persistent.get("resume:party-gate"));
            assertTrue(client.getPartyProgression().tutorialCompleted);
            assertEquals(2, client.getPartyProgression().secretsFound);
            assertEquals(2, client.getPartyKeys());
            assertEquals(1, client.getPhysicalItems().size());
            assertEquals(1, client.getDoorSnapshots().size());
            assertEquals(1, client.getBreakableSnapshots().size());
            assertEquals(1, client.getActorEffects().size());

            client.submitItemAction(client.getNextItemRequestId(), ItemAction.DROP, 8L);
            ItemRequest request = awaitItemRequest(host);
            assertEquals(4L, request.worldGeneration);
            assertEquals(new ParticipantId("campaign-slot-2"), request.getParticipantId());
        }
        finally {
            client.close();
            host.close();
        }
    }

    @Test public void coldResumeKeepsLateMonstersRebuildableAndReplaysThemToClients()
            throws Exception {
        File root = temporaryFolder.newFolder("resumed-late-monsters");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        CampaignSlot friend = roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        CampaignSave base = save(compatibility, roster);
        List<CombatantSnapshot> combatants =
                new ArrayList<CombatantSnapshot>(base.getCombat().getCombatants());
        combatants.add(new CombatantSnapshot("monster:1", CombatantKind.MONSTER, 2, 4));
        // A floor spawner's slime: the rebuilt floor holds only its spawner, not the slime.
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), 3, CampaignSave.Outcome.ACTIVE, base.getFloorId(),
                base.getFloorSeed(), null, base.getNativeWorldGeneration(), roster.getSlots(),
                base.getParticipants(), base.getPhysicalItems(),
                new CombatSnapshot(9L, 120L, Collections.singletonList(
                        new MonsterSnapshot("monster:1", "", 5f, 6f, 0.5f)), combatants),
                base.getDoors(), base.getBreakables(), base.getActorEffects(),
                Collections.singletonList(new NativeMonsterSpawn("monster:1", "DUNGEON",
                        "SLIME", 4f, 4f, 0.5f, 4, 4)),
                Collections.singletonList("spawner:slime")));
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", friend.getReconnectToken());

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1",
                host.getBoundPort(), identity('2'), friend.getPresentation(), 2,
                tokens, compatibility);
        try {
            readyClient(client);
            startReadySession(host);
            awaitPhase(client, DirectConnectPhase.READY);

            assertEquals(Arrays.asList("monster:1"),
                    monsterIds(host.getRestoredNativeMonsterSpawns()));
            assertTrue("Consumed spawner must not add the slime again",
                    host.isNativeMonsterSpawnerConsumed("spawner:slime"));
            assertEquals("Client materializes the saved slime from the replayed spawn",
                    Arrays.asList("monster:1"), monsterIds(client.drainNativeMonsterSpawns()));

            CampaignSave checkpoint = host.persistCampaign();
            assertEquals(Arrays.asList("monster:1"), monsterIds(checkpoint.getMonsterSpawns()));
            assertEquals(Arrays.asList("spawner:slime"), checkpoint.getConsumedMonsterSpawners());
        }
        finally {
            client.close();
            host.close();
        }
    }

    @Test public void coldResumeKeepsSavedDoorsAndBreakablesUnderHostAuthority()
            throws Exception {
        File root = temporaryFolder.newFolder("resumed-world-objects");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        Breakable vase = new Breakable();
        vase.x = vase.y = 1.5f;
        Door door = new Door();
        door.x = door.y = 2.5f;
        CampaignSave base = save(compatibility, roster);
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), base.getStartingLives(), base.getOutcome(),
                base.getFloorId(), base.getFloorSeed(), base.getFloorFingerprint(),
                base.getNativeWorldGeneration(), base.getSlots(), base.getParticipants(),
                Collections.<PhysicalItemState>emptyList(), base.getCombat(),
                Collections.singletonList(new DoorSnapshot(worldObjectId(door), 6L,
                        Door.DoorState.OPEN.ordinal(), false, true, false,
                        2.5f, 2.5f, 0f, 86f, 0f)),
                Collections.singletonList(new BreakableSnapshot(worldObjectId(vase), 5L, 1,
                        true, true, 1.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
                base.getActorEffects()));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        try {
            com.interrupt.managers.StringManager.localizedStrings =
                    new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
            startReadySession(host);
            Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.player.inventory.clear();
            game.level = new Level(4, 4);
            game.level.entities.add(vase);
            game.level.entities.add(door);
            Game.instance = game;

            new DirectConnectItemController(host).prepare(game);

            assertEquals(Door.DoorState.OPEN, door.doorState);
            assertNull("Host door must stay native, not a frozen client replica",
                    networkSnapshot(door));
            assertEquals(1, vase.hp);
            assertFalse("Host breakable must stay breakable", vase.nativePresentationReplica);
        }
        finally {
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
            Game.instance = previous;
            host.close();
        }
    }

    @Test public void checkpointKeepsLastFloorUntilHostEntersItThenSavesLiveFloor()
            throws Exception {
        File root = temporaryFolder.newFolder("floor-checkpoint");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        byte[] oldFloor = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(nativeDormancyFloor());
        Level nextFloor = nativeDormancyFloor(); nextFloor.tiles[0].floorHeight = 0.25f;
        byte[] liveFloor = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(nextFloor);
        store.campaignSaves().save(withFloor(save(compatibility, roster), oldFloor));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host);

            assertArrayEquals(oldFloor, host.takeRestoredNativeFloor());
            assertNull("Saved floor loads once", host.takeRestoredNativeFloor());
            assertArrayEquals("A checkpoint before the floor is live must keep the saved floor",
                    oldFloor, host.persistCampaign().getNativeFloor());

            host.setNativeFloorCapture(() -> liveFloor);
            assertArrayEquals(liveFloor, host.persistCampaign().getNativeFloor());
        }
        finally {
            host.close();
        }
        assertArrayEquals(liveFloor,
                store.campaignSaves().load("friends", compatibility).getNativeFloor());
    }

    @Test public void restoredHostFloorKeepsSavedWorldObjectIdsAndItsNativeState()
            throws Exception {
        File root = temporaryFolder.newFolder("restored-world-objects");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        CampaignSave base = save(compatibility, roster);
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), base.getStartingLives(), base.getOutcome(),
                base.getFloorId(), base.getFloorSeed(), base.getFloorFingerprint(),
                base.getNativeWorldGeneration(), base.getSlots(), base.getParticipants(),
                Collections.<PhysicalItemState>emptyList(), base.getCombat(),
                Collections.singletonList(new DoorSnapshot(4242L, 6L,
                        Door.DoorState.CLOSED.ordinal(), false, true, true,
                        2.5f, 2.5f, 0f, 0f, 1f)),
                base.getBreakables(), base.getActorEffects()));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        try {
            com.interrupt.managers.StringManager.localizedStrings =
                    new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
            startReadySession(host);
            Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.player.inventory.clear();
            game.level = new Level(4, 4);
            game.level.restoredCampaignFloor = true;
            // Opened since its floor was built: its placement no longer names it.
            Door door = new Door();
            door.x = 2.9f;
            door.y = 2.2f;
            door.doorState = Door.DoorState.OPEN;
            door.multiplayerIdentity = "object:4242";
            game.level.entities.add(door);
            Game.instance = game;

            DirectConnectItemController items = new DirectConnectItemController(host);
            items.prepare(game);

            assertEquals(4242L, items.worldObjectIdentity(door));
            assertEquals("Checkpoint state is newer than the saved door record",
                    Door.DoorState.OPEN, door.doorState);
            assertNull(networkSnapshot(door));
        }
        finally {
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
            Game.instance = previous;
            host.close();
        }
    }

    @Test public void effectsOfAMonsterWithoutACombatSlotNeverCostTheCampaignItsSave()
            throws Exception {
        File root = temporaryFolder.newFolder("effects-without-slot");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host);
            // The tutorial finale: a Monster Host saw burning had no combat slot left.
            host.synchronizeNativeActorEffects(new ActorEffectsSnapshot("monster:300", 1L, false,
                    Collections.singletonList(new NativeStatusEffectState(4L,
                            NativeStatusEffectState.Kind.SLOW, 200f, 0.5f, "", true, 10f, 1f, 0L))));

            CampaignSave saved = host.persistCampaign();

            for(ActorEffectsSnapshot effects : saved.getActorEffects()) {
                assertFalse(effects.monsterId.equals("monster:300"));
            }
        }
        finally {
            host.close();
        }
        store.campaignSaves().load("friends", compatibility);
    }

    @Test public void fullEffectsTableSkipsNewActorsInsteadOfStoppingTheHost() throws Exception {
        File root = temporaryFolder.newFolder("effects-table-full");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host);
            for(int index = 1; index <= DirectConnectProtocol.MAX_MONSTERS + 8; index++) {
                host.synchronizeNativeActorEffects(new ActorEffectsSnapshot("monster:" + index, 1L,
                        false, Collections.<NativeStatusEffectState>emptyList()));
            }

            assertEquals(DirectConnectProtocol.MAX_MONSTERS + 4, host.getActorEffects().size());
            host.persistCampaign();
        }
        finally {
            host.close();
        }
    }

    @Test public void spectatorSavedWhileDisconnectedResumesAsSpectator() throws Exception {
        File root = temporaryFolder.newFolder("saved-spectator");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        CampaignSlot friend = roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        CampaignSave base = save(compatibility, roster);
        // Out of Lives and gone before the Campaign was saved: presence says disconnected.
        CampaignSave.ParticipantState spectated = participant(friend, 17.5f, 0, 0,
                PartyMemberState.SPECTATING, 11, false);
        List<CampaignSave.ParticipantState> participants = new ArrayList<CampaignSave.ParticipantState>();
        participants.add(base.getParticipant(1));
        participants.add(new CampaignSave.ParticipantState(2, new PartyMemberStatus(2, null,
                "Friend", AvatarCatalog.HUMANOID_2, 0, 8, 0, PartyMemberState.DISCONNECTED,
                0, 0, 0), spectated.getMovement(), spectated.getProgress(), false));
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), base.getStartingLives(), base.getOutcome(),
                base.getFloorId(), base.getFloorSeed(), base.getFloorFingerprint(),
                base.getNativeWorldGeneration(), base.getSlots(), participants,
                base.getPhysicalItems(), base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects()));
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", friend.getReconnectToken());

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1",
                host.getBoundPort(), identity('2'), friend.getPresentation(), 2,
                tokens, compatibility);
        try {
            readyClient(client);
            startReadySession(host);
            awaitPhase(client, DirectConnectPhase.READY);

            PartyMemberStatus returned = host.getPartyStatus().getMember(2);
            assertEquals("Out of Lives returns as a Spectator",
                    PartyMemberState.SPECTATING, returned.getState());
            assertEquals(0, returned.getRemainingLives());
            assertEquals(0, host.persistCampaign().getParticipant(2).getParty().getRemainingLives());
        }
        finally {
            client.close();
            host.close();
        }
    }

    @Test public void clientStandingOnHostPressurePlateSetsOffItsSpikes() throws Exception {
        File root = temporaryFolder.newFolder("remote-pressure-plate");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        CampaignSave base = save(compatibility, roster);
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), base.getStartingLives(), base.getOutcome(),
                base.getFloorId(), base.getFloorSeed(), base.getFloorFingerprint(),
                base.getNativeWorldGeneration(), base.getSlots(), base.getParticipants(),
                Collections.<PhysicalItemState>emptyList(), base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects()));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        try {
            com.interrupt.managers.StringManager.localizedStrings =
                    new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
            startReadySession(host);
            Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.player.inventory.clear();
            game.progression = new com.interrupt.dungeoneer.game.Progression();
            game.level = new Level(6, 6);
            // The tutorial trap: a pressure plate Mover whose press fires the SPIKES.
            com.interrupt.dungeoneer.entities.Mover plate = new com.interrupt.dungeoneer.entities.Mover();
            plate.moverMode = com.interrupt.dungeoneer.entities.Mover.MoverStartMode.ON_ANY_TOUCH;
            plate.moverEndMode = com.interrupt.dungeoneer.entities.Mover.MoverEndMode.PRESSURE;
            plate.movesBy.set(0f, 0f, -0.03f);
            plate.moveTime = 0.4f;
            plate.endWait = 6f;
            plate.triggersIdWhenDone = "SPIKES";
            plate.x = plate.y = 2.5f;
            plate.collision.set(0.37f, 0.37f, 0.125f);
            plate.isSolid = true;
            TriggerCounter spikes = new TriggerCounter();
            spikes.id = "SPIKES";
            spikes.x = spikes.y = 3.5f;
            game.level.entities.add(plate);
            game.level.entities.add(spikes);
            com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar friend =
                    new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(
                            new com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor(
                                    1L, new NetworkEntityId(2L), new ParticipantId("campaign-slot-2"),
                                    2, "Friend", AvatarCatalog.HUMANOID_2));
            friend.x = friend.y = 2.5f;
            friend.z = 0.125f;
            game.level.non_collidable_entities.add(friend);
            // Host movement has the Friend standing on the plate.
            com.interrupt.dungeoneer.multiplayer.movement.NativeMovementBodies bodies =
                    new com.interrupt.dungeoneer.multiplayer.movement.NativeMovementBodies();
            bodies.updateParticipants(Collections.singletonList(friend), Collections.singletonList(
                    new com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState(
                            new NetworkEntityId(2L), 1L, 0L, 2.5f, 2.5f, 0.125f, 0f, 0f, 0f, 0f,
                            com.interrupt.dungeoneer.multiplayer.movement.MovementState.IDLE)));
            game.level.movementBodies = bodies;
            Game.instance = game;

            DirectConnectItemController items = new DirectConnectItemController(host);
            items.prepare(game);
            float lowest = plate.z;
            for(int frame = 0; frame < 240 && spikes.triggered == 0; frame++) {
                items.update(game);
                game.level.updateSpatialHash(null);
                plate.tick(game.level, 1f);
                lowest = Math.min(lowest, plate.z);
            }

            assertTrue("The plate sinks under the Friend: " + lowest, lowest < -0.01f);
            assertEquals("Its press fires the spikes once", 1, spikes.triggered);
            assertTrue("Clients get the plate's position",
                    !host.getMoverSnapshots().isEmpty());
        }
        finally {
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
            Game.instance = previous;
            host.close();
        }
    }

    /** Counts native triggers aimed at its id. */
    private static final class TriggerCounter extends com.interrupt.dungeoneer.entities.Entity {
        int triggered;

        @Override public void onTrigger(com.interrupt.dungeoneer.entities.Entity instigator, String value) {
            triggered++;
        }
    }

    @Test public void clientAvatarSetsOffHostTouchTriggerOnlyForItsOwnScreen() throws Exception {
        File root = temporaryFolder.newFolder("remote-touch-trigger");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        CampaignSave base = save(compatibility, roster);
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), base.getStartingLives(), base.getOutcome(),
                base.getFloorId(), base.getFloorSeed(), base.getFloorFingerprint(),
                base.getNativeWorldGeneration(), base.getSlots(), base.getParticipants(),
                Collections.<PhysicalItemState>emptyList(), base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects()));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        Game previous = Game.instance;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        try {
            com.interrupt.managers.StringManager.localizedStrings =
                    new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
            startReadySession(host);
            Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.player.inventory.clear();
            game.progression = new com.interrupt.dungeoneer.game.Progression();
            game.level = new Level(4, 4);
            // The tutorial spider trap: a touch trigger a client walks into.
            ScreenProbe trap = new ScreenProbe();
            trap.triggerType = com.interrupt.dungeoneer.entities.triggers.Trigger.TriggerType.PLAYER_TOUCHED;
            trap.x = trap.y = 2.5f;
            trap.collision.set(0.5f, 0.5f, 0.5f);
            ScreenProbe elsewhere = new ScreenProbe();
            elsewhere.triggerType = trap.triggerType;
            elsewhere.x = elsewhere.y = 0.5f;
            elsewhere.collision.set(0.2f, 0.2f, 0.5f);
            game.level.entities.add(trap);
            game.level.entities.add(elsewhere);
            com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar friend =
                    new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(
                            new com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor(
                                    1L, new NetworkEntityId(2L), new ParticipantId("campaign-slot-2"),
                                    2, "Friend", AvatarCatalog.HUMANOID_2));
            friend.x = friend.y = 2.6f;
            game.level.non_collidable_entities.add(friend);
            Game.instance = game;

            DirectConnectItemController items = new DirectConnectItemController(host);
            items.prepare(game);
            items.update(game);

            assertEquals("Host fires the trap its client walked into",
                    com.interrupt.dungeoneer.entities.triggers.Trigger.TriggerStatus.TRIGGERED,
                    trap.getTriggerStatus());
            List<String> delivered = new ArrayList<String>();
            game.level.nativeTriggerPresentationListener = (trigger, who, value) ->
                    delivered.add(who.getValue());
            trap.doTriggerEvent("");
            assertEquals("Host targets client UI; Host screen stays clear",
                    0, trap.presented);
            assertEquals(Collections.singletonList("campaign-slot-2"), delivered);
            assertEquals(com.interrupt.dungeoneer.entities.triggers.Trigger.TriggerStatus.WAITING,
                    elsewhere.getTriggerStatus());
        }
        finally {
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
            Game.instance = previous;
            host.close();
        }
    }

    private static final class ScreenProbe extends com.interrupt.dungeoneer.entities.triggers.Trigger {
        int presented;
        @Override public void presentToActivator(String value, boolean continuesChain) { presented++; }
    }

    private CampaignSave withFloor(CampaignSave base, byte[] floor) {
        return new CampaignSave(base.getCompatibility(), base.getCampaignId(), base.getCapacity(),
                base.getStartingLives(), base.getOutcome(), base.getFloorId(), base.getFloorSeed(),
                base.getFloorFingerprint(), base.getNativeWorldGeneration(), base.getSlots(),
                base.getParticipants(), base.getPhysicalItems(), base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects(), base.getMonsterSpawns(),
                base.getConsumedMonsterSpawners(), floor);
    }

    private long worldObjectId(Entity entity) throws Exception {
        java.lang.reflect.Method id = DirectConnectItemController.class
                .getDeclaredMethod("worldObjectId", String.class, int.class);
        id.setAccessible(true);
        return (Long)id.invoke(null, SharedFloorIdentity.placementKey(entity), 0);
    }

    private Object networkSnapshot(Door door) throws Exception {
        java.lang.reflect.Field snapshot = Door.class.getDeclaredField("networkSnapshot");
        snapshot.setAccessible(true);
        return snapshot.get(door);
    }

    @Test public void joiningClientGetsHostFloorMarksAndOnlyItsOwnTriggerPresentations()
            throws Exception {
        File root = temporaryFolder.newFolder("marks-and-presentations");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        CampaignSlot friend = roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", friend.getReconnectToken());

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        // A blood pool Host's floor already had before this client arrived.
        host.recordNativeDecal(new com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState(
                4f, 5f, 0.2f, 0.05f, 0f, -0.95f, 12f, 0f, 0f, 0f, 0.01f, 1f, 20f, 0.8f, 0.8f,
                true, com.interrupt.dungeoneer.entities.Entity.ArtType.sprite.ordinal(), 16, "",
                1f, 1f, 1f, 1f), false);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1",
                host.getBoundPort(), identity('2'), friend.getPresentation(), 2,
                tokens, compatibility);
        try {
            readyClient(client);
            startReadySession(host);
            awaitPhase(client, DirectConnectPhase.READY);

            List<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState> marks =
                    client.drainNativeDecals();
            assertEquals(1, marks.size());
            assertEquals(16, marks.get(0).tex);

            host.deliverTriggerPresentation(new ParticipantId("campaign-slot-2"), 4242L, "sign.dat");
            List<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation> shown =
                    new ArrayList<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation>();
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(shown.isEmpty() && System.currentTimeMillis() < deadline) {
                shown.addAll(client.drainTriggerPresentations());
                Thread.sleep(10L);
            }
            assertEquals(1, shown.size());
            assertEquals(4242L, shown.get(0).objectId);
            assertEquals("sign.dat", shown.get(0).value);
        }
        finally {
            client.close();
            host.close();
        }
    }

    private List<String> monsterIds(List<NativeMonsterSpawn> spawns) {
        List<String> ids = new ArrayList<String>();
        for(NativeMonsterSpawn spawn : spawns) ids.add(spawn.monsterId);
        return ids;
    }

    @Test public void coldResumeReusesSavedPhysicalItemsInsteadOfRegisteringFreshDuplicates()
            throws Exception {
        File root = temporaryFolder.newFolder("resumed-host-items");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 2, null),
                new SecureRandom());
        store.save(roster);
        DirectConnectCompatibility compatibility = compatibility();
        Armor starter = new Armor();
        starter.name = "Saved armor";
        starter.equipLoc = "ARMOR";
        PhysicalItemState savedItem = new PhysicalItemState(8L, 4L,
                templateId(starter), new ParticipantId("campaign-slot-1"),
                0f, 0f, 0f, ItemProperties.DEFAULT);
        CampaignSave base = save(compatibility, roster);
        store.campaignSaves().save(new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), base.getStartingLives(), base.getOutcome(),
                base.getFloorId(), base.getFloorSeed(), base.getFloorFingerprint(),
                base.getNativeWorldGeneration(), base.getSlots(), base.getParticipants(),
                Collections.singletonList(savedItem), base.getCombat(), base.getDoors(),
                base.getBreakables(), base.getActorEffects()));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        Game previous = Game.instance;
        com.interrupt.managers.HUDManager previousHudManager = Game.hudManager;
        com.interrupt.dungeoneer.ui.Hud previousHud = Game.hud;
        HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        try {
            com.interrupt.managers.StringManager.localizedStrings =
                    new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
            Game.hudManager = new com.interrupt.managers.HUDManager();
            Game.hudManager.quickSlots = new com.interrupt.dungeoneer.ui.Hotbar() {
                @Override public void refresh() { }
            };
            Game.hudManager.backpack = new com.interrupt.dungeoneer.ui.Hotbar() {
                @Override public void refresh() { }
            };
            Game.hud = new com.interrupt.dungeoneer.ui.Hud() {
                @Override public void refresh() { }
                @Override public void refreshEquipLocations() { }
            };
            startReadySession(host);
            awaitMovementSnapshot(host);
            Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.player.inventory.clear();
            game.player.inventory.add(starter);
            game.level = new Level(4, 4);
            Game.instance = game;

            DirectConnectItemController items = new DirectConnectItemController(host);
            items.prepare(game);

            assertEquals("Resume must not mint fresh starter identities", 1,
                    host.getPhysicalItems().size());
            assertEquals(8L, items.physicalIdentity(game.player.inventory.first()));
        }
        finally {
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
            Game.hudManager = previousHudManager;
            Game.hud = previousHud;
            Game.instance = previous;
            host.close();
        }
    }

    @Test public void confirmedSaveQuitPersistsBeforeNotifyingRemoteAndMarksRunClean() throws Exception {
        File root = temporaryFolder.newFolder("confirmed-quit");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        CampaignSlot friend = roster.getSlot(2);
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", friend.getReconnectToken());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                identity('2'), friend.getPresentation(), 2, tokens, compatibility);
        try {
            readyClient(client);
            startReadySession(host); awaitPhase(client, DirectConnectPhase.READY);
            host.setNativeFloorCapture(() -> new byte[] { 7, 8 });
            host.saveAndQuit();
            awaitPhase(client, DirectConnectPhase.DISCONNECTED);
            assertTrue(client.getStatus().getMessage().contains("saved"));
            assertArrayEquals(new byte[] { 7, 8 }, store.campaignSaves().load("friends", compatibility).getNativeFloor());
            assertFalse(store.campaignSaves().needsRecovery("friends"));
            assertFalse(new File(new File(root, "friends"), "session.running").exists());
            assertEquals(DirectConnectPhase.CLOSED, host.getStatus().getPhase());
        }
        finally { client.close(); host.close(); }
    }

    @Test public void failedNativeCaptureRetainsSaveAndConfirmedQuitKeepsSessionAlive() throws Exception {
        File root = temporaryFolder.newFolder("capture-failure");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            host.setNativeFloorCapture(() -> new byte[] { 1 }); host.persistCampaign();
            host.setSessionPaused(false);
            host.setNativeFloorCapture(() -> { throw new IllegalStateException("Injected native capture failure"); });
            try { host.saveAndQuit(); org.junit.Assert.fail("Native state cannot be silently discarded."); }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("native capture failure")); }
            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            assertFalse(host.isSessionPaused());
            assertArrayEquals(new byte[] { 1 }, store.campaignSaves().load("friends", compatibility).getNativeFloor());
            assertTrue(new File(new File(root, "friends"), "session.running").isFile());
        }
        finally { host.setNativeFloorCapture(() -> new byte[] { 1 }); host.close(); }
    }

    @Test public void recoverySnapshotsUseMinuteWallClockEvenWhilePartyPaused() throws Exception {
        File root = temporaryFolder.newFolder("snapshot-cadence");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = persistenceRoster(store);
        DirectConnectCompatibility compatibility = compatibility();
        store.campaignSaves().save(save(compatibility, roster));
        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            startReadySession(host); host.setSessionPaused(true);
            host.setNativeFloorCapture(() -> new byte[] { 1 });
            host.updateCampaignRecovery(0L);
            long first = newestRecoveryTimestamp(root);
            host.updateCampaignRecovery(java.util.concurrent.TimeUnit.SECONDS.toNanos(59));
            assertEquals(first, newestRecoveryTimestamp(root));
            host.updateCampaignRecovery(java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
            assertTrue(newestRecoveryTimestamp(root) > first);
            long second = newestRecoveryTimestamp(root);
            host.beginNativeWorld(); host.beginNativeWorld(); // Resumed baseline then new generation.
            host.updateCampaignRecovery(java.util.concurrent.TimeUnit.SECONDS.toNanos(61));
            assertTrue(newestRecoveryTimestamp(root) > second);
            assertTrue(host.isSessionPaused());
        }
        finally { host.close(); }
    }

    private long newestRecoveryTimestamp(File root) {
        long newest = 0;
        for(File file : new File(root, "friends").listFiles()) {
            if(file.getName().startsWith("recovery-")) newest = Math.max(newest, file.lastModified());
        }
        return newest;
    }

    private CampaignRoster persistenceRoster(CampaignRosterStore store) {
        CampaignRoster roster = store.loadOrCreate("friends", 2, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        roster.approve(new SlotClaimRequest(identity('2'), new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2),
                2, null), new SecureRandom());
        store.save(roster);
        return roster;
    }

    private void awaitPhase(DirectConnectPeer peer, DirectConnectPhase phase)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getStatus().getPhase() == phase) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for " + phase + ": "
                + peer.getStatus().getMessage());
    }

    private ItemRequest awaitItemRequest(DirectConnectHost host) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            List<ItemRequest> requests = host.drainItemRequests();
            if(!requests.isEmpty()) return requests.get(0);
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for resumed-world item interaction.");
    }

    private void awaitMovementSnapshot(DirectConnectHost host) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(!host.getMovementSnapshots().isEmpty()) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for resumed Host movement state.");
    }

    private String templateId(Item item) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((item.getClass().getName()
                + "\n" + item.name + "\n" + item.tex).getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for(byte value : digest) key.append(String.format("%02x", value & 255));
        return key.toString();
    }

    private CampaignSave save(DirectConnectCompatibility compatibility, CampaignRoster roster) {
        List<CampaignSave.ParticipantState> participants =
                new ArrayList<CampaignSave.ParticipantState>();
        participants.add(participant(roster.getSlot(1), 16.5f, 5, 2,
                PartyMemberState.CONNECTED, 11, false));
        participants.add(participant(roster.getSlot(2), 17.5f, 3, 1,
                PartyMemberState.DOWNED, 37, true));
        List<CombatantSnapshot> combatants = Arrays.asList(
                new CombatantSnapshot("participant:campaign-slot-1",
                        CombatantKind.PARTICIPANT, 5, 8),
                new CombatantSnapshot("participant:campaign-slot-2",
                        CombatantKind.PARTICIPANT, 3, 8));
        PhysicalItemState item = new PhysicalItemState(8L, 4L, "saved-item",
                new ParticipantId("campaign-slot-2"), 0f, 0f, 0f,
                new ItemProperties(2, 1, "", "", 1));
        DoorSnapshot door = new DoorSnapshot(1001L, 6L, 2, true,
                true, true, 3f, 4f, 0.5f, 45f, 0.4f);
        BreakableSnapshot breakable = new BreakableSnapshot(1002L, 7L, 2,
                true, true, 5f, 6f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f);
        ActorEffectsSnapshot effects = new ActorEffectsSnapshot(
                "participant:campaign-slot-2", 8L, false,
                Collections.singletonList(new NativeStatusEffectState(9L,
                        NativeStatusEffectState.Kind.SLOW, 400f, 0.5f, "", true,
                        20f, 1f, 0L)));
        return new CampaignSave(compatibility, roster.getCampaignId(), roster.getCapacity(),
                3, CampaignSave.Outcome.ACTIVE, GameApplication.OPEN_SOURCE_TEST_LEVEL,
                12345L, null, 4L, roster.getSlots(), participants,
                Collections.singletonList(item),
                new CombatSnapshot(9L, 120L, Collections.emptyList(), combatants),
                Collections.singletonList(door), Collections.singletonList(breakable),
                Collections.singletonList(effects));
    }

    private CampaignSave.ParticipantState participant(CampaignSlot slot, float x, int health,
            int lives, PartyMemberState state, int gold, boolean orb) {
        ParticipantId id = new ParticipantId("campaign-slot-" + slot.getNumber());
        NetworkEntityId entity = new NetworkEntityId(slot.getNumber());
        PartyMemberStatus party = new PartyMemberStatus(slot.getNumber(), entity,
                slot.getPresentation().getNickname(), slot.getPresentation().getAvatarId(),
                health, 8, lives, state, state == PartyMemberState.DOWNED ? 400 : 0,
                0, 0);
        MovementEntityState movement = new MovementEntityState(entity,
                10L + slot.getNumber(), 100L, x, 16.5f, 0.5f,
                0f, 0f, 0f, 0f, MovementState.IDLE);
        ParticipantProgress progress = new ParticipantProgress(id, 2L, gold, 0, 1,
                4, 4, 4, 4, 4, 4, 0, 8, 20, 5);
        return new CampaignSave.ParticipantState(slot.getNumber(), party, movement, progress, orb);
    }

    private int gold(List<ParticipantProgress> values, ParticipantId participant) {
        for(ParticipantProgress value : values) {
            if(participant.equals(value.participantId)) return value.gold;
        }
        return -1;
    }

    private DirectConnectCompatibility compatibility() {
        return new DirectConnectCompatibility(DirectConnectProtocol.BUILD_ID,
                DirectConnectProtocol.OPEN_SOURCE_TEST_CONTENT_FORMAT, repeat('a'));
    }

    private LauncherIdentity identity(char value) {
        return new LauncherIdentity(repeat(value));
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        while(result.length() < 64) result.append(value);
        return result.toString();
    }

    private static final class MemoryReconnectTokens implements ReconnectTokenStore {
        private final Map<String, String> tokens = new HashMap<String, String>();
        @Override public String load(String campaignId) { return tokens.get(campaignId); }
        @Override public void save(String campaignId, String token) {
            tokens.put(campaignId, token);
        }
    }
}
