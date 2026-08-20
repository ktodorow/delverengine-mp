package com.interrupt.dungeoneer.overlays;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;

public class PartyChatOverlayTest {
    @Test
    public void chatInputUsesAvailableGameButtonStyleInsteadOfMissingTextFieldStyle() {
        Skin skin = new Skin();
        TextButtonStyle buttons = new TextButtonStyle();
        buttons.fontColor = Color.WHITE;
        skin.add("default", buttons);

        TextFieldStyle style = PartyChatOverlay.createMessageStyle(skin);

        assertNotNull(style);
        assertSame(buttons.fontColor, style.fontColor);
        assertSame(buttons.fontColor, style.focusedFontColor);
        assertSame(buttons.fontColor, style.messageFontColor);
    }

    @Test
    public void chatInputDoesNotShowCursor() {
        Skin skin = new Skin();
        TextButtonStyle buttons = new TextButtonStyle();
        skin.add("default", buttons);

        TextFieldStyle style = PartyChatOverlay.createMessageStyle(skin);

        assertNull("Chat input should not show a blinking caret.", style.cursor);
    }
}
