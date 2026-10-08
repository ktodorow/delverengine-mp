package com.interrupt.dungeoneer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignLibrary;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;

import java.util.Collections;
import java.util.List;
import java.io.File;

/** Host-facing Campaign Library shown before a Direct Connect listener is opened. */
public final class CampaignLibraryScreen extends BaseScreen {
    private final GameApplication application;
    private final CampaignLibrary library;
    private final int newCampaignCapacity;
    private final Table campaignRows = new Table();
    private final Label message;
    private final ButtonGroup<TextButton> selection = new ButtonGroup<>();
    private volatile List<CampaignLibrary.Entry> entries = Collections.emptyList();
    private volatile String error;
    private int selected;
    private boolean promptOpen;
    private boolean opening;
    private boolean disposed;

    public CampaignLibraryScreen(GameApplication application, CampaignLibrary library,
            int newCampaignCapacity) {
        if(application == null) throw new IllegalArgumentException("Game application cannot be null.");
        if(library == null) throw new IllegalArgumentException("Campaign Library cannot be null.");
        if(newCampaignCapacity < 2 || newCampaignCapacity > 4) {
            throw new IllegalArgumentException("Campaign Capacity must be 2, 3, or 4.");
        }
        this.application = application;
        this.library = library;
        this.newCampaignCapacity = newCampaignCapacity;
        screenName = "CampaignLibraryScreen";
        splashLevel = splashScreenInfo.backgroundLevel;
        viewport = new FitViewport(426, 280);
        ui = new Stage(viewport);
        Table root = new Table();
        root.setFillParent(true);
        Table panel = new Table(skin);
        panel.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
        panel.pad(10);
        panel.add(new Label("Co-op Campaigns", skin)).padBottom(6);
        panel.row();
        Table primary = new Table();
        action(primary, "New Campaign", 160, this::requestNewCampaign);
        action(primary, "Back", 160, () -> {
            if(application.isMultiplayerLauncher()) application.showMultiplayerMenu();
        });
        panel.add(primary).padBottom(6);
        panel.row();
        ScrollPane scroll = new ScrollPane(campaignRows);
        scroll.setScrollingDisabled(true, false);
        panel.add(scroll).width(350).height(110);
        panel.row();
        Table actions = new Table();
        action(actions, "Resume", 80, this::resumeSelected);
        action(actions, "Recover", 80, this::recoverSelected);
        action(actions, "Export", 80, () -> { if(!entries.isEmpty()) requestExport(); });
        action(actions, "Import", 80, this::requestImport);
        panel.add(actions).padTop(6);
        panel.row();
        message = new Label("", skin);
        message.setFontScale(0.7f);
        message.setWrap(true);
        panel.add(message).width(350).height(32).padTop(4);
        root.add(panel);
        ui.addActor(root);
        refresh();
    }

    @Override public void show() {
        super.show();
        Gdx.input.setInputProcessor(ui);
        Gdx.input.setCursorCatched(false);
    }

    @Override protected void tick(float delta) {
        super.tick(delta);
        handleInput();
        ui.act(delta);
        message.setText((opening ? "Opening Campaign..." : error == null
                ? "N: New Campaign | Enter: Resume | R: Recover | Esc: Back" : error).replace("[", "[["));
        if(selected < selection.getButtons().size) selection.getButtons().get(selected).setChecked(true);
    }

    @Override protected void draw(float delta) {
        Gdx.gl.glViewport(0, 0, curWidth, curHeight);
        super.draw(delta);
        viewport.apply();
        ui.draw();
    }

