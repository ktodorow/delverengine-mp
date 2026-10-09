package com.interrupt.dungeoneer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.NinePatch;
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
import com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.*;
import java.util.List;

/** Native view of the same authoritative public lobby used by real-session tests. */
public final class DirectConnectSessionScreen extends BaseScreen {
    private final GameApplication application;
    private final DirectConnectPeer peer;
    private final Table cards = new Table();
    private final Label title, settings, counts, progress, recovery;
    private final TextButton ready, start, leave, retry, keepOpen, relink, reject;
    private LobbySnapshot displayed;
    private boolean floorEntryRequested, navigationPending, confirmClose, disposed;
    private String entryError;

    public DirectConnectSessionScreen(GameApplication application, DirectConnectPeer peer) {
        if(application == null) throw new IllegalArgumentException("Game application cannot be null.");
        this.application = application;
        this.peer = peer;
        screenName = "DirectConnectSessionScreen";
        splashLevel = splashScreenInfo.backgroundLevel;
        viewport = new FitViewport(540, 600);
        ui = new Stage(viewport);
        Table root = new Table();
        root.setFillParent(true);
        Table panel = new Table(skin);
        panel.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
        panel.pad(12);
        title = label("Co-op Campaign Lobby", 1f);
        panel.add(title).width(470).height(24); panel.row();
        settings = label("Receiving campaign settings...", 0.8f);
        panel.add(settings).width(470).height(22); panel.row();
        counts = label("", 0.75f);
        panel.add(counts).width(470).height(20).padBottom(5); panel.row();
        panel.add(cards).width(470); panel.row();
        progress = label("Connecting...", 0.75f);
        panel.add(progress).width(470).height(44).padTop(5); panel.row();
        recovery = label("Compatible friends enter unused capacity automatically.", 0.65f);
        panel.add(recovery).width(470).height(32); panel.row();
        Table recoveryActions = new Table();
        relink = action(recoveryActions, "Relink trusted (L)", () -> recoverClaim(true));
        reject = action(recoveryActions, "Reject recovery (R)", () -> recoverClaim(false));
        panel.add(recoveryActions).height(24); panel.row();
        Table actions = new Table();
        ready = action(actions, "Ready (Space)", this::toggleReady);
        start = action(actions, "Start (Enter)", () -> application.startMultiplayerCampaign(peer));
        panel.add(actions).height(24).padTop(5); panel.row();
        Table navigation = new Table();
        leave = action(navigation, peer instanceof DirectConnectHost ? "Close lobby (Esc)" : "Leave (Esc)", this::leave);
        keepOpen = action(navigation, "Keep open", () -> confirmClose = false);
        retry = action(navigation, "Retry (T)", application::retryDirectConnectSession);
        panel.add(navigation).height(24).padTop(5);
        root.add(panel);
        ui.addActor(root);
        updateLobby();
    }

    private Label label(String text, float scale) {
        Label label = new Label(text, skin);
        label.setFontScale(scale);
        label.setWrap(true);
        return label;
    }

