package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSimulation;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionCommand;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionSnapshot;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fixed-step Host authority for movement on one Active Floor. Each step is native Player.tick
 * movement physics (per-tick velocities, friction, water, slopes, step-ups, ladders, jumping) so
 * the local prediction every peer runs through its own native Player agrees with the Host.
 */
public final class AuthoritativeMovementSimulation implements AuthoritativeHostSimulation {
    public static final long MAX_INPUT_TICK_LEAD = 600L;

    private static final int MAX_SAVED_INPUT_STEPS = 12;
    private static final int MAX_RETAINED_INPUT_STEPS = 1;
    private static final long INPUT_REORDER_GRACE_TICKS = 3L;
    private static final float MOVING_EPSILON = 0.01f;
    private static final float RADIUS = 0.2f;
    private static final float HEIGHT = 0.65f;
    /** Native Player walkVel, and getWalkSpeed() at the base Speed of 4 (0.10 + 4 * 0.015). */
    private static final float NATIVE_WALK_VELOCITY = 0.05f;
    static final float NATIVE_WALK_SPEED = 0.16f;
    private static final float NATIVE_JUMP = 0.05f;
    private static final float NATIVE_GRAVITY = 0.0035f;
    private static final float NATIVE_STEP_HEIGHT = 0.35f;
    /** Level.collidesWorldOrEntities reads Entity.stepHeight, which Player shadows and never sets. */
    private static final float NATIVE_ENTITY_STEP_HEIGHT = 0.5f;
    /** Land keeps 80 percent of velocity per tick, so steady travel is force / 0.2 per tick. */
    public static final float MAX_SPEED = NATIVE_WALK_VELOCITY * NATIVE_WALK_SPEED / 0.2f * 60f;
    /**
     * Native water: walking force is scaled by min(0.08 * 1.4, 1) and only 4 percent of velocity
     * is removed per tick, so top speed is 0.112 / 0.04 = 2.8 units of force against 5 on land.
     */
    static final float WATER_SPEED = 0.56f;

    private final MovementCollisionWorld world;
    private final Map<ParticipantId, MutableMovement> participants =
            new TreeMap<ParticipantId, MutableMovement>();

    public AuthoritativeMovementSimulation(MovementCollisionWorld world,
            List<MovementEntityDescriptor> descriptors) {
        if(world == null) throw new IllegalArgumentException("Movement collision world cannot be null.");
        if(descriptors == null || descriptors.isEmpty() || descriptors.size() > 4) {
            throw new IllegalArgumentException("Movement session must contain one to four Participants.");
        }
        this.world = world;
        Map<NetworkEntityId, MovementEntityDescriptor> entityIds =
                new LinkedHashMap<NetworkEntityId, MovementEntityDescriptor>();
        for(MovementEntityDescriptor descriptor : descriptors) {
            if(descriptor == null) throw new IllegalArgumentException("Movement descriptor cannot be null.");
            if(entityIds.put(descriptor.getEntityId(), descriptor) != null
                    || participants.containsKey(descriptor.getParticipantId())) {
                throw new IllegalArgumentException("Movement session contains duplicate identity.");
            }
            MovementSpawn spawn = world.getSpawn(descriptor.getCampaignSlot());
            participants.put(descriptor.getParticipantId(), new MutableMovement(
                    descriptor, spawn));
        }
    }

    /** Reintroduces an authenticated Campaign Slot at its safe floor spawn. */
    public synchronized void addParticipant(MovementEntityDescriptor descriptor) {
        if(descriptor == null) throw new IllegalArgumentException("Movement descriptor cannot be null.");
        if(participants.size() >= 4 || participants.containsKey(descriptor.getParticipantId())) {
            throw new IllegalArgumentException("Movement Participant is already present or floor is full.");
        }
        for(MutableMovement participant : participants.values()) {
            if(participant.descriptor.getEntityId().equals(descriptor.getEntityId())) {
                throw new IllegalArgumentException("Movement entity identity is already present.");
            }
        }
        participants.put(descriptor.getParticipantId(), new MutableMovement(descriptor,
                world.getSpawn(descriptor.getCampaignSlot())));
    }

