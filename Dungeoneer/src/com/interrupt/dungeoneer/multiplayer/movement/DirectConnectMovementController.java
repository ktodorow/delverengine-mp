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
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;

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
    private final NativeMovementBodies bodies = new NativeMovementBodies();
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
    private boolean jumpRequested;
    private Level attachedLevel;

    public DirectConnectMovementController(DirectConnectPeer peer) {
        if(peer == null) throw new IllegalArgumentException("Direct Connect peer cannot be null.");
        this.peer = peer;
    }

    public boolean applyInitialAuthoritativeState(Player player) {
        if(player == null) throw new IllegalArgumentException("Local Player cannot be null.");
        NetworkEntityId localId = peer.getLocalMovementEntityId();
        List<MovementSnapshot> snapshots = peer.getMovementSnapshots();
        PartyMemberStatus local =
                peer.getPartyStatus() == null ? null
                        : peer.getPartyStatus().getMember(peer.getLocalCampaignSlot());
        if(localId == null && local != null && local.getState()
                == PartyMemberState.SPECTATING) {
            player.setMultiplayerIncapacitated(true);
            player.inventory.clear();
            // Native HUD addresses inventorySize slots, including empty Spectator slots.
            for(int i = 0; i < player.inventorySize; i++) player.inventory.add(null);
            player.equippedItems.clear();
            player.gold = 0;
            return true;
        }
        boolean spectator = local != null && local.getState()
                == PartyMemberState.SPECTATING;
        // Exhausted retained bodies can already be absent from live movement snapshots.
        if(spectator) player.setMultiplayerIncapacitated(true);
        if(localId == null || snapshots.isEmpty()) return spectator;
        MovementSnapshot latest = snapshots.get(snapshots.size() - 1);
        MovementEntityState authoritative = latest.getEntity(localId);
        if(authoritative == null) return spectator;
        player.setMultiplayerIncapacitated(local != null && (local.getState()
                == PartyMemberState.DOWNED
                || local.getState() == PartyMemberState.SPECTATING));
        player.setPosition(authoritative.getX(), authoritative.getY(), authoritative.getZ());
        // Snapshots carry units per second; native Player velocity is per 60 Hz tick.
        player.xa = authoritative.getVelocityX() / 60f;
        player.ya = authoritative.getVelocityY() / 60f;
        player.za = authoritative.getVelocityZ() / 60f;
        player.rot = authoritative.getRotation();
        nextInputTick = Math.max(nextInputTick, authoritative.getLastProcessedInputTick() + 1L);
        reconciler.reset();
        interpolator.reset();
        lastReconciledSnapshot = latest.getSequence();
        observedX = player.x;
        observedY = player.y;
        observedZ = player.z;
        observed = true;
        return true;
    }

    /** Reconstructs current bodies during admission without sampling input or advancing native play. */
    public void prepare(Game game) {
        if(game == null || game.level == null) return;
        if(attachedLevel != game.level) attachToLevel(game.level);
        updateRemoteAvatars(game.level, 0f);
        bodies.updateParticipants(remoteAvatars.values(), peer.getCurrentMovementStates());
    }

    public void update(Game game, GameInput input, float deltaSeconds) {
        if(game == null || game.player == null || game.level == null || input == null) return;
        Player player = game.player;
        float boundedDelta = Math.max(0f, Math.min(deltaSeconds, 0.1f));
        if(attachedLevel != game.level) attachToLevel(game.level);

        captureLocalPrediction(player);
        if(player.multiplayerIncapacitated) {
            // Host froze this body; unsent ticks would outrun its accepted input lead.
            unsampledDeltaX = unsampledDeltaY = unsampledDeltaZ = 0f;
            inputAccumulator = 0f;
            jumpRequested = false;
        }
        else sampleInputs(player, input, boundedDelta);
        reconcileLocalPlayer(player);
        applyCorrection(player, boundedDelta);
        updateRemoteAvatars(game.level, boundedDelta);
        bodies.updateParticipants(remoteAvatars.values(), peer.getCurrentMovementStates());

        observedX = player.x;
        observedY = player.y;
        observedZ = player.z;
        observed = true;
    }

    public void dispose() {
        detachRemoteAvatars();
        if(attachedLevel != null && attachedLevel.movementBodies == bodies) attachedLevel.movementBodies = null;
        attachedLevel = null;
    }

    /** Native body blocking between this peer's movers and bodies other peers simulate. */
    public NativeMovementBodies getMovementBodies() {
        return bodies;
    }

    public int getRemoteAvatarCount() {
        return remoteAvatars.size();
    }

    public RemoteAvatar getRemoteAvatar(ParticipantId participantId) {
        if(participantId == null) return null;
        for(RemoteAvatar avatar : remoteAvatars.values()) {
            if(participantId.equals(avatar.getDescriptor().getParticipantId())) return avatar;
        }
        return null;
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
        boolean catchesInput = OverlayManager.instance.current() != null
                && OverlayManager.instance.current().catchInput;
        // Native jump fires on the frame it is pressed; keep it for the next input sample.
        jumpRequested |= !catchesInput && input.isJumpPressed();
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
        boolean jump = jumpRequested;
        jumpRequested = false;

        for(int i = 0; i < samples; i++) {
            long tick = nextInputTick++;
            reconciler.addPrediction(tick, predictionX, predictionY, predictionZ);
            peer.submitMovementInput(new MovementInputFrame(tick, forward, strafe,
                    player.rot, jump, com.interrupt.dungeoneer.GameManager.renderer.camera.direction.y));
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
        nextInputTick = Math.max(nextInputTick, authoritative.getLastProcessedInputTick() + 1L);
        lastReconciledSnapshot = latest.getSequence();

        Reconciliation result = reconciler.reconcile(authoritative,
                player.x, player.y, player.z);
        if(result.isSnapRequired()) {
            float x = result.getTargetX(), y = result.getTargetY(), z = result.getTargetZ();
            // Replayed input can land inside a wall the Host never entered; Host's own spot is safe.
            if(attachedLevel != null && !canStand(attachedLevel, player, x, y, z)) {
                x = authoritative.getX();
                y = authoritative.getY();
                z = authoritative.getZ();
            }
            player.setPosition(x, y, z);
            player.xa = 0f;
            player.ya = 0f;
            player.za = 0f;
        }
    }

    /**
     * Native Player physics lifts a Player whose box overlaps a higher floor straight onto it,
     * however high, so a correction only moves the box where native walking could: never into a
     * wall (a push of 0.001 into a 1.4 high block put the Player on top) or a solid object.
     */
    private void applyCorrection(Player player, float deltaSeconds) {
        CorrectionStep correction = reconciler.advance(deltaSeconds);
        player.z += correction.getZ();
        Level level = attachedLevel;
        if(correction.getX() != 0f) {
            float x = player.x + correction.getX();
            if(level == null || canStand(level, player, x, player.y, player.z)) player.x = x;
        }
        if(correction.getY() != 0f) {
            float y = player.y + correction.getY();
            if(level == null || canStand(level, player, player.x, y, player.z)) player.y = y;
        }
    }

    private static boolean canStand(Level level, Player player, float x, float y, float z) {
        return level.isFree(x, y, z, player.collision, player.getMovementStepHeight(), false, null)
                && level.checkEntityCollision(x, y, z, player.collision, player) == null;
    }

    private void attachToLevel(Level level) {
        detachRemoteAvatars();
        if(attachedLevel != null && attachedLevel.movementBodies == bodies) attachedLevel.movementBodies = null;
        interpolator.reset();
        attachedLevel = level;
        level.movementBodies = bodies;
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
