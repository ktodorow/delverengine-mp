package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.GameInput;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.LocalMovementReconciler.CorrectionStep;
import com.interrupt.dungeoneer.multiplayer.movement.LocalMovementReconciler.Reconciliation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshotInterpolator.InterpolatedMovementState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.overlays.OverlayManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Render-thread adapter for local prediction, reconciliation, and remote interpolation. */
public final class DirectConnectMovementController {
    private static final float INPUT_STEP_SECONDS = 1f / 60f;

    private final DirectConnectPeer peer;
    private final LocalMovementReconciler reconciler = new LocalMovementReconciler();
    private final MovementSnapshotInterpolator interpolator =
            new MovementSnapshotInterpolator();
    private final Map<NetworkEntityId, RemoteAvatar> remoteAvatars =
            new LinkedHashMap<NetworkEntityId, RemoteAvatar>();
    private long nextInputTick = 1L;
    private long lastReconciledSnapshot;
    private float inputAccumulator;
    private float unsampledDeltaX;
    private float unsampledDeltaY;
    private float unsampledDeltaZ;
    private float observedX;
    private float observedY;
    private float observedZ;
    private boolean observed;
    private Level attachedLevel;

    public DirectConnectMovementController(DirectConnectPeer peer) {
        if(peer == null) throw new IllegalArgumentException("Direct Connect peer cannot be null.");
        this.peer = peer;
    }

    public boolean applyInitialAuthoritativeState(Player player) {
        if(player == null) throw new IllegalArgumentException("Local Player cannot be null.");
        NetworkEntityId localId = peer.getLocalMovementEntityId();
        List<MovementSnapshot> snapshots = peer.getMovementSnapshots();
        if(localId == null || snapshots.isEmpty()) return false;
        MovementSnapshot latest = snapshots.get(snapshots.size() - 1);
        MovementEntityState authoritative = latest.getEntity(localId);
        if(authoritative == null) return false;
        player.setPosition(authoritative.getX(), authoritative.getY(), authoritative.getZ());
        player.xa = authoritative.getVelocityX();
        player.ya = authoritative.getVelocityY();
        player.za = authoritative.getVelocityZ();
        player.rot = authoritative.getRotation();
        reconciler.reset();
        interpolator.reset();
        lastReconciledSnapshot = latest.getSequence();
        observedX = player.x;
        observedY = player.y;
        observedZ = player.z;
        observed = true;
        return true;
    }

    public void update(Game game, GameInput input, float deltaSeconds) {
        if(game == null || game.player == null || game.level == null || input == null) return;
        Player player = game.player;
        float boundedDelta = Math.max(0f, Math.min(deltaSeconds, 0.1f));
        if(attachedLevel != game.level) attachToLevel(game.level);

        captureLocalPrediction(player);
        sampleInputs(player, input, boundedDelta);
        reconcileLocalPlayer(player);
        applyCorrection(player, boundedDelta);
        updateRemoteAvatars(game.level, boundedDelta);

        observedX = player.x;
        observedY = player.y;
        observedZ = player.z;
        observed = true;
    }

    public void dispose() {
        detachRemoteAvatars();
        attachedLevel = null;
    }

    public int getRemoteAvatarCount() {
        return remoteAvatars.size();
    }

    private void captureLocalPrediction(Player player) {
        if(!observed) {
            observedX = player.x;
            observedY = player.y;
            observedZ = player.z;
            observed = true;
            return;
        }
        unsampledDeltaX += player.x - observedX;
        unsampledDeltaY += player.y - observedY;
        unsampledDeltaZ += player.z - observedZ;
    }