    private void action(Table parent, String label, int width, Runnable action) {
        TextButton button = new TextButton(label, skin);
        button.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                Gdx.app.postRunnable(() -> {
                    if(disposed || opening || promptOpen || application.getScreen() != CampaignLibraryScreen.this) return;
                    try { action.run(); } catch(RuntimeException failure) { fail(failure); }
                });
            }
        });
        parent.add(button).width(width).height(24).padRight(4);
    }

    private void resumeSelected() {
        if(entries.isEmpty()) return;
        CampaignLibrary.Entry entry = entries.get(selected);
        if(entry.isArchived()) error = library.describeArchive(entry.getCampaignId(), application.getCampaignCompatibility());
        else open(library.resume(entry.getCampaignId()));
    }

    private void recoverSelected() {
        if(!entries.isEmpty()) open(library.recover(entries.get(selected).getCampaignId(), application.getCampaignCompatibility()));
    }

    private void handleInput() {
        if(opening || promptOpen) return;
        if(application.isMultiplayerLauncher() && Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            Gdx.app.postRunnable(() -> {
                if(!disposed && application.getScreen() == this) application.showMultiplayerMenu();
            });
            return;
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.UP) && !entries.isEmpty()) {
            selected = (selected + entries.size() - 1) % entries.size();
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.DOWN) && !entries.isEmpty()) {
            selected = (selected + 1) % entries.size();
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !entries.isEmpty()) {
            try { resumeSelected(); }
            catch(RuntimeException failure) { fail(failure); }
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.R) && !entries.isEmpty()) {
            try { recoverSelected(); }
            catch(RuntimeException failure) { fail(failure); }
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.E) && !entries.isEmpty()) requestExport();
        if(Gdx.input.isKeyJustPressed(Input.Keys.I)) requestImport();
        if(Gdx.input.isKeyJustPressed(Input.Keys.N)) requestNewCampaign();
    }

    private void requestExport() {
        final String id = entries.get(selected).getCampaignId();
        promptOpen = true;
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override public void input(String text) {
                if(disposed || application.getScreen() != CampaignLibraryScreen.this) return;
                promptOpen = false;
                try {
                    File exported = library.exportCampaign(id, new File(text.trim()), application.getCampaignCompatibility());
                    error = "Export saved: " + exported + ". Move original Host Identity Recovery File separately to keep ownership.";
                }
                catch(RuntimeException failure) { fail(failure); }
            }
            @Override public void canceled() { promptOpen = false; }
        }, "Campaign Export - original Host only", "", "Unused full path, e.g. C:\\Backups\\friends.delvercampaign");
    }

    private void requestImport() {
        promptOpen = true;
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override public void input(String text) {
                if(disposed || application.getScreen() != CampaignLibraryScreen.this) return;
                promptOpen = false;
                try {
                    CampaignRoster imported = library.importCampaign(new File(text.trim()), application.getCampaignCompatibility());
                    error = "Imported " + imported.getCampaignName() + ". Original Host ownership preserved.";
                    refresh();
                }
                catch(RuntimeException failure) { fail(failure); }
            }
            @Override public void canceled() { promptOpen = false; }
        }, "Import Campaign Export", "", "Full path to original Host export");
    }

    private void requestNewCampaign() {
        Gdx.app.postRunnable(() -> {
            if(!disposed && application.getScreen() == this) application.showNewCampaignSetup(newCampaignCapacity);
        });
    }

    private void open(final CampaignRoster roster) {
        opening = true;
        error = null;
        Gdx.app.postRunnable(new Runnable() {
            @Override public void run() {
                if(disposed || application.getScreen() != CampaignLibraryScreen.this) return;
                try { application.hostDirectConnectCampaign(roster); }
                catch(RuntimeException failure) {
                    opening = false;
                    fail(failure);
                    refresh();
                }
            }
        });
    }

    private void refresh() {
        try {
            entries = library.list();
            if(selected >= entries.size()) selected = Math.max(0, entries.size() - 1);
            campaignRows.clearChildren();
            selection.clear();
            if(entries.isEmpty()) campaignRows.add(new Label("No Co-op Campaigns yet.", skin));
            for(int index = 0; index < entries.size(); index++) {
                final int row = index;
                TextButton button = new TextButton(campaignLine(entries.get(index)).replace("[", "[["),
                        com.interrupt.dungeoneer.ui.UiSkin.createChoiceButtonStyle(skin));
                button.getLabel().setFontScale(0.7f);
                button.getLabel().setWrap(true);
                selection.add(button);
                button.setChecked(index == selected);
                button.addListener(new ClickListener() {
                    @Override public void clicked(InputEvent event, float x, float y) { selected = row; }
                });
                campaignRows.add(button).width(340).height(40).padBottom(3);
                campaignRows.row();
            }
        }
        catch(RuntimeException failure) { fail(failure); }
    }

    private void fail(RuntimeException failure) {
        String message = failure.getMessage();
        error = message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    public void showFailure(String message) { error = message; }

    static String campaignLine(CampaignLibrary.Entry entry) {
        return entry.getCampaignName() + "  |  "
                + (entry.isArchived() ? "Campaign Archive (read-only)" : entry.needsRecovery()
                        ? "Unclean shutdown - [R] Recover" : entry.hasSave() ? "Saved Campaign" : "Not started")
                + "  |  Capacity " + entry.getCapacity()
                + "  |  Claimed " + entry.getClaimedSlots();
    }

    @Override public void resize(int width, int height) {
        curWidth = width; curHeight = height;
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
