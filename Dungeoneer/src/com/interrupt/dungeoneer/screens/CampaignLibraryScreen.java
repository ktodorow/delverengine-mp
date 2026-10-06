package com.interrupt.dungeoneer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Align;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignLibrary;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;

import java.util.Collections;
import java.util.List;
import java.io.File;

/** Host-facing Campaign Library shown before a Direct Connect listener is opened. */
public final class CampaignLibraryScreen implements Screen {
    private final GameApplication application;
    private final CampaignLibrary library;
    private final int newCampaignCapacity;
    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont font = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();
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
        refresh();
    }

    @Override public void show() { }

    @Override
    public void render(float delta) {
        handleInput();
        Gdx.gl.glClearColor(0.035f, 0.045f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        camera.update();
        batch.setProjectionMatrix(camera.combined);

        float width = camera.viewportWidth;
        float y = camera.viewportHeight * 0.86f;
        batch.begin();
        font.getData().setScale(1.4f);
        font.draw(batch, "Co-op Campaign Library", width * 0.05f, y,
                width * 0.9f, Align.center, false);
        font.getData().setScale(1f);
        y -= 44f;
        font.draw(batch, "[N] New Campaign (Capacity " + newCampaignCapacity
                        + ")   [UP/DOWN] Select   [ENTER] Host / inspect archive\n"
                        + "[R] Recover after crash   [E] Export   [I] Import Host export",
                width * 0.05f, y, width * 0.9f, Align.center, true);
        y -= 42f;
        if(entries.isEmpty()) {
            font.draw(batch, "No Co-op Campaigns yet. Press N to create one.",
                    width * 0.1f, y, width * 0.8f, Align.center, true);
        }
        else {
            for(int index = 0; index < entries.size() && index < 12; index++) {
                CampaignLibrary.Entry entry = entries.get(index);
                font.draw(batch, (index == selected ? "> " : "  ") + campaignLine(entry),
                        width * 0.12f, y, width * 0.76f, Align.left, false);
                y -= 28f;
            }
        }
        if(opening) {
            y -= 24f;
            font.draw(batch, "Opening Campaign...", width * 0.1f, y,
                    width * 0.8f, Align.center, true);
        }
        else if(error != null) {
            y -= 24f;
            font.draw(batch, error, width * 0.1f, y,
                    width * 0.8f, Align.center, true);
        }
        batch.end();
    }

    private void handleInput() {
        if(opening || promptOpen) return;
        if(Gdx.input.isKeyJustPressed(Input.Keys.UP) && !entries.isEmpty()) {
            selected = (selected + entries.size() - 1) % entries.size();
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.DOWN) && !entries.isEmpty()) {
            selected = (selected + 1) % entries.size();
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !entries.isEmpty()) {
            try {
                CampaignLibrary.Entry entry = entries.get(selected);
                if(entry.isArchived()) error = library.describeArchive(entry.getCampaignId(), application.getCampaignCompatibility());
                else open(library.resume(entry.getCampaignId()));
            }
            catch(RuntimeException failure) { fail(failure); }
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.R) && !entries.isEmpty()) {
            try { open(library.recover(entries.get(selected).getCampaignId(), application.getCampaignCompatibility())); }
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
                promptOpen = false;
                try {
                    CampaignRoster imported = library.importCampaign(new File(text.trim()), application.getCampaignCompatibility());
                    error = "Imported " + imported.getCampaignId() + ". Original Host ownership preserved.";
                    refresh();
                }
                catch(RuntimeException failure) { fail(failure); }
            }
            @Override public void canceled() { promptOpen = false; }
        }, "Import Campaign Export", "", "Full path to original Host export");
    }

    private void requestNewCampaign() {
        promptOpen = true;
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override public void input(String text) {
                promptOpen = false;
                try { open(library.create(text == null ? "" : text.trim(), newCampaignCapacity)); }
                catch(RuntimeException failure) { fail(failure); }
            }

            @Override public void canceled() { promptOpen = false; }
        }, "New Co-op Campaign", "", "Letters, numbers, dot, dash, underscore");
    }

    private void open(final CampaignRoster roster) {
        opening = true;
        error = null;
        Gdx.app.postRunnable(new Runnable() {
            @Override public void run() {
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
        }
        catch(RuntimeException failure) { fail(failure); }
    }

    private void fail(RuntimeException failure) {
        String message = failure.getMessage();
        error = message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    static String campaignLine(CampaignLibrary.Entry entry) {
        return entry.getCampaignId() + "  |  "
                + (entry.isArchived() ? "Campaign Archive (read-only)" : entry.needsRecovery()
                        ? "Unclean shutdown - [R] Recover" : entry.hasSave() ? "Saved Campaign" : "Not started")
                + "  |  Capacity " + entry.getCapacity()
                + "  |  Claimed " + entry.getClaimedSlots();
    }

    @Override public void resize(int width, int height) {
        camera.setToOrtho(false, Math.max(1, width), Math.max(1, height));
    }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void hide() { }

    @Override public void dispose() {
        if(disposed) return;
        disposed = true;
        batch.dispose();
        font.dispose();
    }
}