    private void sampleInputs(Player player, GameInput input, float deltaSeconds) {
        inputAccumulator += deltaSeconds;
        int samples = Math.min(4, (int)(inputAccumulator / INPUT_STEP_SECONDS));
        if(samples <= 0) return;
        inputAccumulator -= samples * INPUT_STEP_SECONDS;

        float predictionX = unsampledDeltaX / samples;
        float predictionY = unsampledDeltaY / samples;
        float predictionZ = unsampledDeltaZ / samples;
        unsampledDeltaX = 0f;
        unsampledDeltaY = 0f;
        unsampledDeltaZ = 0f;

        boolean catchesInput = OverlayManager.instance.current() != null
                && OverlayManager.instance.current().catchInput;
        float forward = catchesInput ? 0f
                : (input.isMoveForwardPressed() ? 1f : 0f)
                - (input.isMoveBackwardsPressed() ? 1f : 0f);
        float strafe = catchesInput ? 0f
                : (input.isStrafeLeftPressed() ? 1f : 0f)
                - (input.isStrafeRightPressed() ? 1f : 0f);
        float length = (float)Math.sqrt(forward * forward + strafe * strafe);
        if(length > 1f) {
            forward /= length;
            strafe /= length;
        }
        boolean jump = !catchesInput && input.isJumpPressed();

        for(int i = 0; i < samples; i++) {
            long tick = nextInputTick++;
            reconciler.addPrediction(tick, predictionX, predictionY, predictionZ);
            peer.submitMovementInput(new MovementInputFrame(tick, forward, strafe,
                    player.rot, jump));
        }
    }

    private void reconcileLocalPlayer(Player player) {
        NetworkEntityId localId = peer.getLocalMovementEntityId();
        List<MovementSnapshot> snapshots = peer.getMovementSnapshots();
        if(localId == null || snapshots.isEmpty()) return;
        MovementSnapshot latest = snapshots.get(snapshots.size() - 1);
        if(latest.getSequence() <= lastReconciledSnapshot) return;
        MovementEntityState authoritative = latest.getEntity(localId);
        if(authoritative == null) return;
        lastReconciledSnapshot = latest.getSequence();

        Reconciliation result = reconciler.reconcile(authoritative,
                player.x, player.y, player.z);
        if(result.isSnapRequired()) {
            player.setPosition(result.getTargetX(), result.getTargetY(),
                    result.getTargetZ());
            player.xa = 0f;
            player.ya = 0f;
            player.za = 0f;
        }
    }

    private void applyCorrection(Player player, float deltaSeconds) {
        CorrectionStep correction = reconciler.advance(deltaSeconds);
        player.x += correction.getX();
        player.y += correction.getY();
        player.z += correction.getZ();
    }

    private void attachToLevel(Level level) {
        detachRemoteAvatars();
        interpolator.reset();
        attachedLevel = level;
    }

    private void updateRemoteAvatars(Level level, float deltaSeconds) {
        NetworkEntityId localId = peer.getLocalMovementEntityId();
        List<MovementEntityDescriptor> descriptors = peer.getMovementEntities();
        Map<NetworkEntityId, MovementEntityDescriptor> active =
                new LinkedHashMap<NetworkEntityId, MovementEntityDescriptor>();
        for(MovementEntityDescriptor descriptor : descriptors) {
            if(descriptor.getEntityId().equals(localId)) continue;
            active.put(descriptor.getEntityId(), descriptor);
            if(!remoteAvatars.containsKey(descriptor.getEntityId())) {
                RemoteAvatar avatar = new RemoteAvatar(descriptor);
                remoteAvatars.put(descriptor.getEntityId(), avatar);
                level.addEntity(avatar);
            }
        }

        for(NetworkEntityId entityId : new ArrayList<NetworkEntityId>(
                remoteAvatars.keySet())) {
            if(active.containsKey(entityId)) continue;
            RemoteAvatar removed = remoteAvatars.remove(entityId);
            removed.isActive = false;
            level.non_collidable_entities.removeValue(removed, true);
        }

        List<MovementSnapshot> snapshots = peer.getMovementSnapshots();
        if(snapshots.isEmpty()) return;
        interpolator.advance(snapshots, deltaSeconds);

        for(Map.Entry<NetworkEntityId, RemoteAvatar> entry : remoteAvatars.entrySet()) {
            InterpolatedMovementState state = interpolator.sample(entry.getKey(), snapshots);
            if(state == null) continue;
            entry.getValue().applyNetworkState(
                    state.getX(), state.getY(), state.getZ(),
                    state.getVelocityX(), state.getVelocityY(), state.getVelocityZ(),
                    state.getMovementState());
        }
    }

    private void detachRemoteAvatars() {
        if(attachedLevel != null) {
            for(RemoteAvatar avatar : remoteAvatars.values()) {
                avatar.isActive = false;
                attachedLevel.non_collidable_entities.removeValue(avatar, true);
            }
        }
        remoteAvatars.clear();
    }
}