    @Override
    public synchronized void applyCommand(long hostTick, HostSessionCommand command,
            HostSessionOutput output) {
        if(!(command instanceof MovementInputCommand)) {
            throw new IllegalArgumentException("Unsupported movement Host command: " + command);
        }
        MovementInputCommand movement = (MovementInputCommand)command;
        MutableMovement participant = participants.get(movement.getParticipantId());
        if(participant == null || participant.frozen) return;
        MovementInputFrame input = movement.getInput();
        if(input.getInputTick() <= participant.lastProcessedInputTick) return;
        if(input.getInputTick() - participant.lastProcessedInputTick > MAX_INPUT_TICK_LEAD) return;
        if(!participant.pendingInputs.containsKey(input.getInputTick())) {
            participant.pendingInputs.put(input.getInputTick(),
                    new QueuedInput(input, hostTick));
        }
    }

    @Override
    public synchronized void tick(long hostTick, float fixedDeltaSeconds,
            HostSessionOutput output) {
        for(MutableMovement participant : participants.values()) {
            if(participant.frozen) continue;
            // One credit per Host tick bounds sustained movement to 60 Hz while
            // saved credits let delayed UDP bundles catch up in validated steps.
            participant.savedInputSteps = Math.min(MAX_SAVED_INPUT_STEPS,
                    participant.savedInputSteps + 1);
            boolean processedInput = false;
            while(participant.savedInputSteps > 0) {
                QueuedInput queued = nextInput(participant, hostTick);
                if(queued == null) break;
                MovementInputFrame previous = participant.input;
                participant.input = queued.input;
                participant.lastProcessedInputTick = queued.input.getInputTick();
                if(queued.input.isJump()
                        && (previous == null || !previous.isJump())) {
                    participant.jumpQueued = true;
                }
                step(participant, fixedDeltaSeconds);
                participant.savedInputSteps--;
                processedInput = true;
            }
            // Native explosions and other Host-owned forces must take effect even
            // when this tick's client input was lost or deliberately withheld.
            if(!processedInput && participant.hasNativeImpulse()) {
                step(participant, fixedDeltaSeconds);
                processedInput = true;
            }
            if(processedInput && participant.pendingInputs.isEmpty()) {
                participant.savedInputSteps = Math.min(MAX_RETAINED_INPUT_STEPS,
                        participant.savedInputSteps);
            }
        }
    }

    @Override
    public synchronized HostSessionSnapshot snapshot(long hostTick) {
        List<MovementEntityState> states = new ArrayList<MovementEntityState>();
        for(MutableMovement participant : participants.values()) {
            states.add(participant.snapshot());
        }
        return new MovementSnapshot(hostTick, hostTick, states);
    }

    public synchronized void removeParticipant(ParticipantId participantId) {
        if(participantId != null) participants.remove(participantId);
    }

    /** Stops accepted and queued inputs while Host protects disconnected character. */
    public synchronized void freezeParticipant(ParticipantId participantId) {
        MutableMovement participant = participants.get(participantId);
        if(participant != null) participant.freeze();
    }

    /** Restores input processing after authenticated reconnect. */
    public synchronized void resumeParticipant(ParticipantId participantId) {
        MutableMovement participant = participants.get(participantId);
        if(participant != null) participant.resume();
    }

    /** Latest accepted input asks to walk or jump; deliberate movement interrupts Revival. */
    public synchronized boolean isRequestingMovement(ParticipantId participantId) {
        MutableMovement participant = participants.get(participantId);
        if(participant == null || participant.input == null) return false;
        return participant.input.getForward() != 0f || participant.input.getStrafe() != 0f
                || participant.input.isJump();
    }

    /** Every Participant's accepted current movement, for Host-side native body blocking. */
    public synchronized List<MovementEntityState> currentStates() {
        List<MovementEntityState> states = new ArrayList<MovementEntityState>(participants.size());
        for(MutableMovement participant : participants.values()) states.add(participant.snapshot());
        return states;
    }

