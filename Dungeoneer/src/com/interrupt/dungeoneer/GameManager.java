package com.interrupt.dungeoneer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.interrupt.api.steam.SteamApi;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.gfx.GlRenderer;
import com.interrupt.dungeoneer.input.Actions;
import com.interrupt.dungeoneer.overlays.Overlay;
import com.interrupt.dungeoneer.overlays.OverlayManager;
import com.interrupt.dungeoneer.overlays.PauseOverlay;
import com.interrupt.managers.StringManager;
import com.interrupt.utils.Logger;

/**
 * 
 * @author ccuddigan
 * Wrapper container for the Game class. Anything that needs to talk to the static Game can get it from here.
 */
public class GameManager {
	
	protected static Game game;
	public static GlRenderer renderer;
	protected GameApplication myGameApp = null;
	public static boolean gameHasStarted = false;
	
	public boolean running = true;
	
	public GameManager() { }
	
	public GameManager(GameApplication gameapp) {
		myGameApp = gameapp;

		Gdx.app.log("DelverLifeCycle", "Created GL2.0 Renderer");
		renderer = new GlRenderer();

		StringManager.init();
		running = true;
	}
	
	public void startGame(int saveLoc) {
        startGame(saveLoc, Game.StartMode.NORMAL);
    }

    public void startGame(int saveLoc, Game.StartMode startMode) {
        game = new Game(saveLoc, startMode);
		game.setInputHandler(myGameApp.input);
		
		gameHasStarted = true;
		renderer.initHud();
		
		running = true;
	}
	
	public void startGame(Level level) {
		startPreparedLevel(level, Game.PreparedLevelMode.EDITOR);
	}

	public void startCampaign(Level level) {
		startPreparedLevel(level, Game.PreparedLevelMode.CAMPAIGN);
	}

	private void startPreparedLevel(Level level, Game.PreparedLevelMode mode) {
		game = new Game(level, mode);
		game.setInputHandler(myGameApp.input);
		
		gameHasStarted = true;
		renderer.initHud();
		
		running = true;
		GameApplication.editorRunning = mode == Game.PreparedLevelMode.EDITOR;
		
		Options.instance.uiSize = 0.8f;
	}

	/** DelvEdit play-tests stop on Esc; a Direct Connect session opens the ordinary pause menu. */
	static boolean escapeEndsEditorPlaytest() {
		return Game.inEditor && !GameApplication.isDirectConnectSession();
	}

	/**
	 * Esc or gamepad Pause while playing: close the top overlay or menu, otherwise open the pause
	 * menu. Single-player never ticks under an overlay that pauses the game, so that overlay owns
	 * Esc; a Direct Connect world keeps ticking under it and must leave Esc to it too, or the
	 * still-held key closes the pause menu the frame after opening it.
	 */
	static void handleEscape(java.util.function.Supplier<Overlay> pauseMenu) {
		if (OverlayManager.instance.shouldPauseGame()) return;

		if (OverlayManager.instance.current() != null) {
			Game.ignoreEscape = true;
			OverlayManager.instance.pop();
		}

		if (Game.instance != null && (Game.instance.getShowingMenu() || Game.instance.getInteractMode())) {
			Game.ignoreEscape = true;

			if (Game.instance.getShowingMenu())
				Game.instance.toggleInventory();
			else if (Game.instance.getInteractMode())
				Game.instance.toggleInteractMode();
		}

		if (!Game.ignoreEscape) {
			OverlayManager.instance.push(pauseMenu.get());
			Game.ignoreEscape = true;
		}
	}

	/** Esc, Back and gamepad Pause. Direct Connect also polls this while its world is held still. */
	public void pollEscape() {
		if (game == null || game.player == null || game.player.isDead) return;
		if (Game.isMobile && Gdx.input.isKeyPressed(Input.Keys.BACK)) {
			OverlayManager.instance.push(new PauseOverlay());
		} else if (Gdx.input.isKeyPressed(Input.Keys.ESCAPE) || this.myGameApp.input.gamepadManager.controllerState.buttonEvents.contains(Actions.Action.PAUSE, true)) {

			if (escapeEndsEditorPlaytest()) {
				GameApplication.editorRunning = false;
				running = false;
				GameManager.getGame().level.preSaveCleanup();
			} else {
				handleEscape(PauseOverlay::new);
			}
		} else if (!Gdx.input.isKeyPressed(Input.Keys.ESCAPE) && Game.ignoreEscape)
			Game.ignoreEscape = false;
	}

	private float time_since_last_tick = 0f;
	public void tick(float delta) {
		try {
			// put a cap on tick rates
			time_since_last_tick += delta;
			if(time_since_last_tick < 0.3333333f) {
				return;
			}
			else {
				delta = time_since_last_tick;
				time_since_last_tick = 0;
			}

			GameManager.renderer.clearLights();

			pollEscape();

			if (running) game.tick(delta);

			SteamApi.api.runCallbacks();
		}
		catch (Exception ex) {
			// Something really bad happened
			Logger.logExceptionToFile(ex);
			Gdx.app.exit();
		}
	}
	
	public void render() {
		try {
			renderer.render(game);
		}
		catch (Exception ex) {
			// Something really bad happened
			Logger.logExceptionToFile(ex);
			Gdx.app.exit();
		}
	}
	
	public void init()
	{
		renderer.init();
	}

    /** Retire one campaign while retaining the application's initialized renderer. */
    public void releaseCampaign() {
        running = false;
        time_since_last_tick = 0f;
        gameHasStarted = false;
        if(game != null) game.setInputHandler(null);
        game = null;
        Game.instance = null;
        Game.ignoreEscape = false;
        if(Game.ui != null) Game.ui.clear();
        if(renderer != null) renderer.releaseCampaign();
    }
	
	public static Game getGame()
	{
		return game;
	}
}
