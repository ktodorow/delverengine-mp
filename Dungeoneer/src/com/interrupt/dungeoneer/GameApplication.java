package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.launcher.DirectConnectSessionFlow;
import com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorBuild;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.interrupt.api.steam.SteamApi;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;
import com.interrupt.dungeoneer.game.GameData;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.owned.OwnedGameCopyMount;
import com.interrupt.dungeoneer.owned.OwnedGameCopyCompatibility;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignLibrary;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSave;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectClient;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;
import com.interrupt.dungeoneer.multiplayer.network.OpenSourceTestCompatibility;
import com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.interrupt.dungeoneer.multiplayer.movement.LevelMovementCollisionWorld;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.dungeoneer.screens.*;
import com.interrupt.utils.JsonUtil;

import java.io.IOException;

public class GameApplication extends Game {

    public static final String OPEN_SOURCE_TEST_LEVEL = "levels/test-level.bin";
    public static final String OWNED_TUTORIAL_FLOOR = "owned-game-copy-tutorial";
    private static final java.util.regex.Pattern OWNED_LEVEL_FLOOR =
            java.util.regex.Pattern.compile("levels/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.bin");

    /** Development Direct Connect floors name one owned level file; never a filesystem path. */
    public static boolean isOwnedLevelFloor(String floorId) {
        return floorId != null && floorId.length()
                <= com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.MAX_FLOOR_ID_BYTES
                && OWNED_LEVEL_FLOOR.matcher(floorId).matches();
    }

    private enum StartupMode {
        NORMAL,
        MULTIPLAYER_MENU,
        OPEN_SOURCE_TEST_LEVEL,
        OWNED_TUTORIAL,
        DIRECT_CONNECT_HOST,
        DIRECT_CONNECT_CLIENT
    }

	protected GameManager gameManager = null;
	public GameInput input = new GameInput();
    private final StartupMode startupMode;
    private String directConnectAddress;
    private int directConnectPort;
    private CampaignRoster directConnectRoster;
    private CampaignRosterStore directConnectRosterStore;
    private LauncherIdentity directConnectLauncherIdentity;
    private SlotPresentation directConnectPresentation;
    private int directConnectRequestedSlot;
    private ReconnectTokenStore directConnectReconnectTokens;
    private int directConnectCampaignCapacity;
    private String directConnectFloor;
    private final DirectConnectSessionFlow sessionFlow =
            new DirectConnectSessionFlow(this::releaseDirectConnectResources);
    private DirectConnectSessionScreen directConnectScreen;
    private CampaignLibraryScreen campaignLibraryScreen;
    private CampaignLibrary campaignLibrary;
    private String campaignLibrarySelection;
    private com.interrupt.dungeoneer.screens.HostSetupScreen hostSetupScreen;
    private ConnectSetupScreen connectSetupScreen;
    private DirectConnectMovementController directConnectMovementController;
    private DirectConnectCombatController directConnectCombatController;
    private com.interrupt.dungeoneer.multiplayer.lives.DirectConnectLivesController directConnectLivesController;

    public GameScreen mainScreen;
    public GameOverScreen gameoverScreen;
    public LevelChangeScreen levelChangeScreen;
    public SplashScreen mainMenuScreen;

    public WinScreen winScreen;

    public static GameApplication instance;
    public static boolean editorRunning = false;

    public GameApplication() {
        this(StartupMode.NORMAL, null, 0, null, null, null, null, 0, null, 0);
    }

    public boolean isMultiplayerLauncher() { return startupMode == StartupMode.MULTIPLAYER_MENU; }

    public static GameApplication forMultiplayerMenu() {
        if(!OwnedGameCopyMount.isMounted()) {
            throw new IllegalStateException("Owned Game Copy must be mounted before native multiplayer startup.");
        }
        GameApplication application = new GameApplication(StartupMode.MULTIPLAYER_MENU);
        application.directConnectPort = com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.DEFAULT_PORT;
        application.directConnectCampaignCapacity = 2;
        return application;
    }

    private GameApplication(StartupMode startupMode) {
        this(startupMode, null, 0, null, null, null, null, 0, null, 0);
    }

    private GameApplication(StartupMode startupMode, String directConnectAddress,
            int directConnectPort, CampaignRoster directConnectRoster,
            CampaignRosterStore directConnectRosterStore,
            LauncherIdentity directConnectLauncherIdentity,
            SlotPresentation directConnectPresentation, int directConnectRequestedSlot,
            ReconnectTokenStore directConnectReconnectTokens,
            int directConnectCampaignCapacity) {
        this.startupMode = startupMode;
        this.directConnectAddress = directConnectAddress;
        this.directConnectPort = directConnectPort;
        this.directConnectRoster = directConnectRoster;
        this.directConnectRosterStore = directConnectRosterStore;
        this.directConnectLauncherIdentity = directConnectLauncherIdentity;
        this.directConnectPresentation = directConnectPresentation;
        this.directConnectRequestedSlot = directConnectRequestedSlot;
        this.directConnectReconnectTokens = directConnectReconnectTokens;
        this.directConnectCampaignCapacity = directConnectCampaignCapacity;
    }

    public static GameApplication forOpenSourceTestLevel() {
        return new GameApplication(StartupMode.OPEN_SOURCE_TEST_LEVEL);
    }

    public static GameApplication forOwnedTutorial() {
        return new GameApplication(StartupMode.OWNED_TUTORIAL);
    }

    public static GameApplication forDirectConnectHost(int port, CampaignRoster roster,
            CampaignRosterStore rosterStore) {
        return forDirectConnectHost(port, roster, rosterStore, null);
    }

    /** A non-null owned level replaces the tutorial as the shared development floor. */
    public static GameApplication forDirectConnectHost(int port, CampaignRoster roster,
            CampaignRosterStore rosterStore, String ownedFloor) {
        if(ownedFloor != null && !isOwnedLevelFloor(ownedFloor)) {
            throw new IllegalArgumentException("Invalid owned Direct Connect floor: " + ownedFloor);
        }
        GameApplication application = new GameApplication(StartupMode.DIRECT_CONNECT_HOST, null, port,
                roster, rosterStore, null, null, 0, null, roster.getCapacity());
        application.directConnectFloor = ownedFloor;
        return application;
    }