    public synchronized MovementEntityState getState(ParticipantId participantId) {
        MutableMovement participant = participants.get(participantId);
        return participant == null ? null : participant.snapshot();
    }

    /** Applies durable current movement outcome without replaying queued inputs or impulses. */
    public synchronized void restoreState(ParticipantId participantId,
            MovementEntityState state) {
        MutableMovement participant = participants.get(participantId);
        if(participant == null || state == null
                || !participant.descriptor.getEntityId().equals(state.getEntityId())
                || participant.descriptor.getLifecycleSequence() != state.getLifecycleSequence()
                || !world.canOccupy(state.getX(), state.getY(), state.getZ())) {
            throw new IllegalArgumentException("Saved movement state does not match Active Floor Participant.");
        }
        participant.input = null;
        participant.pendingInputs.clear();
        participant.savedInputSteps = 0;
        participant.lastProcessedInputTick = state.getLastProcessedInputTick();
        participant.x = state.getX(); participant.y = state.getY(); participant.z = state.getZ();
        participant.xa = participant.movedXa = state.getVelocityX() / 60f;
        participant.ya = participant.movedYa = state.getVelocityY() / 60f;
        participant.za = participant.movedZa = state.getVelocityZ() / 60f;
        participant.rotation = state.getRotation();
        participant.movementState = state.getMovementState();
        participant.lookY = state.getLookY();
        settle(participant);
        participant.nativeImpulseX = participant.nativeImpulseY = participant.nativeImpulseZ = 0f;
        participant.jumpQueued = false;
        participant.frozen = false;
    }

    /** Native status product, captured on Host render thread; never supplied by client input. */
    public synchronized void setNativeSpeedModifier(ParticipantId participantId, float speed) {
        if(Float.isNaN(speed) || Float.isInfinite(speed) || speed < 0)
            throw new IllegalArgumentException("Invalid native movement speed modifier.");
        MutableMovement participant = participants.get(participantId);
        if(participant != null) participant.nativeSpeedModifier = speed;
    }

    /** Native Player walk speed before status effects (Speed stat, equipment), from Host data. */
    public synchronized void setNativeWalkSpeed(ParticipantId participantId, float walkSpeed) {
        if(Float.isNaN(walkSpeed) || Float.isInfinite(walkSpeed) || walkSpeed < 0f || walkSpeed > 1f)
            throw new IllegalArgumentException("Invalid native walk speed.");
        MutableMovement participant = participants.get(participantId);
        if(participant != null) participant.nativeWalkSpeed = walkSpeed;
    }

    public synchronized void setNativeTimeScale(ParticipantId participantId, float scale) {
        if(Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0)
            throw new IllegalArgumentException("Invalid native personal time scale.");
        MutableMovement participant = participants.get(participantId);
        if(participant != null) participant.nativeTimeScale = scale;
    }

    public synchronized void setNativeFlight(ParticipantId participantId, boolean floating, float speed) {
        if(Float.isNaN(speed) || Float.isInfinite(speed) || speed < 0)
            throw new IllegalArgumentException("Invalid native flight speed.");
        MutableMovement participant = participants.get(participantId);
        if(participant != null) { participant.floating = floating; participant.flightSpeed = speed; }
    }

    public synchronized void applyNativeImpulse(ParticipantId participantId,
            float x, float y, float z) {
        if(!finite(x) || !finite(y) || !finite(z)
                || Math.abs(x) > 2f || Math.abs(y) > 2f || Math.abs(z) > 2f)
            throw new IllegalArgumentException("Invalid native movement impulse.");
        MutableMovement participant = participants.get(participantId);
        if(participant != null && !participant.frozen) {
            // Native per-tick velocity change, as the Host's native Actor received it.
            participant.nativeImpulseX += x;
            participant.nativeImpulseY += y;
            participant.nativeImpulseZ += z;
        }
    }

