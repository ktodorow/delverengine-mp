package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
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
        OPEN_SOURCE_TEST_LEVEL,
        OWNED_TUTORIAL,
        DIRECT_CONNECT_HOST,
        DIRECT_CONNECT_CLIENT
    }

	protected GameManager gameManager = null;
	public GameInput input = new GameInput();
    private final StartupMode startupMode;
    private final String directConnectAddress;
    private final int directConnectPort;
    private final CampaignRoster directConnectRoster;
    private final CampaignRosterStore directConnectRosterStore;
    private final LauncherIdentity directConnectLauncherIdentity;
    private final SlotPresentation directConnectPresentation;
    private final int directConnectRequestedSlot;
    private final ReconnectTokenStore directConnectReconnectTokens;
    private String directConnectFloor;
    private DirectConnectPeer directConnectPeer;
    private DirectConnectSessionScreen directConnectScreen;
    private DirectConnectMovementController directConnectMovementController;
    private DirectConnectCombatController directConnectCombatController;
    private boolean enteredDirectConnectFloor = false;

    public GameScreen mainScreen;
    public GameOverScreen gameoverScreen;
    public LevelChangeScreen levelChangeScreen;
    public SplashScreen mainMenuScreen;

    public WinScreen winScreen;

    public static GameApplication instance;
    public static boolean editorRunning = false;

    public GameApplication() {
        this(StartupMode.NORMAL, null, 0, null, null, null, null, 0, null);
    }

    private GameApplication(StartupMode startupMode) {
        this(startupMode, null, 0, null, null, null, null, 0, null);
    }

    private GameApplication(StartupMode startupMode, String directConnectAddress,
            int directConnectPort, CampaignRoster directConnectRoster,
            CampaignRosterStore directConnectRosterStore,
            LauncherIdentity directConnectLauncherIdentity,
            SlotPresentation directConnectPresentation, int directConnectRequestedSlot,
            ReconnectTokenStore directConnectReconnectTokens) {
        this.startupMode = startupMode;
        this.directConnectAddress = directConnectAddress;
        this.directConnectPort = directConnectPort;
        this.directConnectRoster = directConnectRoster;
        this.directConnectRosterStore = directConnectRosterStore;
        this.directConnectLauncherIdentity = directConnectLauncherIdentity;
        this.directConnectPresentation = directConnectPresentation;
        this.directConnectRequestedSlot = directConnectRequestedSlot;
        this.directConnectReconnectTokens = directConnectReconnectTokens;
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
                roster, rosterStore, null, null, 0, null);
        application.directConnectFloor = ownedFloor;
        return application;
    }

    public static GameApplication forDirectConnectClient(String address, int port,
            LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, ReconnectTokenStore reconnectTokens) {
        return new GameApplication(StartupMode.DIRECT_CONNECT_CLIENT, address, port,
                null, null, launcherIdentity, presentation, requestedSlot, reconnectTokens);
    }

	@Override
	public void create() {
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
        DirectConnectCompatibility compatibility = createDirectConnectCompatibility();
        if(startupMode == StartupMode.DIRECT_CONNECT_HOST) {
            if(directConnectFloor != null && !OwnedGameCopyMount.isMounted()) {
                throw new IllegalStateException("Owned Direct Connect floor requires a validated Owned Game Copy.");
            }
            Level authoritativeLevel = loadDirectConnectLevel(directConnectFloor);
            DirectConnectHost host = DirectConnectHost.start(directConnectPort, compatibility,
                    directConnectRoster, directConnectRosterStore,
                    new LevelMovementCollisionWorld(authoritativeLevel));
            if(directConnectFloor != null) host.useOwnedFloor(directConnectFloor);
            directConnectPeer = host;
        }
        else {
            directConnectPeer = DirectConnectClient.connect(directConnectAddress,
                    directConnectPort, directConnectLauncherIdentity,
                    directConnectPresentation, directConnectRequestedSlot,
                    directConnectReconnectTokens, compatibility);
        }
        directConnectScreen = new DirectConnectSessionScreen(this, directConnectPeer);
        setScreen(directConnectScreen);
    }

    private DirectConnectCompatibility createDirectConnectCompatibility() {
        OwnedGameCopyCompatibility ownedCopy = OwnedGameCopyMount.getCompatibility();
        return ownedCopy == null ? createOpenSourceCompatibility()
                : DirectConnectCompatibility.forOwnedGameCopy(ownedCopy);
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
        if(floorId != null && !OWNED_TUTORIAL_FLOOR.equals(floorId)) {
            if(!isOwnedLevelFloor(floorId)) {
                throw new IllegalStateException("Host announced an unsupported owned floor: " + floorId);
            }
            // Native entities such as TriggeredShop read localized defaults while deserializing.
            com.interrupt.managers.StringManager.init();
            com.badlogic.gdx.files.FileHandle file = com.interrupt.dungeoneer.game.Game.getInternal(floorId);
            Level owned = file == null || !file.exists() ? null : KryoSerializer.loadLevel(file);
            if(owned == null) {
                throw new IllegalStateException("Could not load owned Direct Connect floor: " + floorId);
            }
            if(owned.theme == null) owned.theme = ownedFloorTheme(floorId);
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
        if(enteredDirectConnectFloor || directConnectPeer == null
                || directConnectPeer.getStatus().getPhase() != DirectConnectPhase.READY) return;
        enteredDirectConnectFloor = true;

        // Clients load the floor announced by Host from their own certified Owned Game Copy.
        Level startupLevel = loadDirectConnectLevel(directConnectPeer instanceof DirectConnectHost
                ? directConnectFloor : directConnectPeer.getStatus().getFloorId());
        DirectConnectItemController items = new DirectConnectItemController(directConnectPeer);
        items.rememberLevelTemplates(startupLevel);
        // Every peer builds its own native floor; Host's seed makes those builds identical.
        long floorSeed = directConnectPeer.getSharedFloorSeed();
        if(floorSeed == 0L) {
            throw new IllegalStateException("Host did not announce a Shared Floor seed.");
        }
        SharedFloorBuild floorBuild = new SharedFloorBuild(floorSeed);
        startupLevel.sharedFloorBuild = floorBuild;

        DirectConnectSessionScreen completedScreen = directConnectScreen;
        directConnectScreen = null;
        createFromEditor(startupLevel);
        // Game(Level) is DelvEdit's play-test start and grants debug gold before Campaign Slots
        // copy the starting character; a new Co-op Campaign starts with native starting gold.
        GameManager.getGame().player.gold = nativeStartingGold();
        if(floorBuild.getFingerprint() == null) {
            throw new IllegalStateException("Shared Floor build did not complete.");
        }
        directConnectPeer.recordSharedFloorFingerprint(floorBuild.getFingerprint());
        directConnectMovementController =
                new DirectConnectMovementController(directConnectPeer);
        if(!directConnectMovementController.applyInitialAuthoritativeState(
                GameManager.getGame().player)) {
            throw new IllegalStateException(
                    "Direct Connect floor entered without an authoritative local spawn.");
        }
        mainScreen.setNetworkMovementController(directConnectMovementController);
        directConnectCombatController = new DirectConnectCombatController(
                directConnectPeer, directConnectMovementController,
                OwnedGameCopyMount.isMounted());
        mainScreen.setNetworkCombatController(directConnectCombatController);
        directConnectCombatController.setWeaponResolver(items);
        com.interrupt.dungeoneer.multiplayer.economy.DirectConnectEconomyController economy =
                new com.interrupt.dungeoneer.multiplayer.economy.DirectConnectEconomyController(
                        directConnectPeer, items, directConnectCombatController);
        items.setConsumableConsumer(economy.consumableConsumer(directConnectCombatController::consumeNativeItem));
        items.setEconomyBoundary(economy);
        directConnectCombatController.setProgressResolver(economy);
        mainScreen.setNetworkItemController(items);
        mainScreen.setNetworkEconomyController(economy);
        completedScreen.dispose();
    }

    public DirectConnectPeer getDirectConnectPeer() {
        return directConnectPeer;
    }

    private void createGameplay(com.interrupt.dungeoneer.game.Game.StartMode startMode,
            boolean launchImmediately) {
		instance = this;
		Gdx.app.log("DelverLifeCycle", "LibGdx Create");

        Gdx.app.setLogLevel(Application.LOG_INFO);

		gameManager = new GameManager(this);
        Gdx.input.setInputProcessor( input );
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
        Gdx.input.setInputProcessor( input );
        gameManager.init();

		com.interrupt.dungeoneer.game.Game.inEditor = true;
        mainMenuScreen = new SplashScreen();
        mainScreen = new GameScreen(level, gameManager, input);
        gameoverScreen = new GameOverScreen(gameManager);
        levelChangeScreen = new LevelChangeScreen(gameManager);

        setScreen(mainScreen);
	}

	@Override
	public void dispose() {
		Gdx.app.log("DelverLifeCycle", "Goodbye");
		if(directConnectPeer != null) directConnectPeer.close();
        if(directConnectMovementController != null) directConnectMovementController.dispose();
		if(directConnectScreen != null) directConnectScreen.dispose();
		if(mainScreen != null) mainScreen.dispose();
		SteamApi.api.dispose();
        com.interrupt.dungeoneer.game.Game.threadPool.shutdownNow();
        OwnedGameCopyMount.unmount();
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
		instance.mainScreen.didStart = false;
		instance.setScreen(new MainMenuScreen());
	}
}