    public static GameApplication forDirectConnectHostLibrary(int port,
            CampaignRosterStore rosterStore, LauncherIdentity hostIdentity,
            SlotPresentation hostPresentation, int campaignCapacity, String ownedFloor) {
        if(rosterStore == null) throw new IllegalArgumentException("Campaign store cannot be null.");
        if(hostIdentity == null) throw new IllegalArgumentException("Host identity cannot be null.");
        if(hostPresentation == null) throw new IllegalArgumentException("Host presentation cannot be null.");
        if(campaignCapacity < 2 || campaignCapacity > 4) {
            throw new IllegalArgumentException("Campaign Capacity must be 2, 3, or 4.");
        }
        if(ownedFloor != null && !isOwnedLevelFloor(ownedFloor)) {
            throw new IllegalArgumentException("Invalid owned Direct Connect floor: " + ownedFloor);
        }
        GameApplication application = new GameApplication(StartupMode.DIRECT_CONNECT_HOST,
                null, port, null, rosterStore, hostIdentity, hostPresentation,
                0, null, campaignCapacity);
        application.directConnectFloor = ownedFloor;
        return application;
    }

    public static GameApplication forDirectConnectClient(String address, int port,
            LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, ReconnectTokenStore reconnectTokens) {
        return new GameApplication(StartupMode.DIRECT_CONNECT_CLIENT, address, port,
                null, null, launcherIdentity, presentation, requestedSlot, reconnectTokens, 0);
    }

	@Override
	public void create() {
        if(isMultiplayerLauncher()) {
            Gdx.app.setLogLevel(Application.LOG_INFO);
            ensureNativeRendering();
            mainMenuScreen = new SplashScreen();
            setScreen(mainMenuScreen);
            return;
        }
        if(startupMode == StartupMode.DIRECT_CONNECT_HOST
                || startupMode == StartupMode.DIRECT_CONNECT_CLIENT) {
            createDirectConnect();
            return;
        }

        if(startupMode == StartupMode.OPEN_SOURCE_TEST_LEVEL) {
            Level startupLevel = KryoSerializer.loadLevel(Gdx.files.internal(OPEN_SOURCE_TEST_LEVEL));
            if(startupLevel == null) {
                throw new IllegalStateException("Could not load startup level: " + OPEN_SOURCE_TEST_LEVEL);
            }

            if(startupLevel.theme == null) startupLevel.theme = "TEST";
            createFromEditor(startupLevel);
            return;
        }

        if(startupMode == StartupMode.OWNED_TUTORIAL) {
            if(!OwnedGameCopyMount.isMounted()) {
                throw new IllegalStateException("Owned Game Copy must be validated and mounted before tutorial launch.");
            }

            com.interrupt.dungeoneer.game.Game.gameData =
                    com.interrupt.dungeoneer.game.Game.getModManager().loadGameData();
            Level tutorialLevel = com.interrupt.dungeoneer.game.Game.gameData == null
                    ? null
                    : com.interrupt.dungeoneer.game.Game.gameData.tutorialLevel;
            if(tutorialLevel == null) {
                throw new IllegalStateException("Validated Owned Game Copy does not define tutorial content.");
            }

            createGameplay(com.interrupt.dungeoneer.game.Game.StartMode.OWNED_TUTORIAL, true);
            return;
        }

        createGameplay(com.interrupt.dungeoneer.game.Game.StartMode.NORMAL, false);
    }

    private void createDirectConnect() {
        instance = this;
        Gdx.app.setLogLevel(Application.LOG_INFO);
        ensureNativeRendering();
        DirectConnectCompatibility compatibility = createDirectConnectCompatibility();
        if(startupMode == StartupMode.DIRECT_CONNECT_HOST) {
            if(directConnectRoster == null) {
                showCampaignLibrary();
                return;
            }
            try { startDirectConnectHost(compatibility); }
            catch(RuntimeException failure) { showDirectConnectFailure(failure.getMessage()); }
        }
        else {
            try { connectDirectConnectSession(directConnectAddress,
                    directConnectPort, directConnectLauncherIdentity,
                    directConnectPresentation, directConnectRequestedSlot,
                    directConnectReconnectTokens); }
            catch(RuntimeException failure) { showDirectConnectFailure(failure.getMessage()); }
        }
    }

    public void hostDirectConnectCampaign(CampaignRoster roster) {
        hostDirectConnectCampaign(directConnectPort, roster, directConnectRosterStore, directConnectFloor);
    }

    /** Reusable native launcher request, independent of the original command-line role. */
    public void hostDirectConnectCampaign(int port, CampaignRoster roster,
            CampaignRosterStore store, String ownedFloor) {
        if(roster == null || store == null) throw new IllegalArgumentException("Host Campaign and store are required.");
        if(ownedFloor != null && !isOwnedLevelFloor(ownedFloor)) throw new IllegalArgumentException("Invalid owned Direct Connect floor: " + ownedFloor);
        openDirectConnectSession(() -> {
            // Retain new request after bind failure, but only after prior Host saved successfully.
            directConnectPort = port;
            directConnectRoster = roster;
            directConnectRosterStore = store;
            directConnectFloor = ownedFloor;
            directConnectLauncherIdentity = roster.getSlot(1).getLauncherIdentity();
            directConnectPresentation = roster.getSlot(1).getPresentation();
            directConnectCampaignCapacity = roster.getCapacity();
            return createDirectConnectHost(port, roster, store, ownedFloor, createDirectConnectCompatibility());
        });
    }

    public void connectDirectConnectSession(String address, int port, LauncherIdentity identity,
            SlotPresentation presentation, int requestedSlot, ReconnectTokenStore tokens) {
        openDirectConnectSession(() -> {
            directConnectRoster = null;
            directConnectRosterStore = null;
            directConnectAddress = address;
            directConnectPort = port;
            directConnectLauncherIdentity = identity;
            directConnectPresentation = presentation;
            directConnectRequestedSlot = requestedSlot;
            directConnectReconnectTokens = tokens;
            return DirectConnectClient.connectForNativeWorld(address, port, identity,
                    presentation, requestedSlot, tokens, createDirectConnectCompatibility());
        });
    }

