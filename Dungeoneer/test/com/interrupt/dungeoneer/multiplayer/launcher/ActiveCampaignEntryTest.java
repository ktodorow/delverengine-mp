package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.network.*;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.lives.DirectConnectLivesController;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.GameInput;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.viewport.StretchViewport;
import com.interrupt.dungeoneer.ui.Hud;
import com.interrupt.managers.HUDManager;
import com.interrupt.managers.StringManager;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.readyClient;
import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.startReadySession;
import static org.junit.Assert.*;

/** Menu actions, real TCP/UDP and durable slots; native rendering remains owner acceptance. */
public class ActiveCampaignEntryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File previousProfile;
    private File profileRoot;

    @Before public void profile() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        profileRoot = temporary.newFolder("profile");
        MultiplayerProfile.initialize(profileRoot);
    }

    @After public void restoreProfile() {
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void activeConnectExplainsBodylessSpectatorAndFreshReturnWhileNativeWorldSynchronizes() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectClient friend = connect(host, '2', "Friend", "humanoid-2", false);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            readyClient(friend);
            startReadySession(host);
            await(() -> friend.getStatus().getPhase() == DirectConnectPhase.READY);
            open(flow, host, '3', "Late", "humanoid-3", true);
            DirectConnectPeer late = flow.getPeer();
            await(() -> late.getStatus().getPhase() == DirectConnectPhase.SYNCHRONIZING
                    && late.getPendingAdmissionCheckpoint() > 0L);
            assertTrue("Connect must explain bodyless Spectator entry", flow.getClientProgress().contains("Spectator"));
            assertTrue("Connect must explain next Fresh Return", flow.getClientProgress().contains("first unvisited floor"));
            assertEquals(3, late.getLocalCampaignSlot());
            assertNull(late.getLocalMovementEntityId());
            assertFalse(flow.setPlayerReady(late, true));
            assertNull("Baseline does not activate new slot", host.getPartyStatus().getMember(3));
            Player local = new Player();
            local.gold = 123;
            await(() -> flow.canEnter(late));
            assertTrue(flow.enter(late, () -> assertTrue(new DirectConnectMovementController(late)
                    .applyInitialAuthoritativeState(local))));
            assertTrue(local.multiplayerIncapacitated);
            assertEquals(0, local.gold);
            long baseline = late.getPendingAdmissionCheckpoint();
            assertTrue(baseline > 0);
            late.acknowledgeNativeWorldReadiness(baseline);
            await(() -> late.getPendingAdmissionCheckpoint() > baseline);
            long catchUp = late.getPendingAdmissionCheckpoint();
            late.acknowledgeNativeWorldReadiness(baseline);
            assertEquals(DirectConnectPhase.SYNCHRONIZING, late.getStatus().getPhase());
            assertNull(host.getPartyStatus().getMember(3));
            late.acknowledgeNativeWorldReadiness(catchUp);
            await(() -> late.getStatus().getPhase() == DirectConnectPhase.READY);
            assertEquals(PartyMemberState.SPECTATING, late.getPartyStatus().getMember(3).getState());
            assertTrue(flow.getClientProgress().contains("first unvisited floor"));
            assertFalse(host.isSessionPaused());
            assertFalse(flow.enter(late, () -> fail("Current native world installed twice")));
        }
        finally { flow.leave(); friend.close(); host.close(); }
    }

    @Test public void activeConnectWaitsForStableWorldWithoutEverOfferingPregameConsent() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("loading"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectClient friend = connect(host, '2', "Friend", "humanoid-2", false);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            readyClient(friend);
            startReadySession(host);
            await(() -> friend.getStatus().getPhase() == DirectConnectPhase.READY);
            host.awaitNativeWorld();
            open(flow, host, '3', "Late", "humanoid-3", true);
            DirectConnectPeer late = flow.getPeer();
            await(() -> late.getLobbySnapshot() != null && late.getLobbySnapshot().getSlot(3).isSynchronized());
            assertEquals("Authenticated active join must not become pregame lobby", DirectConnectPhase.SYNCHRONIZING,
                    late.getStatus().getPhase());
            assertFalse(late.canSetPlayerReady());
            assertFalse(flow.setPlayerReady(late, true));
            assertFalse("No authoritative floor baseline yet", flow.enter(late, () -> fail("Loading world entered early")));
            assertEquals(0L, late.getPendingAdmissionCheckpoint());
            assertNull(host.getPartyStatus().getMember(3));
            assertFalse(host.isSessionPaused());
            host.completeNativeWorld(host.getNativeWorldGeneration());
            await(() -> late.getPendingAdmissionCheckpoint() > 0L);
            await(() -> flow.canEnter(late));
            assertTrue(flow.enter(late, () -> { }));
        }
        finally { flow.leave(); friend.close(); host.close(); }
    }

    @Test public void nativeSpectatorPromptKeepsFreshReturnExplanationAfterConnectScreenCloses() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("spectator-prompt"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectClient friend = connect(host, '2', "Friend", "humanoid-2", false);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        Input previousInput = Gdx.input;
        try {
            readyClient(friend);
            startReadySession(host);
            await(() -> friend.getStatus().getPhase() == DirectConnectPhase.READY);
            open(flow, host, '3', "Late", "humanoid-3", false);
            DirectConnectPeer late = flow.getPeer();
            await(() -> late.getStatus().getPhase() == DirectConnectPhase.READY);
            Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(Game.class);
            game.player = new Player();
            DirectConnectMovementController movement = new DirectConnectMovementController(late);
            await(() -> flow.canEnter(late));
            assertTrue(flow.enter(late, () -> assertTrue(movement.applyInitialAuthoritativeState(game.player))));
            // Input device is external boundary. No buttons pressed while native camera attaches.
            Gdx.input = (Input)java.lang.reflect.Proxy.newProxyInstance(
                    Input.class.getClassLoader(), new Class<?>[] { Input.class },
                    (proxy, method, arguments) -> method.getReturnType() == boolean.class ? false : null);
            DirectConnectLivesController lives =
                    new DirectConnectLivesController(late, movement);
            lives.update(game, new GameInput(), 0f);
            assertNotNull("Spectator explanation persists while camera has no Avatar yet", lives.getPrompt());
            assertTrue(lives.getPrompt().toLowerCase(java.util.Locale.ROOT).contains("first unvisited floor"));
            assertTrue(game.player.multiplayerIncapacitated);
            assertNull(late.getLocalMovementEntityId());
            lives.dispose(game);
        }
        finally { Gdx.input = previousInput; flow.leave(); friend.close(); host.close(); }
    }

    @Test public void bodylessLateParticipantCanRefreshNativeHudAfterWorldEntry() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("spectator-hud"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectClient friend = connect(host, '2', "Friend", "humanoid-2", false);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        Game previousGame = Game.instance;
        Stage previousUi = Game.ui;
        Hud previousHud = Game.hud;
        HUDManager previousHudManager = Game.hudManager;
        Graphics previousGraphics = Gdx.graphics;
        GL20 previousGl = Gdx.gl;
        GL20 previousGl20 = Gdx.gl20;
        Options previousOptions = Options.instance;
        java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                StringManager.localizedStrings;
        Stage stage = null;
        try {
            readyClient(friend); startReadySession(host);
            await(() -> friend.getStatus().getPhase() == DirectConnectPhase.READY);
            open(flow, host, '3', "Late", "humanoid-3", true);
            DirectConnectPeer late = flow.getPeer();
            await(() -> flow.canEnter(late));
            Game game = new org.objenesis.ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.player.inventory.add(new com.interrupt.dungeoneer.entities.Item());
            game.player.gold = 123;
            assertTrue(flow.enter(late, () -> assertTrue(new DirectConnectMovementController(late)
                    .applyInitialAuthoritativeState(game.player))));
            long baseline = late.getPendingAdmissionCheckpoint();
            late.acknowledgeNativeWorldReadiness(baseline);
            await(() -> late.getPendingAdmissionCheckpoint() > baseline);
            late.acknowledgeNativeWorldReadiness(late.getPendingAdmissionCheckpoint());
            await(() -> late.getStatus().getPhase() == DirectConnectPhase.READY);

            GdxNativesLoader.load();
            Gdx.graphics = device(Graphics.class);
            Gdx.gl = Gdx.gl20 = device(GL20.class);
            stage = new Stage(new StretchViewport(640f, 480f), device(Batch.class));
            Game.instance = game;
            Game.ui = stage;
            Game.hud = new Hud();
            Game.hudManager = new HUDManager();
            Game.hudManager.backpack.visible = false;
            Game.RefreshUI(); // Exact native path from owner crash, after active bodyless entry.
            game.level = new Level(4, 4);
            StringManager.localizedStrings = new java.util.HashMap<>();
            DirectConnectItemController items = new DirectConnectItemController(late);
            items.prepare(game);
            items.update(game);
            Game.hudManager.backpack.visible = true;
            Game.RefreshUI();
            assertTrue(Game.hudManager.quickSlots.itemButtons.isEmpty());
            assertTrue(Game.hudManager.backpack.itemButtons.isEmpty());
            assertEquals(0, game.player.gold);
            assertTrue(game.player.equippedItems.isEmpty());
            assertEquals("Empty native inventory retains every addressable slot",
                    game.player.inventorySize, game.player.inventory.size);
            for(com.interrupt.dungeoneer.entities.Item item : game.player.inventory) assertNull(item);
            assertTrue(game.player.multiplayerIncapacitated);
            assertNull(late.getLocalMovementEntityId());
        }
        finally {
            if(stage != null) stage.dispose();
            Game.instance = previousGame; Game.ui = previousUi;
            Game.hud = previousHud; Game.hudManager = previousHudManager;
            Gdx.graphics = previousGraphics; Gdx.gl = previousGl; Gdx.gl20 = previousGl20;
            Options.instance = previousOptions;
            StringManager.localizedStrings = previousStrings;
            flow.leave(); friend.close(); host.close();
        }
    }

    private static <T> T device(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, arguments) -> {
                    Class<?> result = method.getReturnType();
                    if(result == boolean.class) return false;
                    if(result == int.class) return method.getName().equals("getHeight") ? 480 : 640;
                    if(result == float.class) return 0f;
                    if(result == long.class) return 0L;
                    return null;
                }));
    }

    @Test public void downedReturnReclaimsBodyAndDisablesNativeControlBeforeFirstTick() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("downed-return"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            open(flow, host, '2', "Friend", "humanoid-2", false);
            DirectConnectClient first = (DirectConnectClient)flow.getPeer();
            readyClient(first);
            startReadySession(host);
            await(() -> first.getStatus().getPhase() == DirectConnectPhase.READY);
            NetworkEntityId body = first.getLocalMovementEntityId();
            String credential = tokens('2').load(host.getRoster().getCampaignId());
            host.applyNativeParticipantDamage("test-trap",
                    new ParticipantId("campaign-slot-2"), 8, 0f, 0f, 0f);
            await(() -> first.getPartyStatus().getMember(2).getState() == PartyMemberState.DOWNED);
            flow.leave();
            await(() -> !host.getLobbySnapshot().getSlot(2).isConnected());
            // Conflicting provisional choice must be ignored for authenticated slot reclaim.
            open(flow, host, '2', "Host", "humanoid-1", false);
            DirectConnectPeer returned = flow.getPeer();
            await(() -> returned.getStatus().getPhase() == DirectConnectPhase.READY);
            assertEquals(2, returned.getLocalCampaignSlot());
            assertEquals(body, returned.getLocalMovementEntityId());
            assertEquals("Friend", returned.getPartyStatus().getMember(2).getNickname());
            assertEquals("humanoid-2", returned.getPartyStatus().getMember(2).getAvatarId());
            assertEquals(PartyMemberState.DOWNED, returned.getPartyStatus().getMember(2).getState());
            assertEquals(3, returned.getPartyStatus().getMember(2).getRemainingLives());
            assertEquals(credential, tokens('2').load(host.getRoster().getCampaignId()));
            assertFalse(flow.setPlayerReady(returned, true));
            Player nativePlayer = new Player();
            await(() -> flow.canEnter(returned));
            assertTrue(flow.enter(returned, () -> assertTrue(new DirectConnectMovementController(returned)
                    .applyInitialAuthoritativeState(nativePlayer))));
            assertTrue("Retained Downed body cannot act before first native tick", nativePlayer.multiplayerIncapacitated);
            assertFalse(host.isSessionPaused());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void standingReturnKeepsWoundedBodyWithoutReadyOrPresentationReset() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("standing-return"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            open(flow, host, '2', "Friend", "humanoid-2", false);
            DirectConnectClient first = (DirectConnectClient)flow.getPeer();
            assertFalse(flow.enter(first, () -> fail("Pregame cannot install a floor")));
            readyClient(first); startReadySession(host);
            await(() -> first.getStatus().getPhase() == DirectConnectPhase.READY);
            NetworkEntityId body = first.getLocalMovementEntityId();
            host.applyNativeParticipantDamage("test-trap",
                    new ParticipantId("campaign-slot-2"), 1, 0f, 0f, 0f);
            await(() -> first.getPartyStatus().getMember(2).getHealth() == 7);
            flow.leave();
            await(() -> !host.getLobbySnapshot().getSlot(2).isConnected());
            open(flow, host, '2', "Host", "humanoid-1", false);
            DirectConnectPeer returned = flow.getPeer();
            await(() -> returned.getStatus().getPhase() == DirectConnectPhase.READY);
            assertEquals(body, returned.getLocalMovementEntityId());
            assertEquals(PartyMemberState.CONNECTED, returned.getPartyStatus().getMember(2).getState());
            assertEquals(7, returned.getPartyStatus().getMember(2).getHealth());
            assertEquals("Friend", returned.getPartyStatus().getMember(2).getNickname());
            assertEquals("humanoid-2", returned.getPartyStatus().getMember(2).getAvatarId());
            assertFalse(returned.canSetPlayerReady());
            Player nativePlayer = new Player();
            nativePlayer.setMultiplayerIncapacitated(true);
            await(() -> flow.canEnter(returned));
            assertTrue(flow.enter(returned, () -> assertTrue(new DirectConnectMovementController(returned)
                    .applyInitialAuthoritativeState(nativePlayer))));
            assertFalse(nativePlayer.multiplayerIncapacitated);
            assertFalse(host.isSessionPaused());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void downedBleedoutContinuesWhileAbsentAndReturnKeepsExhaustedState() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("absent-bleedout"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store, "active", 1), store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            open(flow, host, '2', "Friend", "humanoid-2", false);
            DirectConnectClient first = (DirectConnectClient)flow.getPeer();
            readyClient(first); startReadySession(host);
            await(() -> first.getStatus().getPhase() == DirectConnectPhase.READY);
            NetworkEntityId body = first.getLocalMovementEntityId();
            host.applyNativeParticipantDamage("test-trap",
                    new ParticipantId("campaign-slot-2"), 8, 0f, 0f, 0f);
            await(() -> first.getPartyStatus().getMember(2).getState() == PartyMemberState.DOWNED);
            flow.leave();
            await(() -> !host.getLobbySnapshot().getSlot(2).isConnected());
            await(() -> host.getPartyStatus().getMember(2).getState() == PartyMemberState.SPECTATING, 15);
            open(flow, host, '2', "Host", "humanoid-1", false);
            DirectConnectPeer returned = flow.getPeer();
            await(() -> returned.getStatus().getPhase() == DirectConnectPhase.READY);
            assertEquals(2, returned.getLocalCampaignSlot());
            assertEquals(body, returned.getLocalMovementEntityId());
            assertEquals(0, returned.getPartyStatus().getMember(2).getRemainingLives());
            assertEquals(PartyMemberState.SPECTATING, returned.getPartyStatus().getMember(2).getState());
            assertEquals("Friend", returned.getPartyStatus().getMember(2).getNickname());
            Player nativePlayer = new Player();
            await(() -> flow.canEnter(returned));
            assertTrue(flow.enter(returned, () -> assertTrue(new DirectConnectMovementController(returned)
                    .applyInitialAuthoritativeState(nativePlayer))));
            assertTrue(nativePlayer.multiplayerIncapacitated);
            assertFalse(flow.setPlayerReady(returned, true));
            assertFalse(host.isSessionPaused());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void coldSubsetResumeThenActiveReturnKeepsEverySavedRoleAndBodylessSlot() throws Exception {
        for(PartyMemberState role : new PartyMemberState[] {
                PartyMemberState.CONNECTED, PartyMemberState.DOWNED, PartyMemberState.SPECTATING }) {
            File root = temporary.newFolder("cold-" + role);
            CampaignRosterStore firstStore = new CampaignRosterStore(root, new SecureRandom());
            String campaignId = "cold-" + role;
            DirectConnectHost firstHost = DirectConnectHost.start(0, compatibility(),
                    roster(firstStore, campaignId, role == PartyMemberState.SPECTATING ? 1 : 3), firstStore);
            DirectConnectSessionFlow friendFlow = new DirectConnectSessionFlow();
            DirectConnectSessionFlow lateFlow = new DirectConnectSessionFlow();
            CampaignSave saved;
            try {
                open(friendFlow, firstHost, '2', "Friend", "humanoid-2", false);
                DirectConnectClient friend = (DirectConnectClient)friendFlow.getPeer();
                readyClient(friend); startReadySession(firstHost);
                await(() -> friend.getStatus().getPhase() == DirectConnectPhase.READY);
                open(lateFlow, firstHost, '3', "Late", "humanoid-3", false);
                await(() -> lateFlow.getPeer().getStatus().getPhase() == DirectConnectPhase.READY);
                firstHost.applyNativeParticipantDamage("test-trap",
                        new ParticipantId("campaign-slot-2"),
                        role == PartyMemberState.CONNECTED ? 1 : 8, 0f, 0f, 0f);
                await(() -> firstHost.getPartyStatus().getMember(2).getState() == role
                        && firstHost.getPartyStatus().getMember(2).getHealth() == (role == PartyMemberState.CONNECTED ? 7 : 0), 15);
                firstHost.setSessionPaused(true);
                firstHost.saveAndQuit();
                saved = new CampaignRosterStore(root, new SecureRandom()).campaignSaves().load(campaignId, compatibility());
                assertEquals(role, saved.getParticipant(2).getParty().getState());
                assertEquals(PartyMemberState.SPECTATING, saved.getParticipant(3).getParty().getState());
                assertNull(saved.getParticipant(3).getParty().getEntityId());
            }
            finally { friendFlow.leave(); lateFlow.leave(); firstHost.close(); }
            CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
            CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(),
                    identity('1'), new SlotPresentation("Other default", "humanoid-4"));
            DirectConnectSessionFlow hostFlow = new DirectConnectSessionFlow();
            try {
                DirectConnectSessionFlow.HostSetup setup = hostFlow.prepareCampaign(library, campaignId,
                        SocketTestPorts.availableTcpAndUdp());
                hostFlow.openSavedCampaign(setup, library, store,
                        (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
                DirectConnectHost host = (DirectConnectHost)hostFlow.getPeer();
                startReadySession(host); // Host-only subset; absent credentials/presentation remain reserved.
                CampaignSave subset = host.persistCampaign();
                assertEquals(role, subset.getParticipant(2).getParty().getState());
                assertEquals(saved.getParticipant(2).getParty().getHealth(), subset.getParticipant(2).getParty().getHealth());
                assertEquals(saved.getParticipant(2).getParty().getRemainingLives(), subset.getParticipant(2).getParty().getRemainingLives());
                open(friendFlow, host, '2', "Host", "humanoid-1", false);
                DirectConnectPeer returned = friendFlow.getPeer();
                await(() -> returned.getStatus().getPhase() == DirectConnectPhase.READY);
                assertEquals(2, returned.getLocalCampaignSlot());
                assertNotNull(returned.getLocalMovementEntityId());
                assertEquals(role, returned.getPartyStatus().getMember(2).getState());
                assertEquals(saved.getParticipant(2).getParty().getHealth(), returned.getPartyStatus().getMember(2).getHealth());
                assertEquals(saved.getParticipant(2).getParty().getRemainingLives(), returned.getPartyStatus().getMember(2).getRemainingLives());
                assertEquals("Friend", returned.getPartyStatus().getMember(2).getNickname());
                assertEquals("humanoid-2", returned.getPartyStatus().getMember(2).getAvatarId());
                assertEquals(saved.getSlots().get(1).getReconnectToken(), tokens('2').load(campaignId));
                assertFalse(returned.canSetPlayerReady());
                Player local = new Player();
                await(() -> friendFlow.canEnter(returned));
                assertTrue(friendFlow.enter(returned, () -> assertTrue(new DirectConnectMovementController(returned)
                        .applyInitialAuthoritativeState(local))));
                assertEquals(role != PartyMemberState.CONNECTED, local.multiplayerIncapacitated);
                String bodylessCredential = tokens('3').load(campaignId);
                tokens('3').save(campaignId, identity('f').getValue());
                try {
                    open(lateFlow, host, '3', "Host", "humanoid-1", false);
                    await(() -> lateFlow.getPeer().getStatus().getPhase() == DirectConnectPhase.REJECTED);
                    assertEquals(bodylessCredential, host.getRoster().getSlot(3).getReconnectToken());
                }
                finally { lateFlow.leave(); tokens('3').save(campaignId, bodylessCredential); }
                open(lateFlow, host, '3', "Host", "humanoid-1", false);
                DirectConnectPeer lateReturn = lateFlow.getPeer();
                try { await(() -> lateReturn.getStatus().getPhase() == DirectConnectPhase.READY); }
                catch(AssertionError timeout) {
                    fail("Bodyless cold return after " + role + ": " + lateReturn.getStatus().getPhase()
                            + " / " + lateReturn.getStatus().getMessage() + "; paused=" + host.isSessionPaused()
                            + "; checkpoint=" + lateReturn.getPendingAdmissionCheckpoint());
                }
                assertEquals(3, lateReturn.getLocalCampaignSlot());
                assertNull(lateReturn.getLocalMovementEntityId());
                assertEquals("Late", lateReturn.getPartyStatus().getMember(3).getNickname());
                assertEquals("humanoid-3", lateReturn.getPartyStatus().getMember(3).getAvatarId());
                assertEquals(0, lateReturn.getPartyStatus().getMember(3).getRemainingLives());
                assertTrue(lateFlow.getClientProgress().contains("first unvisited floor"));
                Player bodyless = new Player();
                await(() -> lateFlow.canEnter(lateReturn));
                assertTrue(lateFlow.enter(lateReturn, () -> assertTrue(new DirectConnectMovementController(lateReturn)
                        .applyInitialAuthoritativeState(bodyless))));
                assertTrue(bodyless.multiplayerIncapacitated);
                assertEquals(3, host.getRoster().getSlots().size());
                assertFalse(host.isSessionPaused());
            }
            finally { friendFlow.leave(); lateFlow.leave(); hostFlow.leave(); }
        }
    }

    private ProfileReconnectTokenStore tokens(char identity) {
        File current = MultiplayerProfile.getRoot();
        MultiplayerProfile.initialize(new File(profileRoot, "identity-" + identity));
        try { return new ProfileReconnectTokenStore(); }
        finally { MultiplayerProfile.initialize(current); }
    }

    private void open(DirectConnectSessionFlow flow, DirectConnectHost host, char identity,
            String name, String avatar, boolean nativeWorld) {
        ProfileReconnectTokenStore credentials = tokens(identity);
        flow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", host.getBoundPort(), name, avatar),
                setup -> nativeWorld ? DirectConnectClient.connectForNativeWorld(setup.getAddress(), setup.getPort(),
                        identity(identity), setup.getPresentation(), 0, credentials, compatibility())
                        : DirectConnectClient.connect(setup.getAddress(), setup.getPort(), identity(identity),
                        setup.getPresentation(), 0, credentials, compatibility()));
    }

    private DirectConnectClient connect(DirectConnectHost host, char identity, String name,
            String avatar, boolean nativeWorld) {
        ProfileReconnectTokenStore credentials = tokens(identity);
        return nativeWorld ? DirectConnectClient.connectForNativeWorld("127.0.0.1", host.getBoundPort(), identity(identity),
                new SlotPresentation(name, avatar), 0, credentials, compatibility())
                : DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity(identity),
                new SlotPresentation(name, avatar), 0, credentials, compatibility());
    }

    private static CampaignRoster roster(CampaignRosterStore store) {
        return roster(store, "active", 3);
    }

    private static CampaignRoster roster(CampaignRosterStore store, String campaignId, int lives) {
        CampaignRoster roster = CampaignRoster.createNamed(campaignId, "Friday Delver", 4, lives,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        return roster;
    }

    private static LauncherIdentity identity(char value) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', value));
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("active-entry-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, 8);
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime() < deadline) {
            if(condition.getAsBoolean()) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for authoritative active-session outcome");
    }
}
