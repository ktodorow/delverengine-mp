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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DirectConnectCampaignPersistenceTest {
    private static final long TIMEOUT_MILLIS = 8000L;

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

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
            assertTrue(host.canStartSession());
            host.startSession();

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
        store.campaignSaves().save(save(compatibility, roster));
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        tokens.save("friends", friend.getReconnectToken());

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1",
                host.getBoundPort(), identity('2'), friend.getPresentation(), 2,
                tokens, compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.LOBBY);
            host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);

            assertEquals(4L, host.getNativeWorldGeneration());
            assertEquals(4L, client.getNativeWorldGeneration());
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
            awaitPhase(client, DirectConnectPhase.LOBBY);
            host.startSession();
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
            host.startSession();
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
        store.campaignSaves().save(withFloor(save(compatibility, roster), new byte[] { 1, 2, 3 }));

        DirectConnectHost host = DirectConnectHost.start(0, compatibility, roster, store);
        try {
            host.startSession();

            assertArrayEquals(new byte[] { 1, 2, 3 }, host.takeRestoredNativeFloor());
            assertNull("Saved floor loads once", host.takeRestoredNativeFloor());
            assertArrayEquals("A checkpoint before the floor is live must keep the saved floor",
                    new byte[] { 1, 2, 3 }, host.persistCampaign().getNativeFloor());

            host.setNativeFloorCapture(() -> new byte[] { 9, 9 });
            assertArrayEquals(new byte[] { 9, 9 }, host.persistCampaign().getNativeFloor());
        }
        finally {
            host.close();
        }
        assertArrayEquals(new byte[] { 9, 9 },
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
            host.startSession();
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
            host.startSession();
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
            host.startSession();
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
            awaitPhase(client, DirectConnectPhase.LOBBY);
            host.startSession();
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
            host.startSession();
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
            host.startSession();
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
            assertEquals("The client's own copy shows messages; Host's screen does not",
                    0, trap.presented);
            assertTrue("Nothing to send: that client ran its own copy", delivered.isEmpty());
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
            awaitPhase(client, DirectConnectPhase.LOBBY);
            host.startSession();
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
            host.startSession();
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
            awaitPhase(client, DirectConnectPhase.LOBBY);
            host.startSession(); awaitPhase(client, DirectConnectPhase.READY);
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
            host.startSession(); host.setSessionPaused(true);
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
            host.startSession(); host.setSessionPaused(true);
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