    /** Single application boundary used by development entry points and later native menus. */
    public void openDirectConnectSession(java.util.function.Supplier<? extends DirectConnectPeer> request) {
        ensureNativeRendering();
        try {
            sessionFlow.open(request);
            showDirectConnectSession(sessionFlow.getPeer());
        }
        catch(RuntimeException failure) {
            if(sessionFlow.getPeer() == null && campaignLibraryScreen == null) {
                showDirectConnectSession(null);
                showDirectConnectFailure(failure.getMessage());
            }
            throw failure;
        }
    }

    public void retryDirectConnectSession() {
        try {
            sessionFlow.retry();
            showDirectConnectSession(sessionFlow.getPeer());
        }
        catch(RuntimeException failure) {
            if(sessionFlow.getPeer() == null) showDirectConnectSession(null);
            showDirectConnectFailure(failure.getMessage());
            throw failure;
        }
    }

    private void startDirectConnectHost(DirectConnectCompatibility compatibility) {
        final int port = directConnectPort;
        final CampaignRoster roster = directConnectRoster;
        final CampaignRosterStore store = directConnectRosterStore;
        final String ownedFloor = directConnectFloor;
        openDirectConnectSession(() -> createDirectConnectHost(port, roster, store, ownedFloor, compatibility));
    }