    /** Host-native teleport result; client input never supplies these coordinates. */
    public synchronized void setNativePosition(ParticipantId participantId,
            float x, float y, float z) {
        if(!finite(x) || !finite(y) || !finite(z) || !world.canOccupy(x, y, z)) {
            throw new IllegalArgumentException("Invalid native participant position.");
        }
        MutableMovement participant = participants.get(participantId);
        if(participant == null) return;
        participant.x = x;
        participant.y = y;
        participant.z = z;
        participant.xa = participant.ya = participant.za = 0f;
        participant.movedXa = participant.movedYa = participant.movedZa = 0f;
        participant.nativeImpulseX = participant.nativeImpulseY = participant.nativeImpulseZ = 0f;
        participant.jumpQueued = false;
        settle(participant);
        participant.movementState = participant.onFloor || participant.onEntity
                ? MovementState.IDLE : MovementState.AIRBORNE;
    }

    /** Native ground contact for a placed Participant: tile floor, else the top of an object. */
    private void settle(MutableMovement participant) {
        float x = participant.x, y = participant.y, z = participant.z;
        participant.onFloor = z <= world.getTileFloorZ(x, y, z) + 0.035f;
        participant.onEntity = !participant.onFloor && Math.abs(z - world.getFloorZ(x, y, z)) <= 0.01f;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private QueuedInput nextInput(MutableMovement participant, long hostTick) {
        long expectedTick = participant.lastProcessedInputTick + 1L;
        QueuedInput expected = participant.pendingInputs.remove(expectedTick);
        if(expected != null) return expected;
        Map.Entry<Long, QueuedInput> first = participant.pendingInputs.firstEntry();
        // Briefly hold newer input for reordering, then skip a permanently lost gap.
        if(first == null
                || hostTick - first.getValue().receivedHostTick
                        < INPUT_REORDER_GRACE_TICKS) return null;
        participant.pendingInputs.remove(first.getKey());
        return first.getValue();
    }

    /** One native Player frame: input forces as Player.tick(level, delta, input), then its physics. */
    private void step(MutableMovement participant, float deltaSeconds) {
        float delta = deltaSeconds * 60f * participant.nativeTimeScale;
        MovementInputFrame input = participant.input;
        float forward = input == null ? 0f : input.getForward();
        float strafe = input == null ? 0f : input.getStrafe();
        float rotation = input == null ? participant.rotation : normalizeRotation(input.getRotation());
        float lookY = input == null ? participant.lookY : input.getLookY();

        float length = (float)Math.sqrt(forward * forward + strafe * strafe);
        if(length > 1f) {
            forward /= length;
            strafe /= length;
        }
        // Walking backwards is slower.
        if(forward < 0f) {
            strafe *= 0.8f;
            forward *= 0.5f;
        }
        float zm = forward * NATIVE_WALK_VELOCITY;
        float xm = strafe * NATIVE_WALK_VELOCITY;

        // Ladder.tick runs in Level.tick, before the Player's own tick.
        participant.onLadder = world.isOnLadder(participant.x, participant.y, participant.z);
        if(participant.jumpQueued && (participant.onFloor || participant.onEntity)
                && !participant.onLadder) {
            participant.za += NATIVE_JUMP;
        }
        participant.jumpQueued = false;

        float walkSpeed = participant.nativeWalkSpeed * participant.nativeSpeedModifier;
        float xMod = (float)(xm * Math.cos(rotation) + zm * Math.sin(rotation)) * walkSpeed * delta;
        float yMod = (float)(zm * Math.cos(rotation) - xm * Math.sin(rotation)) * walkSpeed * delta;
        if(participant.floating) {
            if(!participant.onFloor && !participant.onEntity) {
                // Native flight steers along the camera: pitch from lookY, yaw from rotation + 3.14.
                float horizontal = (float)Math.sqrt(Math.max(0, 1f - lookY * lookY));
                float lookX = (float)-Math.sin(rotation + 3.14f) * horizontal;
                float lookZ = (float)-Math.cos(rotation + 3.14f) * horizontal;
                xMod = com.interrupt.dungeoneer.entities.Player.nativeFlightMove(zm, xm, lookX,
                        (float)Math.cos(rotation), participant.flightSpeed, delta);
                yMod = com.interrupt.dungeoneer.entities.Player.nativeFlightMove(zm, xm, lookZ,
                        (float)-Math.sin(rotation), participant.flightSpeed, delta);
            }
            participant.za = com.interrupt.dungeoneer.entities.Player.nativeFlightVertical(
                    participant.za, lookY, forward, participant.flightSpeed, delta);
            participant.xa = com.interrupt.dungeoneer.entities.Player.nativeFlightFriction(participant.xa, delta);
            participant.ya = com.interrupt.dungeoneer.entities.Player.nativeFlightFriction(participant.ya, delta);
        }
        if(participant.onLadder) {
            xMod *= 0.5f;
            yMod *= 0.5f;
        }
        float control = Math.min(participant.friction * 1.4f, 1f);
        participant.xa += xMod * control + participant.nativeImpulseX;
        participant.ya += yMod * control + participant.nativeImpulseY;
        participant.za += participant.nativeImpulseZ;
        participant.nativeImpulseX = participant.nativeImpulseY = participant.nativeImpulseZ = 0f;
        participant.rotation = rotation;
        if(input != null) participant.lookY = lookY;

        float startX = participant.x, startY = participant.y;
        physics(participant, delta);

        boolean requestedMovement = Math.abs(xMod) + Math.abs(yMod) > 0f;
        boolean moved = Math.abs(participant.x - startX) + Math.abs(participant.y - startY)
                > MOVING_EPSILON * deltaSeconds;
        if(!participant.onFloor && !participant.onEntity) participant.movementState = MovementState.AIRBORNE;
        else if(requestedMovement && !moved) participant.movementState = MovementState.BLOCKED;
        else if(moved || Math.abs(participant.xa) + Math.abs(participant.ya)
                > MOVING_EPSILON / 60f) participant.movementState = MovementState.MOVING;
        else participant.movementState = MovementState.IDLE;

        // Native climbing: looking up or down while walking into a ladder, set after physics.
        if(participant.onLadder) {
            if(forward > 0f) {
                float pitch = (float)Math.asin(Math.max(-1f, Math.min(1f, lookY)));
                participant.za = Math.max(-0.2f, Math.min(0.2f, pitch)) * 0.1f;
            }
            else participant.za = 0f;
            if(participant.onFloor && participant.za < 0f) participant.za = 0f;
        }
    }

    /** Native Player.tick(level, delta) movement: walls, objects, falling, stepping, water. */
    private void physics(MutableMovement participant, float delta) {
        float nextX = participant.x + participant.xa * delta;
        float nextY = participant.y + participant.ya * delta;

        world.getFloorNormal(participant.x, participant.y, participant.z, participant.normal);
        float slopeX = participant.normal[0] * Math.abs(participant.normal[0]) * 0.01f;
        float slopeY = participant.normal[1] * Math.abs(participant.normal[1]) * 0.01f;
        if(Math.abs(slopeX) < 0.003f) slopeX = 0f;
        if(Math.abs(slopeY) < 0.003f) slopeY = 0f;
        if(participant.onFloor) {
            participant.xa += slopeX * delta;
            participant.ya += slopeY * delta;
        }

        if(world.isTileFree(nextX, participant.y, participant.z, participant.stepHeight)) {
            MovementObstacle encroaching = encroaching(nextX, participant.y, participant.z);
            float step = climbTo(participant, encroaching, nextX, participant.y);
            if(!Float.isNaN(step)) {
                participant.x += participant.xa * delta;
                participant.z = step;
            }
            else participant.xa = 0f;
        }
        else participant.xa = 0f;

        if(world.isTileFree(participant.x, nextY, participant.z, participant.stepHeight)) {
            MovementObstacle encroaching = encroaching(participant.x, nextY, participant.z);
            float step = climbTo(participant, encroaching, participant.x, nextY);
            if(!Float.isNaN(step)) {
                participant.y += participant.ya * delta;
                participant.z = step;
            }
            else participant.ya = 0f;
        }
        else participant.ya = 0f;
        participant.movedXa = participant.xa;
        participant.movedYa = participant.ya;

        // Falling and stepping.
        participant.onEntity = false;
        float probeZ = participant.z + participant.za * delta - 0.02f;
        MovementObstacle standingOn = null;
        for(MovementObstacle obstacle : world.getObstacles(participant.x, participant.y)) {
            if(!obstacle.overlaps(participant.x, participant.y, probeZ, RADIUS, HEIGHT)) continue;
            if(standingOn == null || obstacle.maxZ > standingOn.maxZ) standingOn = obstacle;
            // Native Player bounces off Actors and Triggers instead of standing on them.
            if(!standingOn.standable) participant.za = 0.02f;
        }
        if(participant.za > 0f && !world.isTileFree(participant.x, participant.y,
                participant.z + participant.za, participant.stepHeight)) participant.za = 0f;
        if(standingOn == null) participant.z += participant.za * delta;
        else if(participant.za <= 0f) participant.onEntity = true;
        participant.movedZa = participant.za;

        float floorZ = world.getTileFloorZ(participant.x, participant.y, participant.z);
        float floorClamp = participant.floating || participant.za > 0f ? 0f : 0.035f;
        participant.onFloor = participant.z <= floorZ + floorClamp;
        if(!participant.onFloor && !participant.onEntity) {
            if(!participant.onLadder && !participant.floating) participant.za -= NATIVE_GRAVITY * delta;
        }
        else if(!participant.onLadder) {
            float stepUpTo = floorZ;
            if(standingOn != null && standingOn.maxZ - participant.z < participant.stepHeight
                    && standingOn.maxZ > stepUpTo) stepUpTo = standingOn.maxZ;
            if(freeOfWorldAndObjects(participant.x, participant.y, stepUpTo)) {
                if(stepUpTo > participant.z) participant.z = stepUpTo;
                else if(participant.onFloor && !participant.onEntity) participant.z = floorZ;
                else if(participant.onEntity) participant.z = stepUpTo;
            }
            if(!participant.floating) {
                // Native objects never carry vertical speed into the Host copy.
                if(participant.onEntity && standingOn != null) {
                    participant.za = Math.max(participant.za - NATIVE_GRAVITY * delta, 0f);
                }
                else if(participant.za < 0f) participant.za = 0f;
            }
        }

        float waterSurface = world.getWaterSurfaceZ(participant.x, participant.y, participant.z);
        if(!Float.isNaN(waterSurface)) {
            participant.friction = 0.08f;
            participant.xa -= (participant.xa - participant.xa * 0.5f) * participant.friction * delta;
            participant.ya -= (participant.ya - participant.ya * 0.5f) * participant.friction * delta;
            // Native: lets the Player climb up out of the water.
            participant.stepHeight = 0.3499f + (waterSurface - participant.z);
        }
        else {
            participant.stepHeight = NATIVE_STEP_HEIGHT;
            if(participant.onFloor) participant.friction = world.getFloorFriction(participant.x, participant.y);
            else if(participant.onEntity || participant.onLadder) participant.friction = 1f;
            else participant.friction = 0.1f;
            participant.xa -= (participant.xa - participant.xa * 0.8f) * participant.friction * delta;
            participant.ya -= (participant.ya - participant.ya * 0.8f) * participant.friction * delta;
        }
    }

    /** Height after moving into an object's space, the current height with none, or NaN when blocked. */
    private float climbTo(MutableMovement participant, MovementObstacle encroaching, float x, float y) {
        if(encroaching == null) return participant.z;
        float top = encroaching.maxZ;
        if(participant.z > top - participant.stepHeight && encroaching.stepable
                && freeOfWorldAndObjects(x, y, top)) return top;
        return Float.NaN;
    }

    /** Native getHighestEntityCollision, then checkStandingRoomWithEntities. */
    private MovementObstacle encroaching(float x, float y, float z) {
        MovementObstacle highest = null;
        for(MovementObstacle obstacle : world.getObstacles(x, y)) {
            if(obstacle.overlaps(x, y, z, RADIUS, HEIGHT)
                    && (highest == null || highest.minZ < obstacle.minZ)) highest = obstacle;
        }
        if(highest != null) return highest;
        // An object standing on a higher floor the Participant would step up to.
        for(int cornerX = -1; cornerX <= 1; cornerX += 2) {
            for(int cornerY = -1; cornerY <= 1; cornerY += 2) {
                float floor = world.getPointFloorZ(x + cornerX * RADIUS, y + cornerY * RADIUS);
                if(floor > z) {
                    MovementObstacle colliding = firstColliding(x, y, floor);
                    if(colliding != null) return colliding;
                }
            }
        }
        return null;
    }

    private MovementObstacle firstColliding(float x, float y, float z) {
        for(MovementObstacle obstacle : world.getObstacles(x, y)) {
            if(obstacle.overlaps(x, y, z, RADIUS, HEIGHT)) return obstacle;
        }
        return null;
    }

    /** Native Level.collidesWorldOrEntities, which answers whether the space is free. */
    private boolean freeOfWorldAndObjects(float x, float y, float z) {
        return world.isTileFree(x, y, z, NATIVE_ENTITY_STEP_HEIGHT) && firstColliding(x, y, z) == null;
    }

    private static float normalizeRotation(float rotation) {
        float fullTurn = (float)(Math.PI * 2.0);
        while(rotation > Math.PI) rotation -= fullTurn;
        while(rotation < -Math.PI) rotation += fullTurn;
        return rotation;
    }

    private static final class MutableMovement {
        private final MovementEntityDescriptor descriptor;
        private final TreeMap<Long, QueuedInput> pendingInputs =
                new TreeMap<Long, QueuedInput>();
        private MovementInputFrame input;
        private long lastProcessedInputTick;
        private int savedInputSteps;
        private float x;
        private float y;
        private float z;
        /** Native per-tick velocity (Player xa, ya, za). */
        private float xa;
        private float ya;
        private float za;
        /** Velocity that carried this tick's move, before native friction and gravity. */
        private float movedXa;
        private float movedYa;
        private float movedZa;
        private float rotation;
        private boolean onFloor = true;
        private boolean onEntity;
        private boolean onLadder;
        private float friction = 1f;
        private float stepHeight = NATIVE_STEP_HEIGHT;
        private final float[] normal = new float[3];
        private boolean jumpQueued;
        private boolean frozen;
        private float nativeSpeedModifier = 1f;
        private float nativeWalkSpeed = NATIVE_WALK_SPEED;
        private float nativeTimeScale = 1f;
        private float nativeImpulseX;
        private float nativeImpulseY;
        private float nativeImpulseZ;
        private boolean floating;
        private float flightSpeed;
        private MovementState movementState = MovementState.IDLE;
        private float lookY;

        private MutableMovement(MovementEntityDescriptor descriptor, MovementSpawn spawn) {
            this.descriptor = descriptor;
            x = spawn.getX();
            y = spawn.getY();
            z = spawn.getZ();
            rotation = spawn.getRotation();
        }

        private MovementEntityState snapshot() {
            return new MovementEntityState(descriptor.getEntityId(),
                    descriptor.getLifecycleSequence(), lastProcessedInputTick,
                    x, y, z, movedXa * 60f, movedYa * 60f, movedZa * 60f, rotation, movementState, lookY);
        }

        private void freeze() {
            input = null;
            pendingInputs.clear();
            savedInputSteps = 0;
            xa = ya = za = 0f;
            movedXa = movedYa = movedZa = 0f;
            nativeImpulseX = 0f;
            nativeImpulseY = 0f;
            nativeImpulseZ = 0f;
            jumpQueued = false;
            frozen = true;
            movementState = MovementState.IDLE;
        }

        private void resume() {
            frozen = false;
        }

        private boolean hasNativeImpulse() {
            return nativeImpulseX != 0f || nativeImpulseY != 0f || nativeImpulseZ != 0f;
        }
    }

    private static final class QueuedInput {
        private final MovementInputFrame input;
        private final long receivedHostTick;

        private QueuedInput(MovementInputFrame input, long receivedHostTick) {
            this.input = input;
            this.receivedHostTick = receivedHostTick;
        }
    }
}
