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
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectStatus;
import com.interrupt.dungeoneer.multiplayer.network.PendingSlotClaim;

import java.util.List;

/** Minimal shared session screen used before Participants enter the shared floor. */
public final class DirectConnectSessionScreen implements Screen {
    private final GameApplication application;
    private final DirectConnectPeer peer;
    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont font = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();
    private boolean floorEntryRequested = false;
    private boolean disposed = false;
    private String entryError;
    private boolean navigationPending;
    private boolean confirmClose;

    public DirectConnectSessionScreen(GameApplication application, DirectConnectPeer peer) {
        if(application == null) throw new IllegalArgumentException("Game application cannot be null.");
        this.application = application;
        this.peer = peer;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(null);
        Gdx.input.setCursorCatched(false);
    }

    @Override
    public void render(float delta) {
        if(disposed) return;
        handleNavigation();
        handleHostControls();
        DirectConnectStatus status = peer == null ? null : peer.getStatus();
        DirectConnectPhase phase = status == null ? DirectConnectPhase.FAILED : status.getPhase();
        boolean spectator = peer != null && peer.getPartyStatus() != null
                && peer.getPartyStatus().getMember(peer.getLocalCampaignSlot()) != null
                && peer.getPartyStatus().getMember(peer.getLocalCampaignSlot()).getState()
                        == com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState.SPECTATING;
        if(peer != null && !navigationPending && !confirmClose && (isFloorEntryReady(phase, peer.getLocalMovementEntityId(),
                peer.getMovementSnapshots()) || spectator && (phase == DirectConnectPhase.READY
                        || phase == DirectConnectPhase.SYNCHRONIZING)
                        && !peer.getMovementSnapshots().isEmpty()) && !floorEntryRequested) {
            floorEntryRequested = true;
            Gdx.app.postRunnable(new Runnable() {
                @Override
                public void run() {
                    if(disposed || application.getScreen() != DirectConnectSessionScreen.this
                            || application.getDirectConnectPeer() != peer) return;
                    try { application.enterDirectConnectFloor(peer); }
                    catch(RuntimeException failure) {
                        entryError = failure.getMessage();
                        try {
                            if(peer instanceof DirectConnectHost) ((DirectConnectHost)peer).abortCampaignRecovery(entryError);
                            else peer.close();
                        }
                        catch(RuntimeException ignored) { }
                        application.returnToDirectConnectSession();
                        application.showDirectConnectFailure(entryError);
                    }
                }
            });
        }

        Gdx.gl.glClearColor(0.035f, 0.045f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        camera.update();
        batch.setProjectionMatrix(camera.combined);

        float width = camera.viewportWidth;
        float y = camera.viewportHeight * 0.82f;
        batch.begin();
        font.getData().setScale(1.35f);
        font.draw(batch, "Delver Multiplayer - Direct Connect", textLeft(width, 0.9f), y,
                width * 0.9f, Align.center, false);
        font.getData().setScale(1f);
        y -= 42f;
        font.draw(batch, peer == null ? "Connection attempt" : peer.getRole() + "  |  " + peer.getEndpoint(),
                textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
        y -= 34f;
        font.draw(batch, phase.name(), textLeft(width, 0.9f), y,
                width * 0.9f, Align.center, false);
        y -= 30f;
        font.draw(batch, entryError != null ? entryError : status == null ? "Session could not open." : status.getMessage(), textLeft(width, 0.8f), y,
                width * 0.8f, Align.center, true);
        if(status != null && status.getSessionId() != null) {
            y -= 40f;
            font.draw(batch, "Private Session " + status.getSessionId(),
                    textLeft(width, 0.9f), y, width * 0.9f, Align.center, false);
        }
        if(peer instanceof DirectConnectHost) {
            DirectConnectHost host = (DirectConnectHost)peer;
            y -= 32f;
            font.draw(batch, "Campaign " + host.getRoster().getCampaignName() + "  |  Capacity "
                            + host.getRoster().getCapacity() + "  |  Connected "
                            + host.getConnectedParticipantCount()
                            + "  |  Starting Lives " + host.getStartingLives()
                            + (host.isStartingLivesLocked() ? " (locked)" : " [1-5]"),
                    textLeft(width, 0.9f), y, width * 0.9f, Align.center, false);
            List<PendingSlotClaim> pending = host.getPendingClaims();
            y -= 30f;
            if(!pending.isEmpty()) {
                PendingSlotClaim claim = pending.get(0);
                boolean relink = claim.getRequestedSlot() > 1
                        && host.getRoster().getSlot(claim.getRequestedSlot()) != null;
                font.draw(batch, "Pending: " + claim.getNickname() + " / "
                                + claim.getAvatarId() + " / Identity "
                                + claim.getLauncherIdentity().getFingerprint()
                                + (relink ? "   [L] Relink trusted slot   [R] Reject"
                                        : "   [A] Approve   [R] Reject"),
                        textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
            }
            else if(host.canStartSession()) {
                font.draw(batch, "[ENTER] Start with approved TCP/UDP-ready Participants",
                        textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
            }
        }
        if(confirmClose) {
            y -= 36f;
            font.draw(batch, "Close Host lobby and disconnect Participants? [Y] Close   [N] Keep open",
                    textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
        }
        else if(phase == DirectConnectPhase.CLOSED
                || phase == DirectConnectPhase.DISCONNECTED
                || phase == DirectConnectPhase.FAILED
                || phase == DirectConnectPhase.REJECTED) {
            y -= 36f;
            font.draw(batch, "Session stopped. [T] Retry   [ESC] Back / Cancel",
                    textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
        }
        else {
            y -= 36f;
            font.draw(batch, peer instanceof DirectConnectHost ? "[ESC] Close lobby" : "[ESC] Cancel / Leave",
                    textLeft(width, 0.9f), y, width * 0.9f, Align.center, true);
        }
        batch.end();
    }

    public void showFailure(String error) { entryError = error; }

    private void handleNavigation() {
        if(navigationPending) return;
        if(confirmClose) {
            if(Gdx.input.isKeyJustPressed(Input.Keys.Y)) navigate(application::leaveDirectConnectSession);
            else if(Gdx.input.isKeyJustPressed(Input.Keys.N) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) confirmClose = false;
            return;
        }
        DirectConnectPhase phase = peer == null ? DirectConnectPhase.FAILED : peer.getStatus().getPhase();
        boolean stopped = phase == DirectConnectPhase.CLOSED || phase == DirectConnectPhase.FAILED
                || phase == DirectConnectPhase.REJECTED || phase == DirectConnectPhase.DISCONNECTED;
        if(stopped && Gdx.input.isKeyJustPressed(Input.Keys.T)) navigate(application::retryDirectConnectSession);
        else if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if(!stopped && peer instanceof DirectConnectHost) confirmClose = true;
            else navigate(application::leaveDirectConnectSession);
        }
    }

    private void navigate(final Runnable action) {
        navigationPending = true;
        Gdx.app.postRunnable(() -> {
            if(disposed || application.getScreen() != this) return;
            try { action.run(); }
            catch(RuntimeException failure) { application.showDirectConnectFailure(failure.getMessage()); }
            finally { navigationPending = false; }
        });
    }

    private void handleHostControls() {
        if(navigationPending || confirmClose || !(peer instanceof DirectConnectHost)) return;
        DirectConnectPhase phase = peer.getStatus().getPhase();
        if(phase == DirectConnectPhase.CLOSED || phase == DirectConnectPhase.FAILED) return;
        DirectConnectHost host = (DirectConnectHost)peer;
        List<PendingSlotClaim> pending = host.getPendingClaims();
        if(!pending.isEmpty()) {
            PendingSlotClaim claim = pending.get(0);
            String identity = claim.getLauncherIdentity().getValue();
            if(Gdx.input.isKeyJustPressed(Input.Keys.L)
                    && claim.getRequestedSlot() > 1
                    && host.getRoster().getSlot(claim.getRequestedSlot()) != null) {
                host.relinkTrustedParticipant(identity);
            }
            else if(Gdx.input.isKeyJustPressed(Input.Keys.A)) host.approve(identity);
            else if(Gdx.input.isKeyJustPressed(Input.Keys.R)) host.decline(identity);
        }
        // Campaign-wide risk is the Host's choice until play begins; Host locks it at start.
        for(int startingLives = 1; startingLives <= 5; startingLives++) {
            if(!host.isStartingLivesLocked()
                    && (Gdx.input.isKeyJustPressed(Input.Keys.NUM_0 + startingLives)
                    || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_0 + startingLives))) {
                host.setStartingLives(startingLives);
            }
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && host.canStartSession()) {
            host.startSession();
        }
    }

    static float textLeft(float viewportWidth, float textWidthFraction) {
        return viewportWidth * (1f - textWidthFraction) * 0.5f;
    }

    static float lowestHostTextBaseline(float viewportHeight) {
        return viewportHeight * 0.82f - 42f - 34f - 30f - 40f - 32f - 30f;
    }

    static boolean isFloorEntryReady(DirectConnectPhase phase,
            NetworkEntityId localEntityId, List<MovementSnapshot> snapshots) {
        if(phase != DirectConnectPhase.READY || localEntityId == null
                || snapshots == null || snapshots.isEmpty()) return false;
        return snapshots.get(snapshots.size() - 1).getEntity(localEntityId) != null;
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