    private DirectConnectHost createDirectConnectHost(int port, CampaignRoster roster,
            CampaignRosterStore store, String ownedFloor, DirectConnectCompatibility compatibility) {
        String floorId = ownedFloor;
        CampaignSave resumed = null;
        if(store.campaignSaves().exists(roster.getCampaignId())) {
            CampaignSave saved = store.campaignSaves().load(roster.getCampaignId(), compatibility);
            resumed = saved;
            floorId = compatibility.usesOpenSourceTestContent() ? null : saved.getFloorId();
        }
        if(floorId != null && !OwnedGameCopyMount.isMounted()) {
            throw new IllegalStateException(
                    "Owned Direct Connect floor requires a validated Owned Game Copy.");
        }
        // Initialize owned managers/localized defaults before checkpoint deserialization.
        Level authoritativeLevel = loadDirectConnectLevel(resumed == null ? floorId : ownedFloor);
        if(resumed != null && resumed.hasNativeFloor()) authoritativeLevel = NativeFloorSave.restore(resumed.getNativeFloor());
        DirectConnectHost host = DirectConnectHost.start(port, compatibility, roster, store,
                new LevelMovementCollisionWorld(authoritativeLevel));
        try {
            if(floorId != null && resumed == null) host.useOwnedFloor(floorId);
            host.awaitNativeWorld();
            return host;
        }
        catch(RuntimeException failure) {
            try { host.close(); }
            catch(RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Switch an admitted native Connect attempt to the same shared lobby as Host setup. */
    public boolean showDirectConnectLobby(DirectConnectPeer expectedPeer) {
        if(expectedPeer == null || expectedPeer != sessionFlow.getPeer()
                || expectedPeer.getStatus().getPhase() != DirectConnectPhase.LOBBY
                || expectedPeer.getLobbySnapshot() == null) return false;
        showDirectConnectSession(expectedPeer);
        return true;
    }

    private void showDirectConnectSession(DirectConnectPeer peer) {
        Screen completedMenu = getScreen() instanceof MultiplayerMenuScreen ? getScreen() : null;
        CampaignLibraryScreen completedLibrary = campaignLibraryScreen;
        DirectConnectSessionScreen completedSession = directConnectScreen;
        com.interrupt.dungeoneer.screens.HostSetupScreen completedSetup = hostSetupScreen;
        ConnectSetupScreen completedConnect = connectSetupScreen;
        connectSetupScreen = null;
        hostSetupScreen = null;
        campaignLibraryScreen = null;
        directConnectScreen = new DirectConnectSessionScreen(this, peer);
        setScreen(directConnectScreen);
        if(completedLibrary != null) completedLibrary.dispose();
        if(completedSession != null) completedSession.dispose();
        if(completedMenu != null) completedMenu.dispose();
        if(completedSetup != null) completedSetup.dispose();
        if(completedConnect != null) completedConnect.dispose();
    }

    private void showCampaignLibrary() {
        if(directConnectLauncherIdentity == null && directConnectRoster != null) {
            directConnectLauncherIdentity = directConnectRoster.getSlot(1).getLauncherIdentity();
            directConnectPresentation = directConnectRoster.getSlot(1).getPresentation();
        }
        campaignLibrary = new CampaignLibrary(directConnectRosterStore, AvatarCatalog.ownedV108Humanoids(),
                directConnectLauncherIdentity, directConnectPresentation);
        campaignLibraryScreen = new CampaignLibraryScreen(this, campaignLibrary,
                directConnectCampaignCapacity, campaignLibrarySelection);
        setScreen(campaignLibraryScreen);
    }

    /** Native title/menu owns navigation; session policy stays in the reusable flow. */
    public void showMultiplayerMenu() {
        if(!isMultiplayerLauncher()) throw new IllegalStateException("Native multiplayer entry is required.");
        if(getDirectConnectPeer() != null) sessionFlow.leave();
        Screen previous = getScreen();
        setScreen(new MultiplayerMenuScreen(this));
        if(previous != null) previous.dispose();
        if(previous == mainMenuScreen) mainMenuScreen = null;
        directConnectScreen = null;
        campaignLibraryScreen = null;
        connectSetupScreen = null;
    }

    public void showMultiplayerHostLibrary() {
        directConnectLauncherIdentity = com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentityStore.loadOrCreate();
        directConnectPresentation = com.interrupt.dungeoneer.multiplayer.lobby.LauncherPresentationStore.load();
        directConnectRosterStore = new CampaignRosterStore();
        Screen previous = getScreen();
        showCampaignLibrary();
        if(previous != null) previous.dispose();
        hostSetupScreen = null;
    }

    public void showNewCampaignSetup(int capacity) {
        if(getDirectConnectPeer() != null) throw new IllegalStateException("Leave current session before creating a Campaign.");
        Screen previous = getScreen();
        campaignLibrarySelection = campaignLibraryScreen == null ? campaignLibrarySelection
                : campaignLibraryScreen.getSelectedCampaignId();
        hostSetupScreen = new com.interrupt.dungeoneer.screens.HostSetupScreen(this, capacity,
                com.interrupt.dungeoneer.multiplayer.lobby.LauncherPresentationStore.load());
        setScreen(hostSetupScreen);
        campaignLibraryScreen = null;
        if(previous != null) previous.dispose();
    }

    /** Saved selection feeds shared form before listener opens. */
    public void showSavedCampaignSetup(String campaignId) {
        DirectConnectSessionFlow.HostSetup setup = sessionFlow.prepareCampaign(campaignLibrary, campaignId, directConnectPort);
        Screen previous = getScreen();
        campaignLibrarySelection = campaignId;
        hostSetupScreen = new com.interrupt.dungeoneer.screens.HostSetupScreen(this, setup);
        setScreen(hostSetupScreen);
        campaignLibraryScreen = null;
        if(previous != null) previous.dispose();
    }

    public void returnFromCampaignSetup() {
        Screen previous = getScreen();
        hostSetupScreen = null;
        showCampaignLibrary();
        if(previous != null) previous.dispose();
    }

    /** Setup stays installed until listener and profile writes succeed. No implicit Start. */
    public void openNewCampaignLobby(DirectConnectSessionFlow.HostSetup setup) {
        ensureNativeRendering();
        final CampaignRosterStore store = directConnectRosterStore;
        final LauncherIdentity identity = directConnectLauncherIdentity;
        sessionFlow.openNewCampaign(setup, store, identity, (request, roster) ->
                createDirectConnectHost(request.getPort(), roster, store, null, createDirectConnectCompatibility()));
        directConnectPort = setup.getPort();
        directConnectRoster = ((DirectConnectHost)sessionFlow.getPeer()).getRoster();
        directConnectFloor = null;
        directConnectPresentation = setup.getPresentation();
        directConnectCampaignCapacity = setup.getCapacity();
        showDirectConnectSession(sessionFlow.getPeer());
    }

    public void openSavedCampaignLobby(DirectConnectSessionFlow.HostSetup setup) {
        ensureNativeRendering();
        final CampaignRosterStore store = directConnectRosterStore;
        sessionFlow.openSavedCampaign(setup, campaignLibrary, store, (request, roster) ->
                createDirectConnectHost(request.getPort(), roster, store, null, createDirectConnectCompatibility()));
        directConnectPort = setup.getPort();
        directConnectRoster = ((DirectConnectHost)sessionFlow.getPeer()).getRoster();
        directConnectFloor = null;
        directConnectPresentation = setup.getPresentation();
        directConnectCampaignCapacity = setup.getCapacity();
        showDirectConnectSession(sessionFlow.getPeer());
    }

    /** Ordinary Host and Client roles share one profile identity and editable presentation. */
    public void promptMultiplayerConnect() {
        if(getDirectConnectPeer() != null) throw new IllegalStateException("Leave current session before connecting.");
        DirectConnectSessionFlow.ConnectSetup defaults = sessionFlow.prepareConnect();
        Screen previous = getScreen();
        connectSetupScreen = new ConnectSetupScreen(this, defaults);
        setScreen(connectSetupScreen);
        if(previous != null) previous.dispose();
        if(previous == mainMenuScreen) mainMenuScreen = null;
    }

    public void openMultiplayerConnection(DirectConnectSessionFlow.ConnectSetup setup) {
        ensureNativeRendering();
        LauncherIdentity identity = com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentityStore.loadOrCreate();
        ReconnectTokenStore tokens = new com.interrupt.dungeoneer.multiplayer.lobby.ProfileReconnectTokenStore();
        sessionFlow.openClient(setup, request -> {
            directConnectRoster = null;
            directConnectRosterStore = null;
            directConnectAddress = request.getAddress();
            directConnectPort = request.getPort();
            directConnectLauncherIdentity = identity;
            directConnectPresentation = request.getPresentation();
            directConnectRequestedSlot = 0;
            directConnectReconnectTokens = tokens;
            return DirectConnectClient.connectForNativeWorld(request.getAddress(), request.getPort(),
                    identity, request.getPresentation(), 0, tokens, createDirectConnectCompatibility());
        });
    }

    public String getMultiplayerConnectionProgress() { return sessionFlow.getClientProgress(); }

    public boolean setMultiplayerPlayerReady(DirectConnectPeer expected, boolean ready) {
        return sessionFlow.setPlayerReady(expected, ready);
    }

    public long editMultiplayerPresentation(DirectConnectPeer expected, String nickname, String avatar) {
        return sessionFlow.editPresentation(expected, nickname, avatar);
    }

    public com.interrupt.dungeoneer.multiplayer.lobby.PresentationEditResult pollMultiplayerPresentationEditResult(DirectConnectPeer expected) {
        return sessionFlow.pollPresentationEditResult(expected);
    }

    public boolean startMultiplayerCampaign(DirectConnectPeer expected) { return sessionFlow.startCampaign(expected); }

    public void cancelMultiplayerConnect() {
        sessionFlow.cancelClient();
        showMultiplayerMenu();
    }

    private DirectConnectCompatibility createDirectConnectCompatibility() {
        OwnedGameCopyCompatibility ownedCopy = OwnedGameCopyMount.getCompatibility();
        return ownedCopy == null ? createOpenSourceCompatibility()
                : DirectConnectCompatibility.forOwnedGameCopy(ownedCopy);
    }

    public DirectConnectCompatibility getCampaignCompatibility() {
        return createDirectConnectCompatibility();
    }

    /** Stop and release native world before returning; failed Host save leaves gameplay intact. */
    public void returnToDirectConnectSession() {
        DirectConnectPeer stopped = sessionFlow.getPeer();
        if(stopped == null) {
            if(isMultiplayerLauncher()) {
                releaseDirectConnectResources();
                showMultiplayerMenu();
                return;
            }
            if(directConnectRosterStore != null) {
                releaseDirectConnectResources();
                showCampaignLibrary();
            }
            return;
        }
        DirectConnectPhase phase = stopped.getStatus().getPhase();
        String reason = stopped.getStatus().getMessage();
        sessionFlow.leave();
        if(stopped instanceof DirectConnectHost) showCampaignLibrary();
        else {
            if(isMultiplayerLauncher()) {
                promptMultiplayerConnect();
                if(phase == DirectConnectPhase.FAILED || phase == DirectConnectPhase.REJECTED
                        || phase == DirectConnectPhase.DISCONNECTED) showDirectConnectFailure(reason);
                return;
            }
            showDirectConnectSession(stopped);
            if(phase == DirectConnectPhase.FAILED || phase == DirectConnectPhase.REJECTED
                    || phase == DirectConnectPhase.DISCONNECTED) showDirectConnectFailure(reason);
        }
    }

    public void leaveDirectConnectSession() {
        returnToDirectConnectSession();
    }

    public void showDirectConnectFailure(String message) {
        if(connectSetupScreen != null) connectSetupScreen.showFailure(message);
        else if(directConnectScreen != null) directConnectScreen.showFailure(message);
        else if(campaignLibraryScreen != null) campaignLibraryScreen.showFailure(message);
        else if(getScreen() instanceof MultiplayerMenuScreen) ((MultiplayerMenuScreen)getScreen()).showFailure(message);
    }

    /** Transition floors keep theme in native generator section definitions, not in their .bin. */
    private static String ownedFloorTheme(String floorId) {
        for(Level definition : com.interrupt.dungeoneer.game.Game.buildLevelLayout()) {
            if(floorId.equals(definition.levelFileName) && definition.theme != null) return definition.theme;
        }
        throw new IllegalStateException("Owned Game Copy defines no theme for Direct Connect floor: " + floorId);
    }

    /** Native player template gold; a missing template falls back like Game(Level) to a new Player. */
    private static int nativeStartingGold() {
        try {
            com.interrupt.dungeoneer.entities.Player template = com.interrupt.utils.JsonUtil.fromJson(
                    com.interrupt.dungeoneer.entities.Player.class,
                    com.interrupt.dungeoneer.game.Game.findInternalFileInMods(
                            "data/" + com.interrupt.dungeoneer.game.Game.gameData.playerDataFile));
            return template == null ? 0 : Math.max(0, template.gold);
        }
        catch(Exception missing) {
            return 0;
        }
    }

    private Level loadDirectConnectLevel(String floorId) {
        if(!OwnedGameCopyMount.isMounted()) {
            Level level = KryoSerializer.loadLevel(Gdx.files.internal(OPEN_SOURCE_TEST_LEVEL));
            if(level == null) {
                throw new IllegalStateException("Could not load Direct Connect Level: "
                        + OPEN_SOURCE_TEST_LEVEL);
            }
            if(level.theme == null) level.theme = "TEST";
            return level;
        }

        com.interrupt.dungeoneer.game.Game.gameData =
                com.interrupt.dungeoneer.game.Game.getModManager().loadGameData();
        // Triggers, TriggeredShop and some items read localized defaults while deserializing;
        // without them the level reader silently drops the rest of an entity list (the owned
        // tutorial kept 99 of 530 entities, no triggers) and a floor checkpoint cannot load.
        com.interrupt.managers.StringManager.init();
        if(floorId != null && !OWNED_TUTORIAL_FLOOR.equals(floorId)) {
            if(!isOwnedLevelFloor(floorId)) {
                throw new IllegalStateException("Host announced an unsupported owned floor: " + floorId);
            }
            com.badlogic.gdx.files.FileHandle file = com.interrupt.dungeoneer.game.Game.getInternal(floorId);
            Level owned = file == null || !file.exists() ? null : KryoSerializer.loadLevel(file);
            if(owned == null) {
                throw new IllegalStateException("Could not load owned Direct Connect floor: " + floorId);
            }
            if(owned.theme == null) owned.theme = ownedFloorTheme(floorId);
            owned.levelFileName = floorId;
            owned.generated = false;
            owned.multiplayerNativeRecipe = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorRecipe.fromDefinition(owned, 1).encode();
            return owned;
        }
        Level tutorial = com.interrupt.dungeoneer.game.Game.gameData == null
                ? null : com.interrupt.dungeoneer.game.Game.gameData.tutorialLevel;
        if(tutorial == null || tutorial.levelFileName == null) {
            throw new IllegalStateException(
                    "Validated Owned Game Copy does not define tutorial content.");
        }
        Level level = KryoSerializer.loadLevel(
                com.interrupt.dungeoneer.game.Game.getInternal(tutorial.levelFileName));
        if(level == null) {
            throw new IllegalStateException("Could not load owned Direct Connect Level: "
                    + tutorial.levelFileName);
        }
        level.levelFileName = tutorial.levelFileName;
        level.generated = false;
        if(level.theme == null) level.theme = tutorial.theme;
        level.multiplayerNativeRecipe = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorRecipe.fromDefinition(level, 1).encode();
        return level;
    }

    private DirectConnectCompatibility createOpenSourceCompatibility() {
        final String index = Gdx.files.internal("packaged_files.txt").readString("UTF-8");
        try {
            return OpenSourceTestCompatibility.fromPackagedIndex(index,
                    new OpenSourceTestCompatibility.AssetSource() {
                        @Override
                        public boolean exists(String path) {
                            return Gdx.files.internal(path).exists();
                        }

                        @Override
                        public byte[] read(String path) {
                            return Gdx.files.internal(path).readBytes();
                        }
                    });
        }
        catch(IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    public void enterDirectConnectFloor() {
        enterDirectConnectFloor(getDirectConnectPeer());
    }

    /** Old posted screen work cannot enter a replacement session. */
    public boolean enterDirectConnectFloor(DirectConnectPeer expected) {
        return sessionFlow.enter(expected, this::installDirectConnectFloor);
    }

    public boolean canEnterDirectConnectFloor(DirectConnectPeer expected) {
        return sessionFlow.canEnter(expected);
    }

    private void installDirectConnectFloor() {

        // Clients load the floor announced by Host from their own certified Owned Game Copy.
        com.interrupt.dungeoneer.multiplayer.floor.PartyDestination destination = getDirectConnectPeer().getPartyDestination();
        Level startupLevel;
        if(destination != null && !destination.recipe.isEmpty()) {
            loadDirectConnectLevel(directConnectFloor); // Owned managers and localization.
            startupLevel = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorRecipe.decode(destination.recipe).definition();
            startupLevel.multiplayerNativeRecipe = destination.recipe;
            startupLevel.multiplayerArrival = destination.arrival == null ? null : destination.arrival.clone();
        }
        else startupLevel = loadDirectConnectLevel(getDirectConnectPeer().getStatus().getFloorId());
        DirectConnectItemController items = new DirectConnectItemController(getDirectConnectPeer());
        directConnectItemController = items;
        items.rememberLevelTemplates(startupLevel);
        // Every peer builds its own native floor; Host's seed makes those builds identical.
        long floorSeed = getDirectConnectPeer().getSharedFloorSeed();
        if(floorSeed == 0L) {
            throw new IllegalStateException("Host did not announce a Shared Floor seed.");
        }
        SharedFloorBuild floorBuild = new SharedFloorBuild(floorSeed);
        DirectConnectHost host = getDirectConnectPeer() instanceof DirectConnectHost
                ? (DirectConnectHost)getDirectConnectPeer() : null;
        // A resumed Host loads its saved floor like a single-player save instead of rebuilding.
        byte[] savedFloor = host == null ? null : host.takeRestoredNativeFloor();
        if(savedFloor != null) {
            try {
                String initialRecipe = startupLevel.multiplayerNativeRecipe;
                startupLevel = NativeFloorSave.restore(savedFloor);
                if(startupLevel.multiplayerNativeRecipe == null) startupLevel.multiplayerNativeRecipe = initialRecipe;
            }
            catch(RuntimeException unreadable) {
                throw new IllegalStateException("Saved native Active Floor is incompatible or corrupt; "
                        + "previous Campaign files retained. Restore a compatible Host export.", unreadable);
            }
        }
        if(savedFloor == null) startupLevel.sharedFloorBuild = floorBuild;

        DirectConnectSessionScreen completedScreen = directConnectScreen;
        ConnectSetupScreen completedConnect = connectSetupScreen;
        createCampaign(startupLevel);
        mainScreen.setNetworkItemController(items);
        // Campaign Slot state replaces this native starting value when a saved Campaign resumes.
        GameManager.getGame().player.gold = nativeStartingGold();
        if(savedFloor != null) {
            getDirectConnectPeer().recordSharedFloorFingerprint(host.getSavedFloorFingerprint());
        }
        else if(floorBuild.getFingerprint() == null) {
            throw new IllegalStateException("Shared Floor build did not complete.");
        }
        else getDirectConnectPeer().recordSharedFloorFingerprint(floorBuild.getFingerprint());
        if(host != null) {
            host.setNativeFloorCapture(() -> {
                host.publishPartyProgression(GameManager.getGame().progression);
                return NativeFloorSave.capture(GameManager.getGame().level);
            });
            host.adoptNativeTileRules(GameManager.getGame().level);
        }
        directConnectMovementController =
                new DirectConnectMovementController(getDirectConnectPeer());
        mainScreen.setNetworkMovementController(directConnectMovementController);
        if(!directConnectMovementController.applyInitialAuthoritativeState(
                GameManager.getGame().player)) {
            throw new IllegalStateException(
                    "Direct Connect floor entered without an authoritative local spawn.");
        }
        directConnectCombatController = new DirectConnectCombatController(
                getDirectConnectPeer(), directConnectMovementController,
                OwnedGameCopyMount.isMounted());
        mainScreen.setNetworkCombatController(directConnectCombatController);
        directConnectCombatController.setWeaponResolver(items);
        com.interrupt.dungeoneer.multiplayer.economy.DirectConnectEconomyController economy =
                new com.interrupt.dungeoneer.multiplayer.economy.DirectConnectEconomyController(
                        getDirectConnectPeer(), items, directConnectCombatController);
        items.setConsumableConsumer(economy.consumableConsumer(directConnectCombatController::consumeNativeItem));
        items.setEconomyBoundary(economy);
        directConnectCombatController.setProgressResolver(economy);
        mainScreen.setNetworkEconomyController(economy);
        directConnectLivesController =
                new com.interrupt.dungeoneer.multiplayer.lives.DirectConnectLivesController(
                        getDirectConnectPeer(), directConnectMovementController);
        mainScreen.setNetworkLivesController(directConnectLivesController);
        directConnectPartyTravel = new com.interrupt.dungeoneer.multiplayer.floor.NativePartyTravel(
                getDirectConnectPeer(), items::rollFreshCharacter, this::installDirectConnectPartyFloor);
        directConnectScreen = null;
        if(completedScreen != null) completedScreen.dispose();
        connectSetupScreen = null;
        if(completedConnect != null) completedConnect.dispose();
    }

    private com.interrupt.dungeoneer.multiplayer.floor.NativePartyTravel directConnectPartyTravel;

    /** Render-thread destination swap before bridge attachment/readiness. */
    public void updateDirectConnectPartyTravel() {
        if(directConnectPartyTravel != null) directConnectPartyTravel.update(GameManager.getGame());
    }

    private void installDirectConnectPartyFloor(Level level) {
        com.interrupt.dungeoneer.game.Game game = GameManager.getGame();
        level.nativeTriggerReplica = !(getDirectConnectPeer() instanceof DirectConnectHost);
        game.installPartyFloor(level);
        directConnectMovementController.applyInitialAuthoritativeState(game.player);
        com.interrupt.dungeoneer.overlays.OverlayManager.instance.clear();
        com.interrupt.dungeoneer.Audio.stopLoopingSounds();
        if(com.interrupt.dungeoneer.game.Options.instance.enableMusic) com.interrupt.dungeoneer.Audio.playMusic(
                game.player.isHoldingOrb ? level.actionMusic : level.music, level.loopMusic);
        if(level.ambientSound != null && !com.interrupt.dungeoneer.game.Game.isMobile)
            com.interrupt.dungeoneer.Audio.playAmbientSound(level.ambientSound, level.ambientSoundVolume, 0.1f);
        if(getDirectConnectPeer() instanceof DirectConnectHost) {
            DirectConnectHost host = (DirectConnectHost)getDirectConnectPeer();
            host.setNativeFloorCapture(() -> {
                host.publishPartyProgression(game.progression);
                return NativeFloorSave.capture(game.level);
            });
            host.adoptNativeTileRules(level);
        }
    }

    /** Spectator viewpoint replacing the local camera, or null. */
    public com.interrupt.dungeoneer.multiplayer.lives.SpectatorCamera getDirectConnectSpectatorCamera() {
        return directConnectLivesController == null ? null
                : directConnectLivesController.getSpectatorCamera();
    }

    /** Centered Downed, bleedout or Revival line for local Participant, or null. */
    public String getDirectConnectLivesPrompt() {
        if(getDirectConnectPeer() != null) {
            com.interrupt.dungeoneer.multiplayer.floor.PartyTransition travel = getDirectConnectPeer().getPartyTransition();
            switch(travel.phase) {
                case GATHERING: return "Gather living Party at exit — C cancels travel";
                case COUNTDOWN: return "Party travels in " + ((travel.remainingTicks + 59) / 60) + " — C cancels";
                case LOADING: return "Party loading next area…";
                default: break;
            }
        }
        return directConnectLivesController == null ? null : directConnectLivesController.getPrompt();
    }

    public DirectConnectPeer getDirectConnectPeer() {
        return sessionFlow.getPeer();
    }

    public DirectConnectCombatController getDirectConnectCombatController() {
        return directConnectCombatController;
    }

    public DirectConnectItemController getDirectConnectItemController() {
        return directConnectItemController;
    }

    private DirectConnectItemController directConnectItemController;

    public DirectConnectMovementController getDirectConnectMovementController() {
        return directConnectMovementController;
    }

    /** Native use/trigger boundary; the Host authenticates which living body initiated it. */
    public static void requestPartyTravel(com.interrupt.dungeoneer.entities.Entity portal,
            com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext participant) {
        if(!isDirectConnectSession() || portal == null) return;
        String key = com.interrupt.dungeoneer.multiplayer.floor.PartyPortals.key(portal);
        DirectConnectPeer peer = instance.getDirectConnectPeer();
        if(peer instanceof DirectConnectHost) {
            DirectConnectHost host = (DirectConnectHost)peer;
            float portalZ = portal instanceof com.interrupt.dungeoneer.entities.Stairs
                    ? GameManager.getGame().level.getTile((int)portal.x, (int)portal.y).floorHeight + 0.5f : portal.z;
            host.registerPartyPortal(key, portal.x, portal.y, portalZ);
            com.interrupt.dungeoneer.multiplayer.participant.ParticipantId id = participant == null
                    ? null : participant.getParticipantId();
            if(id == null || com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter.LOCAL_PARTICIPANT_ID.equals(id))
                host.requestPartyTransition(key);
            else host.requestNativePartyTransition(id, key);
        }
        else peer.requestPartyTransition(key);
    }

    /** True while this process owns a live Direct Connect Campaign session. */
    public static boolean isDirectConnectSession() {
        return instance != null && instance.getDirectConnectPeer() != null;
    }

    private void createGameplay(com.interrupt.dungeoneer.game.Game.StartMode startMode,
            boolean launchImmediately) {
		instance = this;
		Gdx.app.log("DelverLifeCycle", "LibGdx Create");

        Gdx.app.setLogLevel(Application.LOG_INFO);

        gameManager = new GameManager(this);
        Gdx.input.setInputProcessor(input);
        gameManager.init();

        mainMenuScreen = new SplashScreen();
        mainScreen = new GameScreen(gameManager, input, startMode);
        gameoverScreen = new GameOverScreen(gameManager);
        levelChangeScreen = new LevelChangeScreen(gameManager);
        winScreen = new WinScreen(gameManager);

        setScreen(launchImmediately ? mainScreen : new SplashScreen());
	}

	public void createFromEditor(Level level) {
		instance = this;
		Gdx.app.log("DelverLifeCycle", "LibGdx Create From Editor");

        gameManager = new GameManager(this);
        Gdx.input.setInputProcessor(input);
        gameManager.init();

		com.interrupt.dungeoneer.game.Game.inEditor = true;
        mainMenuScreen = new SplashScreen();
        mainScreen = new GameScreen(level, gameManager, input);
        gameoverScreen = new GameOverScreen(gameManager);
        levelChangeScreen = new LevelChangeScreen(gameManager);

        setScreen(mainScreen);
	}

    private void createCampaign(Level level) {
        instance = this;
        Gdx.app.log("DelverLifeCycle", "LibGdx Create Campaign");

        ensureNativeRendering();
        input.clear();
        input.caughtCursor = true;
        Gdx.input.setInputProcessor(input);

        com.interrupt.dungeoneer.game.Game.inEditor = false;
        editorRunning = false;
        // New setup retains native backdrop through bind/retry; release it only at explicit Start.
        BaseScreen.freeBackgroundLevel();
        mainMenuScreen = new SplashScreen();
        mainScreen = new GameScreen(level, gameManager, input,
                com.interrupt.dungeoneer.game.Game.PreparedLevelMode.CAMPAIGN);
        gameoverScreen = new GameOverScreen(gameManager);
        levelChangeScreen = new LevelChangeScreen(gameManager);
        winScreen = new WinScreen(gameManager);

        setScreen(mainScreen);
    }

    /** Renderer and native managers belong to the application, not to a session. */
    private void ensureNativeRendering() {
        instance = this;
        if(gameManager != null) return;
        gameManager = new GameManager(this);
        gameManager.init();
    }

    private void releaseDirectConnectResources() {
        com.interrupt.dungeoneer.overlays.OverlayManager.instance.clear();
        setScreen(null);
        Gdx.input.setInputProcessor(null);
        Gdx.input.setCursorCatched(false);
        input.clear();
        input.setMenuUI(null);
        input.caughtCursor = false;
        input.usingGamepad = false;
        input.showingGamepadCursor = false;
        input.ignoreLastMouseLocation = true;
        if(com.interrupt.dungeoneer.game.Game.gamepadManager != null) {
            com.interrupt.dungeoneer.input.ControllerState state = com.interrupt.dungeoneer.game.Game.gamepadManager.controllerState;
            state.clearEvents();
            state.resetState();
            state.rawMove.setZero(); state.rawLook.setZero();
            state.controllerMove.setZero(); state.controllerLook.setZero();
            com.interrupt.dungeoneer.game.Game.gamepadManager.menuMode = true;
        }
        if(mainScreen != null) mainScreen.dispose();
        // Item bridge can exist before native GameScreen construction succeeds.
        else if(directConnectItemController != null) directConnectItemController.dispose();
        directConnectMovementController = null;
        directConnectCombatController = null;
        directConnectLivesController = null;
        directConnectItemController = null;
        directConnectPartyTravel = null;
        if(directConnectScreen != null) directConnectScreen.dispose();
        if(campaignLibraryScreen != null) campaignLibraryScreen.dispose();
        if(hostSetupScreen != null) hostSetupScreen.dispose();
        if(connectSetupScreen != null) connectSetupScreen.dispose();
        if(gameoverScreen != null) gameoverScreen.dispose();
        if(winScreen != null) winScreen.dispose();
        if(levelChangeScreen != null) levelChangeScreen.dispose();
        if(mainMenuScreen != null) mainMenuScreen.dispose();
        directConnectScreen = null;
        campaignLibraryScreen = null;
        mainScreen = null;
        hostSetupScreen = null;
        connectSetupScreen = null;
        gameoverScreen = null;
        winScreen = null;
        levelChangeScreen = null;
        mainMenuScreen = null;
        com.interrupt.dungeoneer.screens.BaseScreen.freeBackgroundLevel();
        com.interrupt.dungeoneer.Audio.stopLoopingSounds();
        if(gameManager != null) gameManager.releaseCampaign();
        GameScreen.resetDelta = true;
    }

    @Override
    public void dispose() {
        Gdx.app.log("DelverLifeCycle", "Goodbye");
        boolean directResources = getDirectConnectPeer() != null || directConnectScreen != null
                || campaignLibraryScreen != null || hostSetupScreen != null || connectSetupScreen != null;
        try { if(getDirectConnectPeer() != null) getDirectConnectPeer().close(); }
        finally {
            try {
                if(directResources) releaseDirectConnectResources();
                else if(isMultiplayerLauncher()) {
                    if(getScreen() != null) getScreen().dispose();
                    BaseScreen.freeBackgroundLevel();
                    com.interrupt.dungeoneer.Audio.stopLoopingSounds();
                }
            }
            finally {
                SteamApi.api.dispose();
                com.interrupt.dungeoneer.game.Game.threadPool.shutdownNow();
                OwnedGameCopyMount.unmount();
            }
        }
    }

	public static void ShowMainScreen() {
		Gdx.input.setInputProcessor( instance.input );
		instance.setScreen(instance.mainScreen);
	}

	public static void ShowGameOverScreen(boolean escaped) {

		// Only show the ending level once!
		if(escaped) {
			GameData gameData = JsonUtil.fromJson(GameData.class, com.interrupt.dungeoneer.game.Game.findInternalFileInMods("data/game.dat"));
			Level endingLevel = gameData.endingLevel;

			// Warp to the ending level, if we're not there already.
			if(endingLevel != null && (GameManager.getGame().level.levelFileName == null || !GameManager.getGame().level.levelFileName.equals(endingLevel.levelFileName))) {
				// Make a warp for this ending level!
				TriggeredWarp warp = new TriggeredWarp();
				warp.generated = endingLevel.generated;
				warp.levelToLoad = endingLevel.levelFileName;
				warp.levelTheme = endingLevel.theme;
				warp.fogColor = endingLevel.fogColor;
				warp.fogEnd = endingLevel.fogEnd;
				warp.fogStart = endingLevel.fogStart;
				warp.fogEnd = endingLevel.viewDistance;
				warp.levelName = endingLevel.levelName;
				warp.spawnMonsters = endingLevel.spawnMonsters;
				warp.objectivePrefabToSpawn = endingLevel.objectivePrefab;
				warp.skyLightColor = endingLevel.skyLightColor;
				warp.music = endingLevel.music;
				warp.ambientSound = endingLevel.ambientSound;
				// Warp must be initialized to work correctly.
				warp.init(endingLevel, Level.Source.SPAWNED);

				GameManager.getGame().player.makeEscapeEffects = false;
				GameManager.getGame().warpToLevel("ending", warp);

				return;
			}
		}

		GameManager.getGame().gameOver = true;
		instance.gameoverScreen.gameOver = !escaped;

		if(escaped) {
			instance.setScreen(instance.winScreen);
		}
		else {
			instance.setScreen(instance.gameoverScreen);
		}
	}

	public static void ShowLevelChangeScreen(Stairs stair) {
		instance.levelChangeScreen.stair = stair;
		instance.levelChangeScreen.triggeredWarp = null;
		instance.mainScreen.saveOnPause = false;

		instance.setScreen(instance.levelChangeScreen);
	}

	public static void ShowLevelChangeScreen(TriggeredWarp warp) {
		instance.levelChangeScreen.triggeredWarp = warp;
		instance.levelChangeScreen.stair = null;
		instance.mainScreen.saveOnPause = false;

		instance.setScreen(instance.levelChangeScreen);
	}

	public static void SetScreen(Screen newScreen) {
		instance.setScreen(newScreen);
	}

	public static void SetSaveLocation(int saveLoc) {
		instance.mainScreen.saveLoc = saveLoc;
	}

	public static void ShowMainMenuScreen() {
        if(isDirectConnectSession()) {
            final GameApplication application = instance;
            final DirectConnectPeer expected = application.getDirectConnectPeer();
            final com.badlogic.gdx.Screen screen = application.getScreen();
            Gdx.app.postRunnable(() -> {
                if(application.getDirectConnectPeer() == expected && application.getScreen() == screen)
                    application.leaveDirectConnectSession();
            });
            return;
        }

        if(instance.isMultiplayerLauncher()) {
            instance.showMultiplayerMenu();
            return;
        }
		instance.mainScreen.didStart = false;
		instance.setScreen(new MainMenuScreen());
	}
}
