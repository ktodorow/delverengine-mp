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
import java.text.DateFormat;
import java.util.Date;

/** Host-facing Campaign Library shown before a Direct Connect listener is opened. */
public final class CampaignLibraryScreen extends BaseScreen {
    private final GameApplication application;
    private final CampaignLibrary library;
    private final int newCampaignCapacity;
    private final Table campaignRows = new Table();
    private static final int CARDS_PER_PAGE = 3;
    private final Label message;
    private final Label pageLabel;
    private final TextButton previousPage, nextPage, selectedAction;
    private final Table management = new Table();
    private final String initialSelection;
    private final ButtonGroup<TextButton> selection = new ButtonGroup<>();
    private volatile List<CampaignLibrary.Entry> entries = Collections.emptyList();
    private volatile String error;
    private int selected;
    private boolean promptOpen;
    private boolean opening;
    private boolean disposed;

    public CampaignLibraryScreen(GameApplication application, CampaignLibrary library,
            int newCampaignCapacity) {
        this(application, library, newCampaignCapacity, null);
    }

    public CampaignLibraryScreen(GameApplication application, CampaignLibrary library,
            int newCampaignCapacity, String selectedCampaignId) {
        if(application == null) throw new IllegalArgumentException("Game application cannot be null.");
        if(library == null) throw new IllegalArgumentException("Campaign Library cannot be null.");
        if(newCampaignCapacity < 2 || newCampaignCapacity > 4) {
            throw new IllegalArgumentException("Campaign Capacity must be 2, 3, or 4.");
        }
        this.application = application;
        this.library = library;
        this.newCampaignCapacity = newCampaignCapacity;
        initialSelection = selectedCampaignId;
        screenName = "CampaignLibraryScreen";
        splashLevel = splashScreenInfo.backgroundLevel;
        viewport = new FitViewport(480, 420);
        ui = new Stage(viewport);
        Table root = new Table();
        root.setFillParent(true);
        Table panel = new Table(skin);
        panel.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
        panel.pad(10);
        panel.add(new Label("Co-op Campaigns", skin)).padBottom(6);
        panel.row();
        Table primary = new Table();
        action(primary, "New Campaign", 140, this::requestNewCampaign);
        action(primary, "Manage Campaigns", 140, this::toggleManagement);
        action(primary, "Back", 70, () -> {
            if(application.isMultiplayerLauncher()) application.showMultiplayerMenu();
        });
        panel.add(primary).padBottom(6);
        panel.row();
        panel.add(campaignRows).width(370).height(240);
        panel.row();
        Table pages = new Table();
        previousPage = action(pages, "<", 38, () -> changePage(-1));
        pageLabel = new Label("", skin);
        pageLabel.setFontScale(0.75f);
        pages.add(pageLabel).width(160).center();
        nextPage = action(pages, ">", 38, () -> changePage(1));
        selectedAction = action(pages, "Resume", 100, this::resumeSelected);
        panel.add(pages).padTop(3);
        panel.row();
        action(management, "Export", 120, () -> { if(!entries.isEmpty()) requestExport(); });
        action(management, "Import", 120, this::requestImport);
        management.setVisible(false);
        panel.add(management).height(0);
        panel.row();
        message = new Label("", skin);
        message.setFontScale(0.7f);
        message.setWrap(true);
        panel.add(message).width(370).height(32).padTop(4);
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
                ? "Up/Down: Select | Left/Right: Page | Enter: Open | Esc: Back" : error).replace("[", "[["));
        int local = selected % CARDS_PER_PAGE;
        if(local < selection.getButtons().size) selection.getButtons().get(local).setChecked(true);
    }

    @Override protected void draw(float delta) {
        Gdx.gl.glViewport(0, 0, curWidth, curHeight);
        super.draw(delta);
        viewport.apply();
        ui.draw();
    }

    private TextButton action(Table parent, String label, int width, Runnable action) {
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
        return button;
    }

    private void toggleManagement() {
        management.setVisible(!management.isVisible());
        ((Table)management.getParent()).getCell(management).height(management.isVisible() ? 28 : 0);
        ((Table)management.getParent()).invalidateHierarchy();
    }

    private void resumeSelected() {
        if(entries.isEmpty()) return;
        CampaignLibrary.Entry entry = entries.get(selected);
        if(entry.isArchived()) error = library.describeArchive(entry.getCampaignId(), application.getCampaignCompatibility());
        else if(entry.needsRecovery()) recoverSelected();
        else open(entry.getCampaignId());
    }

    private void recoverSelected() {
        if(entries.isEmpty()) return;
        String id = entries.get(selected).getCampaignId();
        library.recover(id, application.getCampaignCompatibility());
        refresh();
        open(id);
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
            select((selected + entries.size() - 1) % entries.size());
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.DOWN) && !entries.isEmpty()) {
            select((selected + 1) % entries.size());
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.LEFT) || Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP)) changePage(-1);
        if(Gdx.input.isKeyJustPressed(Input.Keys.RIGHT) || Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) changePage(1);
        if(Gdx.input.isKeyJustPressed(Input.Keys.M)) toggleManagement();
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

    private void open(final String campaignId) {
        opening = true;
        error = null;
        Gdx.app.postRunnable(new Runnable() {
            @Override public void run() {
                if(disposed || application.getScreen() != CampaignLibraryScreen.this) return;
                try { application.showSavedCampaignSetup(campaignId); }
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
            String id = entries.isEmpty() ? initialSelection : getSelectedCampaignId();
            entries = library.list();
            selected = Math.min(selected, Math.max(0, entries.size() - 1));
            if(id != null) for(int index = 0; index < entries.size(); index++)
                if(entries.get(index).getCampaignId().equals(id)) selected = index;
            renderPage();
        }
        catch(RuntimeException failure) { fail(failure); }
    }

    public String getSelectedCampaignId() { return entries.isEmpty() ? null : entries.get(selected).getCampaignId(); }

    private void select(int index) {
        selected = index;
        error = null;
        renderPage();
    }

    private void changePage(int direction) {
        if(entries.isEmpty()) return;
        int pages = (entries.size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE;
        int page = (selected / CARDS_PER_PAGE + direction + pages) % pages;
        select(Math.min(page * CARDS_PER_PAGE + selected % CARDS_PER_PAGE, entries.size() - 1));
    }

    /** Three native cards are viewport presentation only; every stored campaign remains reachable. */
    private void renderPage() {
        campaignRows.clearChildren();
        selection.clear();
        int first = selected / CARDS_PER_PAGE * CARDS_PER_PAGE;
        TextButton.TextButtonStyle style = com.interrupt.dungeoneer.ui.UiSkin.createChoiceButtonStyle(skin);
        NinePatch frame = new NinePatch(skin.getRegion("save-select"), 1, 1, 1, 1);
        style.up = new NinePatchDrawable(frame);
        NinePatch selectedFrame = new NinePatch(frame);
        selectedFrame.setColor(skin.getColor("gamepad-selected"));
        style.checked = style.down = new NinePatchDrawable(selectedFrame);
        for(int local = 0; local < CARDS_PER_PAGE; local++) {
            final int index = first + local;
            if(index >= entries.size()) {
                Label empty = new Label(entries.isEmpty() && local == 0 ? "No Co-op Campaigns yet. Choose New Campaign." : "", skin);
                empty.setFontScale(0.75f);
                campaignRows.add(empty).width(370).height(77).padBottom(3);
            }
            else {
                CampaignLibrary.Entry entry = entries.get(index);
                TextButton card = new TextButton(entry.getCampaignName().replace("[", "[["), style);
                Label title = card.getLabel();
                title.setFontScale(0.9f);
                title.setEllipsis(true);
                card.clearChildren();
                card.pad(7);
                card.add(title).width(348).left();
                card.row();
                Table details = new Table();
                Label floor = new Label(("Floor: " + (entry.getFloorId() == null ? "Unavailable" : entry.getFloorId())).replace("[", "[["), skin);
                floor.setFontScale(0.7f);
                floor.setEllipsis(true);
                details.add(floor).width(220).left();
                Label claimed = new Label("Claimed " + entry.getClaimedSlots() + "/" + entry.getCapacity(), skin);
                claimed.setFontScale(0.7f);
                details.add(claimed).width(128).right();
                card.add(details).width(348).left();
                card.row();
                Label saved = new Label("Last saved: " + (entry.getLastSavedTime() == 0L ? "Unavailable"
                        : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.getLastSavedTime()))), skin);
                saved.setFontScale(0.7f);
                card.add(saved).left();
                card.row();
                Label state = new Label(entry.isArchived() ? "Archive (read-only)" : entry.needsRecovery() ? "Recover"
                        : entry.getSummaryError() != null ? "Save unavailable" : entry.hasSave() ? "Resume" : "Resume - not started", skin);
                state.setFontScale(0.7f);
                card.add(state).left();
                selection.add(card);
                card.setChecked(index == selected);
                card.addListener(new ClickListener() {
                    @Override public void clicked(InputEvent event, float x, float y) {
                        if(!opening && !promptOpen) select(index);
                    }
                });
                campaignRows.add(card).width(370).height(77).padBottom(3);
            }
            campaignRows.row();
        }
        int pageCount = Math.max(1, (entries.size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE);
        pageLabel.setText("Page " + (selected / CARDS_PER_PAGE + 1) + " / " + pageCount);
        previousPage.setDisabled(pageCount == 1);
        nextPage.setDisabled(pageCount == 1);
        selectedAction.setDisabled(entries.isEmpty());
        if(!entries.isEmpty()) {
            CampaignLibrary.Entry entry = entries.get(selected);
            selectedAction.setText(entry.isArchived() ? "Archive" : entry.needsRecovery() ? "Recover" : "Resume");
            if(entry.getSummaryError() != null && !entry.needsRecovery() && !entry.isArchived())
                error = entry.getSummaryError();
        }
    }

    private void fail(RuntimeException failure) {
        String message = failure.getMessage();
        error = message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    public void showFailure(String message) { error = message; }

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
