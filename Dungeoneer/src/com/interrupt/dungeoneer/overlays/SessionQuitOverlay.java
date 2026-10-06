package com.interrupt.dungeoneer.overlays;

import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;

/** Personal confirmation menu; shared world continues until Host commits shutdown. */
public final class SessionQuitOverlay extends WindowOverlay {
    @Override public Table makeContent() {
        boolean host = GameApplication.instance.getDirectConnectPeer() instanceof DirectConnectHost;
        Table content = new Table();
        final Label message = new Label(host ? "Save Campaign and end session for everyone?"
                : "Leave session? Your Campaign Slot stays with Host.", skin);
        message.setWrap(true);
        content.add(message).width(300f).padBottom(8f);
        content.row();
        TextButton confirm = new TextButton(host ? "Save and Quit" : "Leave Session", skin);
        confirm.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                try { GameApplication.instance.leaveDirectConnectSession(); }
                catch(RuntimeException failure) { message.setText("Save failed; session continues. " + failure.getMessage()); }
            }
        });
        content.add(confirm).fillX(); content.row();
        TextButton cancel = new TextButton("Cancel", skin);
        cancel.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { OverlayManager.instance.remove(SessionQuitOverlay.this); }
        });
        content.add(cancel).fillX();
        buttonOrder.clear(); buttonOrder.add(confirm); buttonOrder.add(cancel);
        return content;
    }
}
