package com.interrupt.dungeoneer.overlays;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;

/** Non-pausing text entry for reliable Party chat during an active Private Session. */
public final class PartyChatOverlay extends WindowOverlay {
    private final DirectConnectPeer peer;
    private TextField message;

    public PartyChatOverlay(DirectConnectPeer peer) {
        if(peer == null) throw new IllegalArgumentException("Party chat peer cannot be null.");
        this.peer = peer;
        pausesGame = false;
        dimScreen = false;
        animateBackground = false;
    }

    @Override
    public void onShow() {
        super.onShow();
        ui.setKeyboardFocus(message);
        ui.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if(keycode == Keys.ENTER) sendAndClose();
                else if(keycode == Keys.ESCAPE || keycode == Keys.BACK) {
                    OverlayManager.instance.remove(PartyChatOverlay.this);
                }
                return false;
            }
        });
    }

    @Override
    public Table makeContent() {
        message = createMessageField(skin);
        message.setMessageText("Party message");
        message.setMaxLength(240);

        TextButton send = new TextButton(" Send ", skin);
        send.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                sendAndClose();
            }
        });

        Table content = new Table();
        content.add(new Label("PARTY CHAT", skin)).padBottom(4f).row();
        content.add(message).width(340f).padRight(4f);
        content.add(send);
        return content;
    }

    static TextField createMessageField(com.badlogic.gdx.scenes.scene2d.ui.Skin skin) {
        return new TextField("", createMessageStyle(skin));
    }

    static TextField.TextFieldStyle createMessageStyle(
            com.badlogic.gdx.scenes.scene2d.ui.Skin skin) {
        TextButtonStyle buttons = skin.get(TextButtonStyle.class);
        TextField.TextFieldStyle input = new TextField.TextFieldStyle();
        input.font = buttons.font;
        input.fontColor = buttons.fontColor;
        input.focusedFontColor = buttons.fontColor;
        input.disabledFontColor = buttons.fontColor;
        input.messageFont = buttons.font;
        input.messageFontColor = buttons.fontColor;
        input.background = buttons.up;
        input.focusedBackground = buttons.down == null ? buttons.up : buttons.down;
        input.disabledBackground = buttons.up;
        input.selection = buttons.down == null ? buttons.up : buttons.down;
        input.cursor = null;
        return input;
    }

    @Override
    public void tick(float delta) {
        super.tick(delta);
    }

    private void sendAndClose() {
        try {
            peer.submitPartyChat(message.getText());
            OverlayManager.instance.remove(this);
        }
        catch(IllegalArgumentException ignored) {
            // Keep entry focused until valid bounded text is supplied or cancelled.
        }
    }
}
