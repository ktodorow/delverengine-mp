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
import com.interrupt.dungeoneer.multiplayer.launcher.DirectConnectSessionFlow.ConnectSetup;
import com.interrupt.dungeoneer.multiplayer.launcher.DirectConnectSessionFlow.SessionReturn;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;

/** Native Connect view consumes the same real application flow used by session tests. */
public final class ConnectSetupScreen extends BaseScreen {
    private final GameApplication application;
    private final TextField address, port, nickname;
    private final ButtonGroup<TextButton> avatars = new ButtonGroup<>();
    private final Image selectedPortrait = new Image();
    private final Label message;
    private final TextButton connect, back, edit;
    private Window failurePanel;
    private boolean pending, navigationPending, entryRequested, disposed;
    private String localFailure;

    public ConnectSetupScreen(GameApplication application, ConnectSetup defaults) {
        this.application = application;
        screenName = "ConnectSetupScreen";
        splashLevel = splashScreenInfo.backgroundLevel;
        viewport = new FitViewport(520, 400);
        ui = new Stage(viewport);
        Table root = new Table();
        root.setFillParent(true);
        Table panel = new Table(skin);
        panel.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
        panel.pad(12);
        panel.add(new Label("Connect to Co-op Campaign", skin)).colspan(2).padBottom(8);
        panel.row();
        address = field(defaults.getAddress(), 253);
        address.setMessageText("Host address");
        row(panel, "Host address", address);
        port = field(Integer.toString(defaults.getPort()), 5);
        port.setTextFieldFilter((textField, character) -> character >= '0' && character <= '9');
        row(panel, "Port", port);
        nickname = field(defaults.getPresentation().getNickname(), 64);
        row(panel, "Nickname", nickname);
        Table choices = new Table();
        int number = 1;
        for(String id : AvatarCatalog.ownedV108Humanoids().getAvatarIds()) {
            DrawableSprite sprite = HostSetupScreen.resolvedPortrait(id);
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
            button.setChecked(id.equals(defaults.getPresentation().getAvatarId()));
            button.addListener(new ClickListener() {
                @Override public void clicked(InputEvent event, float x, float y) {
                    if(!pending && !button.isDisabled()) selectPortrait(sprite);
                }
            });
            choices.add(button).width(51).height(50).padRight(3);
            if(id.equals(defaults.getPresentation().getAvatarId())) selectPortrait(sprite);
        }
        row(panel, "Avatar (F1-F4)", choices);
        panel.getCell(choices).height(50);
        selectedPortrait.setScaling(Scaling.fit);
        panel.add(new Label("Selected Avatar", skin)).left();
        panel.add(selectedPortrait).size(56).padBottom(4);
        panel.row();
        Label help = new Label("Use Host LAN or reachable internet address. TCP and UDP use same port.\n"
                + "Choices are provisional; saved characters keep campaign Nickname and Avatar.", skin);
        help.setFontScale(0.65f);
        help.setWrap(true);
        panel.add(help).colspan(2).width(390).height(36).padTop(3);
        panel.row();
        message = new Label("Enter Host address and presentation.", skin);
        message.setFontScale(0.7f);
        message.setWrap(true);
        panel.add(message).colspan(2).width(390).height(48).padTop(3);
        panel.row();
        Table actions = new Table();
        back = action(actions, "Back", application::cancelMultiplayerConnect);
        edit = action(actions, "Edit", () -> ui.setKeyboardFocus(address));
        edit.setVisible(false);
        connect = action(actions, "Connect", this::connect);
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

    private void selectPortrait(DrawableSprite sprite) {
        selectedPortrait.setDrawable(new TextureRegionDrawable(sprite.atlas.getSprite(sprite.tex)));
        selectedPortrait.setColor(sprite.color);
    }

    private TextButton action(Table parent, String label, Runnable action) {
        TextButton button = new TextButton(label, skin);
        button.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                if(!button.isDisabled()) navigate(action);
            }
        });
        parent.add(button).width(120).height(24).padRight(6);
        return button;
    }

    private void navigate(Runnable action) {
        if(navigationPending || disposed) return;
        navigationPending = true;
        Gdx.app.postRunnable(() -> {
            if(disposed || application.getScreen() != this) return;
            try { action.run(); }
            catch(RuntimeException failure) { showFailure(failure.getMessage()); }
            finally { navigationPending = false; }
        });
    }

    private void connect() {
        if(pending) return;
        int selectedPort;
        try { selectedPort = Integer.parseInt(port.getText()); }
        catch(NumberFormatException invalid) { throw new IllegalArgumentException("Port must be 1-65535."); }
        ConnectSetup setup = new ConnectSetup(address.getText(), selectedPort, nickname.getText(),
                (String)avatars.getChecked().getUserObject());
        localFailure = null;
        connect.setText("Connect");
        edit.setVisible(false);
        entryRequested = false;
        application.openMultiplayerConnection(setup);
        setPending(true);
    }

    public void showFailure(String reason) {
        localFailure = reason == null ? "Connection failed. Edit fields and retry." : reason;
        message.setText(localFailure.replace("[", "[["));
        message.setColor(Color.ORANGE);
        connect.setText("Retry");
        edit.setVisible(true);
    }

    /** Unexpected session loss is acknowledged before the retained Connect form is edited. */
    public void showSessionReturn(SessionReturn result) {
        localFailure = result.getMessage();
        message.setText(localFailure.replace("[", "[["));
        message.setColor(result.isFailure() ? Color.ORANGE : Color.WHITE);
        if(!result.isFailure()) return;
        showFailure(localFailure);
        failurePanel = new Window("", new Window.WindowStyle(skin.get(TextButton.TextButtonStyle.class).font,
                Color.WHITE, new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8))));
        failurePanel.setModal(true);
        failurePanel.setMovable(false);
        failurePanel.pad(12);
        Label reason = new Label(localFailure.replace("[", "[["), skin);
        reason.setFontScale(0.8f);
        reason.setWrap(true);
        failurePanel.add(reason).width(390).height(90).padBottom(8); failurePanel.row();
        Table actions = new Table();
        action(actions, "Retry", () -> { closeFailurePanel(); connect(); });
        TextButton editReason = action(actions, "Edit / Continue", this::closeFailurePanel);
        action(actions, "Back", application::cancelMultiplayerConnect);
        failurePanel.add(actions).height(24);
        failurePanel.pack();
        failurePanel.setPosition((viewport.getWorldWidth() - failurePanel.getWidth()) / 2f,
                (viewport.getWorldHeight() - failurePanel.getHeight()) / 2f);
        ui.addActor(failurePanel);
        ui.setKeyboardFocus(editReason);
    }

    private void closeFailurePanel() {
        if(failurePanel == null) return;
        failurePanel.remove();
        failurePanel = null;
        ui.setKeyboardFocus(address);
    }

    private void setPending(boolean value) {
        pending = value;
        address.setDisabled(value);
        port.setDisabled(value);
        nickname.setDisabled(value);
        connect.setDisabled(value);
        edit.setDisabled(value);
        for(TextButton button : avatars.getButtons()) button.setDisabled(value);
        back.setText(value ? "Cancel" : "Back");
    }

    @Override public void show() {
        super.show();
        Gdx.input.setCursorCatched(false);
        Gdx.input.setInputProcessor(ui);
        ui.setKeyboardFocus(address);
    }

    @Override protected void tick(float delta) {
        super.tick(delta);
        ui.act(delta);
        if(failurePanel != null) {
            if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.ENTER))
                navigate(this::closeFailurePanel);
            return;
        }
        DirectConnectPeer peer = application.getDirectConnectPeer();
        if(peer != null) {
            DirectConnectPhase phase = peer.getStatus().getPhase();
            boolean stopped = phase == DirectConnectPhase.FAILED || phase == DirectConnectPhase.REJECTED
                    || phase == DirectConnectPhase.DISCONNECTED || phase == DirectConnectPhase.CLOSED;
            setPending(!stopped);
            connect.setText(stopped ? "Retry" : "Connect");
            edit.setVisible(stopped);
            if(localFailure == null) {
                message.setText(application.getMultiplayerConnectionProgress().replace("[", "[["));
                message.setColor(stopped ? Color.ORANGE : Color.WHITE);
            }
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) navigate(application::cancelMultiplayerConnect);
        else if(!pending && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) navigate(this::connect);
        if(!pending) {
            for(int i = 0; i < avatars.getButtons().size; i++) {
                if(Gdx.input.isKeyJustPressed(Input.Keys.F1 + i)) {
                    TextButton button = avatars.getButtons().get(i);
                    button.setChecked(true);
                    selectPortrait(HostSetupScreen.resolvedPortrait((String)button.getUserObject()));
                }
            }
        }
        if(peer != null && !navigationPending && !entryRequested
                && peer.getStatus().getPhase() == DirectConnectPhase.LOBBY && peer.getLobbySnapshot() != null) {
            entryRequested = true;
            Gdx.app.postRunnable(() -> {
                if(disposed || application.getScreen() != this || application.getDirectConnectPeer() != peer) return;
                if(navigationPending) { entryRequested = false; return; }
                if(!application.showDirectConnectLobby(peer)) entryRequested = false;
            });
        }
        if(peer != null && !navigationPending && !entryRequested
                && application.canEnterDirectConnectFloor(peer)) {
            entryRequested = true;
            Gdx.app.postRunnable(() -> {
                if(disposed || application.getScreen() != this || application.getDirectConnectPeer() != peer) return;
                if(navigationPending) { entryRequested = false; return; }
                try { if(!application.enterDirectConnectFloor(peer)) entryRequested = false; }
                catch(RuntimeException failure) {
                    peer.close();
                    application.returnToDirectConnectSession();
                    application.showDirectConnectFailure(failure.getMessage());
                }
            });
        }
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
