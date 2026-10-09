package com.interrupt.dungeoneer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.gfx.drawables.DrawableSprite;
import com.interrupt.dungeoneer.multiplayer.launcher.DirectConnectSessionFlow.HostSetup;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol;

/** Native form is a view over application's real session flow, never a second session model. */
public final class HostSetupScreen extends BaseScreen {
    private final GameApplication application;
    private final HostSetup savedSetup;
    private final TextField campaignName, nickname, port;
    private final ButtonGroup<TextButton> capacities = new ButtonGroup<>();
    private final ButtonGroup<TextButton> lives = new ButtonGroup<>();
    private final ButtonGroup<TextButton> avatars = new ButtonGroup<>();
    private final Image selectedPortrait = new Image();
    private final Label message;
    private boolean opening, disposed;

    public HostSetupScreen(GameApplication application, int capacity, SlotPresentation defaults) {
        this(application, capacity, defaults, null);
    }

    public HostSetupScreen(GameApplication application, HostSetup savedSetup) {
        this(application, savedSetup.getCapacity(), savedSetup.getPresentation(), savedSetup);
    }

    private HostSetupScreen(GameApplication application, int capacity, SlotPresentation defaults, HostSetup savedSetup) {
        this.application = application;
        this.savedSetup = savedSetup;
        screenName = "HostSetupScreen";
        splashLevel = splashScreenInfo.backgroundLevel;
        viewport = new FitViewport(520, 440);
        ui = new Stage(viewport);
        Table root = new Table();
        root.setFillParent(true);
        Table panel = new Table(skin);
        panel.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
        panel.pad(12);
        panel.add(new Label(savedSetup == null ? "New Co-op Campaign" : "Resume Co-op Campaign", skin)).colspan(2).padBottom(8);
        panel.row();
        campaignName = field(savedSetup == null ? "" : savedSetup.getCampaignName(), 128);
        campaignName.setDisabled(savedSetup != null);
        campaignName.setMessageText("Campaign Name");
        row(panel, "Campaign Name", campaignName);
        row(panel, "Capacity", numbers(capacities, 2, 4, capacity));
        row(panel, "Starting Lives", numbers(lives, 1, 5, savedSetup == null ? 3 : savedSetup.getStartingLives()));
        if(savedSetup != null) {
            for(TextButton button : capacities.getButtons()) button.setDisabled(true);
            for(TextButton button : lives.getButtons()) button.setDisabled(true);
        }
        nickname = field(defaults.getNickname(), 64);
        row(panel, "Nickname", nickname);

        Table choices = new Table();
        int number = 1;
        for(String id : AvatarCatalog.ownedV108Humanoids().getAvatarIds()) {
            final DrawableSprite sprite = resolvedPortrait(id);
            TextButton button = new TextButton("Avatar " + number++, com.interrupt.dungeoneer.ui.UiSkin.createChoiceButtonStyle(skin));
            button.setUserObject(id);
            Label label = button.getLabel();
            label.setFontScale(0.65f);
            button.clearChildren();
            Image portrait = new Image(new TextureRegionDrawable(sprite.atlas.getSprite(sprite.tex)), Scaling.fit);
            portrait.setColor(sprite.color);
            button.add(portrait).size(28).pad(2);
            button.row();
            button.add(label).padBottom(2);
            avatars.add(button);
            button.setChecked(id.equals(defaults.getAvatarId()));
            button.addListener(new ClickListener() {
                @Override public void clicked(InputEvent event, float x, float y) { selectPortrait(sprite); }
            });
            choices.add(button).width(51).height(50).padRight(3);
            if(id.equals(defaults.getAvatarId())) selectPortrait(sprite);
        }
        row(panel, "Avatar", choices);
        panel.getCell(choices).height(50);
        selectedPortrait.setScaling(Scaling.fit);
        panel.add(new Label("Selected Avatar", skin)).left();
        panel.add(selectedPortrait).size(56).padBottom(4);
        panel.row();
        port = field(Integer.toString(savedSetup == null ? DirectConnectProtocol.DEFAULT_PORT : savedSetup.getPort()), 5);
        port.setTextFieldFilter((textField, character) -> character >= '0' && character <= '9');
        row(panel, "Port", port);
        message = new Label(savedSetup == null ? "Open Lobby waits for Participants. Host starts play from Lobby."
                : "Saved name, Capacity and Lives are locked. Host can resume alone.", skin);
        message.setFontScale(0.7f);
        message.setWrap(true);
        panel.add(message).colspan(2).width(390).height(32).padTop(3);
        panel.row();
        Table actions = new Table();
        action(actions, "Back", application::returnFromCampaignSetup);
        action(actions, "Open Lobby", this::openLobby);
        panel.add(actions).colspan(2).padTop(4);
        root.add(panel);
        ui.addActor(root);
    }

