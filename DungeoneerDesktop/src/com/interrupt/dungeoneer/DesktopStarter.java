package com.interrupt.dungeoneer;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Graphics.DisplayMode;
import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentityStore;
import com.interrupt.dungeoneer.multiplayer.lobby.ProfileReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import com.interrupt.dungeoneer.owned.OwnedGameCopyValidationException;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

public class DesktopStarter {
    public static void main(String[] args) {
        DesktopLaunchOptions launchOptions = DesktopLaunchOptions.parse(args);
        boolean directConnect = launchOptions.directHost
                || launchOptions.directConnectAddress != null;
        LauncherIdentity launcherIdentity = null;
        SlotPresentation slotPresentation = null;
        CampaignRoster campaignRoster = null;
        CampaignRosterStore campaignRosterStore = null;
        ReconnectTokenStore reconnectTokenStore = null;

        if (args != null) {
            for (String arg : args) {
                if (arg.toLowerCase().endsWith("debug=true")) {
                    Game.isDebugMode = true;
                }
                else if (arg.toLowerCase().endsWith("debug-collision=true")) {
                    Game.drawDebugBoxes = true;
                }
                else if (arg.toLowerCase().endsWith("version")){
                    System.out.println(Game.VERSION);
                    System.exit(0);
                }
                else if (arg.equalsIgnoreCase("--test-level") || arg.equalsIgnoreCase("test-level=true")) {
                    Game.isDebugMode = true;
                }
            }
        }

        if(launchOptions.openSourceTestLevel && launchOptions.ownedTutorial) {
            throw new IllegalArgumentException("Choose either --test-level or --owned-tutorial, not both.");
        }

        if(directConnect) Game.isDebugMode = true;

        // Test content must not create or read a player profile in the source tree.
        if(launchOptions.openSourceTestLevel) {
            Options.SetKeyboardBindings();
        }
        else if(directConnect) {
            if(launchOptions.profileRoot == null) MultiplayerProfile.initializeDefault();
            else MultiplayerProfile.initialize(launchOptions.profileRoot);
            launcherIdentity = LauncherIdentityStore.loadOrCreate();
            slotPresentation = new SlotPresentation(
                    launchOptions.nickname, launchOptions.avatarId);
            if(launchOptions.directHost) {
                campaignRosterStore = new CampaignRosterStore();
                campaignRoster = campaignRosterStore.loadOrCreate(
                        launchOptions.campaignId, launchOptions.campaignCapacity,
                        AvatarCatalog.ownedV108Humanoids(), launcherIdentity,
                        slotPresentation);
            }
            else {
                reconnectTokenStore = new ProfileReconnectTokenStore();
            }
            Options.SetKeyboardBindings();
        }
        else {
            MultiplayerProfile.initializeDefault();
            try {
                if(launchOptions.inspectOwnedCopy) {
                    OwnedGameCopyLauncher.inspect(launchOptions);
                    return;
                }
                if(launchOptions.ownedTutorial) OwnedGameCopyLauncher.validateAndMount(launchOptions);
            }
            catch(OwnedGameCopyValidationException ex) {
                reportOwnedCopyError(ex.getMessage());
                throw new IllegalStateException("Owned Game Copy launch blocked: " + ex.getMessage(), ex);
            }
            Options.loadOptions();
        }

        DisplayMode defaultMode = LwjglApplicationConfiguration.getDesktopDisplayMode();

        LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
        config.title = launchOptions.ownedTutorial || directConnect
                ? "Delver Multiplayer" : "Delver Engine";
        config.fullscreen = Options.instance.fullScreen;
        config.width = defaultMode.width;
        config.height = defaultMode.height;
        config.vSyncEnabled = Options.instance.vsyncEnabled;
        config.samples = Options.instance.antiAliasingSamples;
        config.stencil = 8;
        config.foregroundFPS = Options.instance.fpsLimit;

        if (!config.fullscreen) {
            config.width *= 0.8;
            config.height *= 0.8;
        }

        // More sounds! Libgdx sets these settings low by default
        config.audioDeviceBufferCount *= 2;
        config.audioDeviceSimultaneousSources *= 2;

        config.addIcon("icon-128.png", Files.FileType.Internal); // 128x128 icon (mac OS)
        config.addIcon("icon-32.png", Files.FileType.Internal);  // 32x32 icon (Windows + Linux)
        config.addIcon("icon-16.png", Files.FileType.Internal);  // 16x16 icon (Windows)
        configureProcessExit(config);

        GameApplication gameApplication;
        if(launchOptions.directHost) {
            gameApplication = GameApplication.forDirectConnectHost(launchOptions.sessionPort,
                    campaignRoster, campaignRosterStore);
        }
        else if(launchOptions.directConnectAddress != null) {
            gameApplication = GameApplication.forDirectConnectClient(
                    launchOptions.directConnectAddress, launchOptions.sessionPort,
                    launcherIdentity, slotPresentation, launchOptions.requestedSlot,
                    reconnectTokenStore);
        }
        else if(launchOptions.openSourceTestLevel) gameApplication = GameApplication.forOpenSourceTestLevel();
        else if(launchOptions.ownedTutorial) gameApplication = GameApplication.forOwnedTutorial();
        else gameApplication = new GameApplication();
        Thread.setDefaultUncaughtExceptionHandler(new DesktopCrashHandler(
                System.err,
                new DesktopCrashHandler.Exit() {
                    @Override
                    public void exit(int status) {
                        System.exit(status);
                    }
                }));
        new LwjglApplication(gameApplication, config);
    }

    static void configureProcessExit(LwjglApplicationConfiguration config) {
        config.forceExit = false;
    }

    private static void reportOwnedCopyError(String message) {
        System.err.println(message);
        if(!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "Owned Game Copy rejected", JOptionPane.ERROR_MESSAGE);
        }
    }
}
