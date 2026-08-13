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

/** Fixed-step Host authority for movement on one Active Floor. */
public final class AuthoritativeMovementSimulation implements AuthoritativeHostSimulation {
    public static final float MAX_SPEED = 2.4f;
    public static final long MAX_INPUT_TICK_LEAD = 600L;

    private static final float ACCELERATION = 18f;
    private static final float DECELERATION = 14f;
    private static final float JUMP_SPEED = 3.2f;
    private static final float GRAVITY = 9.8f;
    private static final float MOVING_EPSILON = 0.01f;

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

    @Override
    public synchronized void applyCommand(long hostTick, HostSessionCommand command,
            HostSessionOutput output) {
        if(!(command instanceof MovementInputCommand)) {
            throw new IllegalArgumentException("Unsupported movement Host command: " + command);
        }
        MovementInputCommand movement = (MovementInputCommand)command;
        MutableMovement participant = participants.get(movement.getParticipantId());
        if(participant == null) return;
        MovementInputFrame input = movement.getInput();
        if(input.getInputTick() <= participant.lastProcessedInputTick) return;
        if(input.getInputTick() - participant.lastProcessedInputTick > MAX_INPUT_TICK_LEAD) return;
        boolean jumpWasPressed = participant.input != null
                && participant.input.isJump();
        if(input.isJump() && !jumpWasPressed) participant.jumpQueued = true;
        participant.input = input;
        participant.lastProcessedInputTick = input.getInputTick();
    }

    @Override
    public synchronized void tick(long hostTick, float fixedDeltaSeconds,
            HostSessionOutput output) {
        for(MutableMovement participant : participants.values()) {
            step(participant, fixedDeltaSeconds);
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

    public synchronized MovementEntityState getState(ParticipantId participantId) {
        MutableMovement participant = participants.get(participantId);
        return participant == null ? null : participant.snapshot();
    }

    private void step(MutableMovement participant, float deltaSeconds) {
        MovementInputFrame input = participant.input;
        float forward = input == null ? 0f : input.getForward();
        float strafe = input == null ? 0f : input.getStrafe();
        float rotation = input == null ? participant.rotation : normalizeRotation(input.getRotation());

        float length = (float)Math.sqrt(forward * forward + strafe * strafe);
        if(length > 1f) {
            forward /= length;
            strafe /= length;
        }
        if(forward < 0f) {
            forward *= 0.5f;
            strafe *= 0.8f;
        }

        float targetVelocityX = (float)(strafe * Math.cos(rotation)
                + forward * Math.sin(rotation)) * MAX_SPEED;
        float targetVelocityY = (float)(forward * Math.cos(rotation)
                - strafe * Math.sin(rotation)) * MAX_SPEED;
        float rate = Math.abs(forward) + Math.abs(strafe) > 0f
                ? ACCELERATION : DECELERATION;
        float blend = Math.min(1f, rate * deltaSeconds);
        participant.velocityX += (targetVelocityX - participant.velocityX) * blend;
        participant.velocityY += (targetVelocityY - participant.velocityY) * blend;
        participant.rotation = rotation;

        boolean requestedMovement = Math.abs(targetVelocityX) + Math.abs(targetVelocityY)
                > MOVING_EPSILON;
        boolean moved = false;
        float nextX = participant.x + participant.velocityX * deltaSeconds;
        if(world.canOccupy(nextX, participant.y, participant.z)) {
            moved |= Math.abs(nextX - participant.x) > MOVING_EPSILON * deltaSeconds;
            participant.x = nextX;
        }
        else {
            participant.velocityX = 0f;
        }

        float nextY = participant.y + participant.velocityY * deltaSeconds;
        if(world.canOccupy(participant.x, nextY, participant.z)) {
            moved |= Math.abs(nextY - participant.y) > MOVING_EPSILON * deltaSeconds;
            participant.y = nextY;
        }
        else {
            participant.velocityY = 0f;
        }

        if(participant.jumpQueued && participant.onFloor) {
            participant.velocityZ = JUMP_SPEED;
            participant.onFloor = false;
        }
        participant.jumpQueued = false;

        if(!participant.onFloor) participant.velocityZ -= GRAVITY * deltaSeconds;
        float floorZ = world.getFloorZ(participant.x, participant.y, participant.z);
        float nextZ = participant.z + participant.velocityZ * deltaSeconds;
        if(nextZ <= floorZ) {
            participant.z = floorZ;
            participant.velocityZ = 0f;
            participant.onFloor = true;
        }
        else if(world.canOccupy(participant.x, participant.y, nextZ)) {
            participant.z = nextZ;
            participant.onFloor = false;
        }
        else {
            participant.velocityZ = 0f;
        }

        if(!participant.onFloor) participant.movementState = MovementState.AIRBORNE;
        else if(requestedMovement && !moved) participant.movementState = MovementState.BLOCKED;
        else if(moved || Math.abs(participant.velocityX) + Math.abs(participant.velocityY)
                > MOVING_EPSILON) participant.movementState = MovementState.MOVING;
        else participant.movementState = MovementState.IDLE;
    }

    private static float normalizeRotation(float rotation) {
        float fullTurn = (float)(Math.PI * 2.0);
        while(rotation > Math.PI) rotation -= fullTurn;
        while(rotation < -Math.PI) rotation += fullTurn;
        return rotation;
    }

    private static final class MutableMovement {
        private final MovementEntityDescriptor descriptor;
        private MovementInputFrame input;
        private long lastProcessedInputTick;
        private float x;
        private float y;
        private float z;
        private float velocityX;
        private float velocityY;
        private float velocityZ;
        private float rotation;
        private boolean onFloor = true;
        private boolean jumpQueued;
        private MovementState movementState = MovementState.IDLE;

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
                    x, y, z, velocityX, velocityY, velocityZ, rotation, movementState);
        }
    }
}