    private TextButton action(Table table, String text, Runnable action) {
        TextButton button = new TextButton(text, skin);
        button.getLabel().setFontScale(0.75f);
        button.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                if(!button.isDisabled()) navigate(action);
            }
        });
        table.add(button).width(113).height(24).padRight(4);
        return button;
    }

    private static String text(String value) { return value == null ? "" : value.replace("[", "[["); }

    private void updateLobby() {
        LobbySnapshot lobby = peer == null ? null : peer.getLobbySnapshot();
        if(lobby != null && lobby != displayed) {
            displayed = lobby;
            title.setText(text(lobby.getCampaignName()));
            settings.setText("Capacity " + lobby.getCapacity() + " / Starting Lives " + lobby.getStartingLives() + " (locked)");
            counts.setText("Connected " + lobby.getConnectedCount() + " / Claimed " + lobby.getClaimedCount()
                    + " / Reserved " + lobby.getReservedCount() + " / Empty " + lobby.getEmptyCount());
            cards.clearChildren();
            for(LobbySnapshot.Slot slot : lobby.getSlots()) {
                Table card = new Table(skin);
                card.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 8, 8, 8, 8)));
                card.pad(4);
                if(slot.isClaimed()) {
                    DrawableSprite sprite = HostSetupScreen.resolvedPortrait(slot.getPresentation().getAvatarId());
                    Image portrait = new Image(new TextureRegionDrawable(sprite.atlas.getSprite(sprite.tex)), Scaling.fit);
                    portrait.setColor(sprite.color);
                    card.add(portrait).size(44).padRight(9);
                }
                else card.add(label("?", 1f)).size(44).padRight(9);
                Table detail = new Table();
                String name = slot.isClaimed() ? text(slot.getPresentation().getNickname()) : "Empty capacity";
                if(slot.getNumber() == lobby.getHostSlot()) name += " / Host";
                if(slot.getNumber() == peer.getLocalCampaignSlot() || peer instanceof DirectConnectHost && slot.getNumber() == 1) name += " / You";
                detail.add(label(slot.getNumber() + ". " + name, 0.85f)).width(380).height(21).left(); detail.row();
                String state = !slot.isClaimed() ? "Available to compatible friend" : !slot.isConnected() ? "Reserved / Offline"
                        : !slot.isAuthenticated() ? "Connected / Authenticating UDP"
                        : !slot.isSynchronized() ? "Connected / Synchronizing lobby"
                        : slot.isPlayerReady() ? "Connected / Ready" : "Connected / Not ready";
                Label status = label(state, 0.7f);
                status.setColor(slot.isPlayerReady() ? Color.GREEN : slot.isConnected() ? Color.ORANGE : Color.GRAY);
                detail.add(status).width(380).height(18).left();
                card.add(detail).expandX().left();
                cards.add(card).width(470).height(54).padBottom(3); cards.row();
            }
        }
        DirectConnectPhase phase = peer == null ? DirectConnectPhase.FAILED : peer.getStatus().getPhase();
        boolean stopped = phase == DirectConnectPhase.CLOSED || phase == DirectConnectPhase.FAILED
                || phase == DirectConnectPhase.REJECTED || phase == DirectConnectPhase.DISCONNECTED && !(peer instanceof DirectConnectHost);
        progress.setText(text(confirmClose ? "Close Host lobby? Connected Participants will disconnect."
                : entryError != null ? entryError : peer == null ? "Connection could not open."
                : stopped ? peer.getStatus().getMessage() : peer instanceof DirectConnectHost ? "Lobby open / " + peer.getEndpoint()
                : application.getMultiplayerConnectionProgress()));
        progress.setColor(confirmClose || stopped || entryError != null ? Color.ORANGE : Color.WHITE);
        boolean host = peer instanceof DirectConnectHost;
        ready.setVisible(!confirmClose && !stopped);
        ready.setDisabled(peer == null || !peer.canSetPlayerReady());
        LobbySnapshot.Slot local = lobby == null ? null : lobby.getSlot(host ? 1 : peer.getLocalCampaignSlot());
        ready.setText(local != null && local.isPlayerReady() ? "Not ready (Space)" : "Ready (Space)");
        start.setVisible(host && !confirmClose && !stopped);
        start.setDisabled(!host || !((DirectConnectHost)peer).canStartSession());
        if(host) start.setText(((DirectConnectHost)peer).isResumedCampaign() ? "Resume (Enter)" : "Start (Enter)");
        retry.setVisible(stopped && !confirmClose);
        keepOpen.setVisible(confirmClose);
        leave.setText(confirmClose ? "Confirm close" : host ? "Close lobby (Esc)" : stopped ? "Edit / Back (Esc)" : "Leave (Esc)");
        List<PendingSlotClaim> pending = host ? ((DirectConnectHost)peer).getPendingClaims() : java.util.Collections.emptyList();
        relink.setVisible(!pending.isEmpty() && !confirmClose && !stopped);
        reject.setVisible(relink.isVisible());
        if(!pending.isEmpty()) {
            PendingSlotClaim claim = pending.get(0);
            recovery.setText(text("Recovery: " + claim.getNickname() + " / Slot " + claim.getRequestedSlot()
                    + " / Identity fingerprint " + claim.getLauncherIdentity().getFingerprint() + ". Verify trusted friend before relink."));
        }
        else recovery.setText("Every connected player must be Ready. Host then chooses Start.");
    }

    private void toggleReady() {
        LobbySnapshot lobby = peer.getLobbySnapshot();
        LobbySnapshot.Slot local = lobby == null ? null : lobby.getSlot(peer instanceof DirectConnectHost ? 1 : peer.getLocalCampaignSlot());
        if(local != null) application.setMultiplayerPlayerReady(peer, !local.isPlayerReady());
    }

    private void recoverClaim(boolean trusted) {
        List<PendingSlotClaim> pending = ((DirectConnectHost)peer).getPendingClaims();
        if(pending.isEmpty()) return;
        String identity = pending.get(0).getLauncherIdentity().getValue();
        if(trusted) ((DirectConnectHost)peer).relinkTrustedParticipant(identity);
        else ((DirectConnectHost)peer).decline(identity);
    }

    private void leave() {
        if(peer instanceof DirectConnectHost && !confirmClose
                && peer.getStatus().getPhase() != DirectConnectPhase.CLOSED
                && peer.getStatus().getPhase() != DirectConnectPhase.FAILED) confirmClose = true;
        else application.leaveDirectConnectSession();
    }

    private void navigate(Runnable action) {
        if(navigationPending || disposed) return;
        navigationPending = true;
        Gdx.app.postRunnable(() -> {
            if(disposed || application.getScreen() != this || application.getDirectConnectPeer() != peer) return;
            try { action.run(); }
            catch(RuntimeException failure) { showFailure(failure.getMessage()); }
            finally { navigationPending = false; }
        });
    }

    public void showFailure(String error) { entryError = error; }

    @Override public void show() {
        super.show();
        Gdx.input.setCursorCatched(false);
        Gdx.input.setInputProcessor(ui);
        ui.setKeyboardFocus(leave);
    }

    @Override protected void tick(float delta) {
        super.tick(delta);
        ui.act(delta);
        updateLobby();
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if(confirmClose) confirmClose = false;
            else navigate(this::leave);
        }
        else if(confirmClose && Gdx.input.isKeyJustPressed(Input.Keys.Y)) navigate(application::leaveDirectConnectSession);
        else if(confirmClose && Gdx.input.isKeyJustPressed(Input.Keys.N)) confirmClose = false;
        else if(!confirmClose && retry.isVisible() && Gdx.input.isKeyJustPressed(Input.Keys.T)) navigate(application::retryDirectConnectSession);
        else if(!confirmClose && relink.isVisible() && Gdx.input.isKeyJustPressed(Input.Keys.L)) navigate(() -> recoverClaim(true));
        else if(!confirmClose && reject.isVisible() && Gdx.input.isKeyJustPressed(Input.Keys.R)) navigate(() -> recoverClaim(false));
        else if(!confirmClose && ready.isVisible() && !ready.isDisabled() && Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) navigate(this::toggleReady);
        else if(!confirmClose && peer instanceof DirectConnectHost && Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !start.isDisabled())
            navigate(() -> application.startMultiplayerCampaign(peer));
        if(peer == null || navigationPending || confirmClose || floorEntryRequested) return;
        DirectConnectPhase phase = peer.getStatus().getPhase();
        boolean spectator = peer.getPartyStatus() != null && peer.getPartyStatus().getMember(peer.getLocalCampaignSlot()) != null
                && peer.getPartyStatus().getMember(peer.getLocalCampaignSlot()).getState()
                == com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState.SPECTATING;
        if(isFloorEntryReady(phase, peer.getLocalMovementEntityId(), peer.getMovementSnapshots())
                || spectator && (phase == DirectConnectPhase.READY || phase == DirectConnectPhase.SYNCHRONIZING)
                && !peer.getMovementSnapshots().isEmpty()) {
            floorEntryRequested = true;
            Gdx.app.postRunnable(() -> {
                if(disposed || application.getScreen() != this || application.getDirectConnectPeer() != peer) return;
                if(navigationPending || confirmClose) { floorEntryRequested = false; return; }
                try { if(!application.enterDirectConnectFloor(peer)) floorEntryRequested = false; }
                catch(RuntimeException failure) {
                    entryError = failure.getMessage();
                    try {
                        if(peer instanceof DirectConnectHost) ((DirectConnectHost)peer).abortCampaignRecovery(entryError);
                        else peer.close();
                    }
                    catch(RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    application.returnToDirectConnectSession();
                    application.showDirectConnectFailure(entryError);
                }
            });
        }
    }

    static boolean isFloorEntryReady(DirectConnectPhase phase, NetworkEntityId localEntityId, List<MovementSnapshot> snapshots) {
        if(phase != DirectConnectPhase.READY || localEntityId == null || snapshots == null || snapshots.isEmpty()) return false;
        return snapshots.get(snapshots.size() - 1).getEntity(localEntityId) != null;
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
