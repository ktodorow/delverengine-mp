package com.interrupt.dungeoneer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol;
import com.interrupt.dungeoneer.overlays.OptionsOverlay;

/** Native Delver presentation with multiplayer entry actions, never single-player saves. */
public final class MultiplayerMenuScreen extends BaseScreen {
    private final GameApplication application;
    private final Label message;
    private boolean disposed;

    public MultiplayerMenuScreen(GameApplication application) {
        this.application = application;
        screenName = "MultiplayerMenuScreen";
        splashLevel = splashScreenInfo.backgroundLevel;
        viewport = new FitViewport(426, 240);
        ui = new Stage(viewport);

        Table root = new Table();
        root.setFillParent(true);
        Table panel = new Table(skin);
        panel.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
        panel.pad(12);
        panel.add(new Label("Delver Multiplayer", skin)).padBottom(8);
        panel.row();
        button(panel, "Host", application::showMultiplayerHostLibrary);
        button(panel, "Connect", application::promptMultiplayerConnect);
        button(panel, "Options", () -> {
            GameApplication.SetScreen(new OverlayWrapperScreen(new OptionsOverlay(false, true)));
            dispose();
        });
        button(panel, "Quit", () -> Gdx.app.exit());
        message = new Label("", skin);
        message.setWrap(true);
        message.setAlignment(Align.center);
        message.setFontScale(0.75f);
        panel.add(message).width(230).padTop(4);
        root.add(panel);
        root.row();
        Label attribution = new Label("2018 Priority Interrupt Games", skin);
        attribution.setColor(Color.GRAY);
        attribution.setFontScale(0.75f);
        root.add(attribution).padTop(8);
        root.row();
        Label build = new Label("Unofficial Multiplayer | " + DirectConnectProtocol.BUILD_ID, skin);
        build.setFontScale(0.65f);
        root.add(build).padTop(2);
        ui.addActor(root);
    }

    private void button(Table panel, String label, Runnable action) {
        TextButton button = new TextButton(" " + label + " ", skin);
        Runnable invoke = () -> Gdx.app.postRunnable(() -> {
            if(disposed || application.getScreen() != this) return;
            try { action.run(); }
            catch(RuntimeException failure) { showFailure(failure.getMessage()); }
        });
        button.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { invoke.run(); }
        });
        panel.add(button).width(140).height(24).padBottom(4);
        panel.row();
        gamepadEntries.add(new GamepadEntry(button, new GamepadEntryListener() {
            @Override public void onPress() { invoke.run(); }
        }, new GamepadEntryListener()));
    }

    @Override public void show() {
        // BaseScreen clears navigation entries; preserve this screen's native actions.
        com.badlogic.gdx.utils.Array<GamepadEntry> entries = new com.badlogic.gdx.utils.Array<>(gamepadEntries);
        super.show();
        gamepadEntries.addAll(entries);
        gamepadSelectionIndex = 0;
        Gdx.input.setInputProcessor(ui);
        if(splashScreenInfo.music != null) Audio.playMusic(splashScreenInfo.music, true);
    }

    public void showFailure(String reason) { message.setText(reason == null ? "Unable to open session." : reason); }

    @Override protected void tick(float delta) {
        super.tick(delta);
        ui.act(delta);
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) Gdx.app.exit();
    }

    @Override protected void draw(float delta) {
        Gdx.gl.glViewport(0, 0, curWidth, curHeight);
        super.draw(delta);
        // Restore UI viewport after native renderer's world passes.
        viewport.apply();
        ui.draw();
    }

    @Override public void resize(int width, int height) {
        curWidth = width;
        curHeight = height;
        viewport.update(width, height, true);
        GameManager.renderer.setSize(width, height);
    }

    @Override public void dispose() {
        if(disposed) return;
        disposed = true;
        if(Gdx.input.getInputProcessor() == ui) Gdx.input.setInputProcessor(null);
        ui.dispose();
    }
}