    private TextField field(String value, int maxLength) {
        TextField.TextFieldStyle style = com.interrupt.dungeoneer.ui.UiSkin.createTextFieldStyle(skin);
        style.cursor = new TextureRegionDrawable(new TextureRegion(skin.getRegion("knob"), 8, 8, 1, 1));
        TextField field = new TextField(value, style);
        field.setMaxLength(maxLength);
        field.setOnlyFontChars(false);
        return field;
    }

    private void row(Table panel, String label, com.badlogic.gdx.scenes.scene2d.Actor control) {
        Label title = new Label(label, skin);
        title.setFontScale(0.8f);
        panel.add(title).width(125).left().padBottom(4);
        panel.add(control).width(250).height(24).padBottom(4);
        panel.row();
    }

    private Table numbers(ButtonGroup<TextButton> group, int first, int last, int selected) {
        Table row = new Table();
        for(int value = first; value <= last; value++) {
            TextButton button = new TextButton(Integer.toString(value), com.interrupt.dungeoneer.ui.UiSkin.createChoiceButtonStyle(skin));
            button.setUserObject(value);
            group.add(button);
            button.setChecked(value == selected);
            row.add(button).width(40).height(24).padRight(4);
        }
        return row;
    }

    /** Same entity, DrawableSprite update, atlas lookup/fallback and tint used during gameplay. */
    private static DrawableSprite resolvedPortrait(String avatarId) {
        RemoteAvatar actor = new RemoteAvatar(new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                new ParticipantId("campaign-slot-1"), 1, "Preview", avatarId));
        actor.updateDrawable();
        DrawableSprite sprite = (DrawableSprite)actor.drawable;
        if(sprite == null || sprite.atlas == null) throw new IllegalStateException("Owned Avatar portrait could not load.");
        return sprite;
    }

    private void selectPortrait(DrawableSprite sprite) {
        selectedPortrait.setDrawable(new TextureRegionDrawable(sprite.atlas.getSprite(sprite.tex)));
        selectedPortrait.setColor(sprite.color);
    }

    private void action(Table parent, String label, Runnable action) {
        TextButton button = new TextButton(label, skin);
        button.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                Gdx.app.postRunnable(() -> {
                    if(!disposed && !opening && application.getScreen() == HostSetupScreen.this) action.run();
                });
            }
        });
        parent.add(button).width(145).height(24).padRight(6);
    }

    private void openLobby() {
        opening = true;
        try {
            int listenerPort;
            try { listenerPort = Integer.parseInt(port.getText()); }
            catch(NumberFormatException invalid) { throw new IllegalArgumentException("Port must be 1-65535."); }
            String avatar = (String)avatars.getChecked().getUserObject();
            if(savedSetup == null) application.openNewCampaignLobby(new HostSetup(campaignName.getText(),
                    (Integer)capacities.getChecked().getUserObject(), (Integer)lives.getChecked().getUserObject(),
                    nickname.getText(), avatar, listenerPort));
            else application.openSavedCampaignLobby(savedSetup.withHostOptions(nickname.getText(), avatar, listenerPort));
        }
        catch(RuntimeException failure) {
            opening = false;
            String reason = failure.getMessage();
            message.setText(reason == null ? "Lobby could not open. Check port and try again." : reason.replace("[", "[["));
            message.setColor(Color.ORANGE);
        }
    }

    @Override public void show() {
        super.show();
        Gdx.input.setCursorCatched(false);
        Gdx.input.setInputProcessor(ui);
        ui.setKeyboardFocus(savedSetup == null ? campaignName : nickname);
    }

    @Override protected void tick(float delta) {
        super.tick(delta);
        ui.act(delta);
        if(!opening && Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE))
            Gdx.app.postRunnable(() -> { if(!disposed && application.getScreen() == this) application.returnFromCampaignSetup(); });
        else if(!opening && Gdx.input.isKeyJustPressed(Input.Keys.ENTER))
            Gdx.app.postRunnable(() -> { if(!disposed && application.getScreen() == this) openLobby(); });
    }

    @Override protected void draw(float delta) {
        Gdx.gl.glViewport(0, 0, curWidth, curHeight);
        super.draw(delta);
        viewport.apply();
        ui.draw();
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
