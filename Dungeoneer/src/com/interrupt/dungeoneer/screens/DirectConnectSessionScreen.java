package com.interrupt.dungeoneer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Align;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectStatus;

/** Minimal shared session screen used before both Participants enter the test floor. */
public final class DirectConnectSessionScreen implements Screen {
    private final GameApplication application;
    private final DirectConnectPeer peer;
    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont font = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();
    private boolean floorEntryRequested = false;
    private boolean disposed = false;

    public DirectConnectSessionScreen(GameApplication application, DirectConnectPeer peer) {
        if(application == null) throw new IllegalArgumentException("Game application cannot be null.");
        if(peer == null) throw new IllegalArgumentException("Direct Connect peer cannot be null.");
        this.application = application;
        this.peer = peer;
    }

    @Override
    public void show() { }

    @Override
    public void render(float delta) {
        DirectConnectStatus status = peer.getStatus();
        if(status.getPhase() == DirectConnectPhase.READY && !floorEntryRequested) {
            floorEntryRequested = true;
            Gdx.app.postRunnable(new Runnable() {
                @Override
                public void run() {
                    application.enterDirectConnectTestFloor();
                }
            });
        }

        Gdx.gl.glClearColor(0.035f, 0.045f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        camera.update();
        batch.setProjectionMatrix(camera.combined);

        float width = camera.viewportWidth;
        float y = camera.viewportHeight * 0.68f;
        batch.begin();
        font.getData().setScale(1.35f);
        font.draw(batch, "Delver Multiplayer - Direct Connect", textLeft(width, 0.9f), y,
                width * 0.9f, Align.center, false);
        font.getData().setScale(1f);
        y -= 52f;
        font.draw(batch, peer.getRole() + "  |  " + peer.getEndpoint(),
                textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
        y -= 42f;
        font.draw(batch, status.getPhase().name(), textLeft(width, 0.9f), y,
                width * 0.9f, Align.center, false);
        y -= 34f;
        font.draw(batch, status.getMessage(), textLeft(width, 0.8f), y,
                width * 0.8f, Align.center, true);
        if(status.getSessionId() != null) {
            y -= 58f;
            font.draw(batch, "Private Session " + status.getSessionId(),
                    textLeft(width, 0.9f), y, width * 0.9f, Align.center, false);
        }
        batch.end();
    }

    static float textLeft(float viewportWidth, float textWidthFraction) {
        return viewportWidth * (1f - textWidthFraction) * 0.5f;
    }

    @Override
    public void resize(int width, int height) {
        camera.setToOrtho(false, Math.max(1, width), Math.max(1, height));
    }

    @Override
    public void pause() { }

    @Override
    public void resume() { }

    @Override
    public void hide() { }

    @Override
    public void dispose() {
        if(disposed) return;
        disposed = true;
        batch.dispose();
        font.dispose();
    }
}
