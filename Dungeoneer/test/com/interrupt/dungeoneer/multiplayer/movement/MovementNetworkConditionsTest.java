package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.host.HostDisconnectOutcome;
import com.interrupt.dungeoneer.multiplayer.host.HostPersistedState;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostTransitionOutcome;
import com.interrupt.dungeoneer.multiplayer.movement.LocalMovementReconciler.CorrectionStep;
import com.interrupt.dungeoneer.multiplayer.movement.LocalMovementReconciler.Reconciliation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshotInterpolator.InterpolatedMovementState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MovementNetworkConditionsTest {
    private static final float FIXED_DELTA = 1f / 60f;
    private static final ParticipantId PARTICIPANT_ID =
            new ParticipantId("campaign-slot-1");
    private static final NetworkEntityId ENTITY_ID = new NetworkEntityId(1L);
    private static final MovementEntityDescriptor DESCRIPTOR =
            new MovementEntityDescriptor(1L, ENTITY_ID, PARTICIPANT_ID,
                    1, "Participant", "humanoid-1");
    private static final HostSessionOutput NO_OUTPUT = new HostSessionOutput() {
        @Override public void event(HostSessionEvent event) { }
        @Override public void disconnect(HostDisconnectOutcome outcome) { }
        @Override public void transition(HostTransitionOutcome outcome) { }
        @Override public void persist(HostPersistedState state) { }
    };

    @Test
    public void predictionStaysSmoothAtOneHundredTwentyMillisecondRtt() {
        ScenarioResult result = runScenario(120);

        assertResponsiveAndAuthoritative(result);
        assertTrue("120 ms correction exceeded smooth threshold: "
                + result.maxCorrectionError,
                result.maxCorrectionError < LocalMovementReconciler.SNAP_DISTANCE);
        assertEquals(0, result.snapCount);
    }

    @Test
    public void predictionRemainsUsableAtTwoHundredMillisecondRtt() {
        ScenarioResult result = runScenario(200);

        assertResponsiveAndAuthoritative(result);
        assertTrue("200 ms correction exceeded safe threshold: "
                + result.maxCorrectionError,
                result.maxCorrectionError < LocalMovementReconciler.SNAP_DISTANCE);
        assertEquals(0, result.snapCount);
        assertTrue(result.pendingPredictionCount <= DirectConnectProtocol.MAX_INPUT_FRAMES * 2);
    }

    private void assertResponsiveAndAuthoritative(ScenarioResult result) {
        assertTrue("Local movement waited for Host round trip.", result.immediateLocalDistance > 0f);
        assertEquals("Host moved before delayed input arrived.",
                0f, result.immediateHostDistance, 0f);
        assertTrue("Input packet loss was not exercised.", result.droppedInputPackets > 0);
        assertTrue("Snapshot packet loss was not exercised.", result.droppedSnapshotPackets > 0);
        assertTrue("Packet duplication was not exercised.", result.duplicatedPackets > 0);
        assertTrue("Packet reordering was not exercised.", result.reorderedPackets > 0);
        assertTrue("Remote Avatar did not improve on raw snapshot stepping.",
                result.largestInterpolatedStep < result.largestRawSnapshotStep);
        assertTrue("Remote interpolation produced an unsafe visual jump: "
                + result.largestInterpolatedStep,
                result.largestInterpolatedStep < 0.2f);
        assertTrue("Prediction drift remained after replay and correction: "
                + result.finalPredictionDrift,
                result.finalPredictionDrift < 0.05f);
        assertTrue("Host stopped acknowledging continuous client input.",
                result.lastAcknowledgedInputTick >= 330L);
    }

    private ScenarioResult runScenario(int roundTripMilliseconds) {
        RectangularMovementCollisionWorld world =
                new RectangularMovementCollisionWorld(1000f, 1000f, 500f, 500f, 0.5f);
        AuthoritativeMovementSimulation host = new AuthoritativeMovementSimulation(
                world, Arrays.asList(DESCRIPTOR));
        AuthoritativeMovementSimulation predictor = new AuthoritativeMovementSimulation(
                world, Arrays.asList(DESCRIPTOR));
        MovementReplicationState replication = new MovementReplicationState();
        replication.applySpawn(DESCRIPTOR);
        LocalMovementReconciler reconciler = new LocalMovementReconciler();
        MovementSnapshotInterpolator interpolator = new MovementSnapshotInterpolator();
        HostileNetwork network = new HostileNetwork(roundTripMilliseconds);
        List<MovementInputFrame> unacknowledged = new ArrayList<MovementInputFrame>();

        MovementEntityState predicted = predictor.getState(PARTICIPANT_ID);
        float localX = predicted.getX();
        float localY = predicted.getY();
        float localZ = predicted.getZ();
        float spawnX = localX;
        float spawnY = localY;
        float spawnZ = localZ;
        float immediateLocalDistance = 0f;
        float immediateHostDistance = 0f;
        float maxCorrectionError = 0f;
        int snapCount = 0;
        float largestRawSnapshotStep = 0f;
        float lastRawX = 0f;
        float lastRawY = 0f;
        float lastRawZ = 0f;
        boolean rawObserved = false;
        float largestInterpolatedStep = 0f;
        float lastRemoteX = 0f;
        float lastRemoteY = 0f;
        float lastRemoteZ = 0f;
        boolean remoteObserved = false;
        long lastAcknowledgedInputTick = 0L;

        for(int tick = 1; tick <= 360; tick++) {
            MovementInputFrame input = input(tick);
            MovementEntityState beforePrediction = predicted;
            predictor.applyCommand(tick,
                    new MovementInputCommand(PARTICIPANT_ID, input), NO_OUTPUT);
            predictor.tick(tick, FIXED_DELTA, NO_OUTPUT);
            predicted = predictor.getState(PARTICIPANT_ID);
            float predictedDeltaX = predicted.getX() - beforePrediction.getX();
            float predictedDeltaY = predicted.getY() - beforePrediction.getY();
            float predictedDeltaZ = predicted.getZ() - beforePrediction.getZ();
            localX += predictedDeltaX;
            localY += predictedDeltaY;
            localZ += predictedDeltaZ;
            reconciler.addPrediction(input.getInputTick(), predictedDeltaX,
                    predictedDeltaY, predictedDeltaZ);
            unacknowledged.add(input);

            int firstBundled = Math.max(0,
                    unacknowledged.size() - DirectConnectProtocol.MAX_INPUT_FRAMES);
            network.sendInputs(tick, new ArrayList<MovementInputFrame>(
                    unacknowledged.subList(firstBundled, unacknowledged.size())));
            for(List<MovementInputFrame> packet : network.deliverInputs(tick)) {
                for(MovementInputFrame delivered : packet) {
                    host.applyCommand(tick,
                            new MovementInputCommand(PARTICIPANT_ID, delivered), NO_OUTPUT);
                }
            }

            host.tick(tick, FIXED_DELTA, NO_OUTPUT);
            if(tick == 1) {
                immediateLocalDistance = distance(localX, localY, localZ,
                        spawnX, spawnY, spawnZ);
                MovementEntityState hostState = host.getState(PARTICIPANT_ID);
                immediateHostDistance = distance(hostState.getX(), hostState.getY(),
                        hostState.getZ(), spawnX, spawnY, spawnZ);
            }
            if(tick % 3 == 0) {
                network.sendSnapshot(tick, (MovementSnapshot)host.snapshot(tick));
            }

            for(MovementSnapshot snapshot : network.deliverSnapshots(tick)) {
                if(!replication.applySnapshot(snapshot)) continue;
                MovementEntityState authoritative = snapshot.getEntity(ENTITY_ID);
                if(authoritative == null) continue;
                float rawStep = rawObserved ? distance(authoritative.getX(),
                        authoritative.getY(), authoritative.getZ(),
                        lastRawX, lastRawY, lastRawZ) : 0f;
                largestRawSnapshotStep = Math.max(largestRawSnapshotStep, rawStep);
                lastRawX = authoritative.getX();
                lastRawY = authoritative.getY();
                lastRawZ = authoritative.getZ();
                rawObserved = true;

                Reconciliation correction = reconciler.reconcile(authoritative,
                        localX, localY, localZ);
                maxCorrectionError = Math.max(maxCorrectionError,
                        correction.getErrorDistance());
                if(correction.isSnapRequired()) {
                    snapCount++;
                    localX = correction.getTargetX();
                    localY = correction.getTargetY();
                    localZ = correction.getTargetZ();
                }
                lastAcknowledgedInputTick = Math.max(lastAcknowledgedInputTick,
                        authoritative.getLastProcessedInputTick());
                while(!unacknowledged.isEmpty()
                        && unacknowledged.get(0).getInputTick()
                                <= authoritative.getLastProcessedInputTick()) {
                    unacknowledged.remove(0);
                }
            }

            CorrectionStep correction = reconciler.advance(FIXED_DELTA);
            localX += correction.getX();
            localY += correction.getY();
            localZ += correction.getZ();

            List<MovementSnapshot> snapshots = replication.getSnapshots();
            interpolator.advance(snapshots, FIXED_DELTA);
            InterpolatedMovementState remote = interpolator.sample(ENTITY_ID, snapshots);
            if(remote != null) {
                float remoteStep = remoteObserved ? distance(remote.getX(), remote.getY(),
                        remote.getZ(), lastRemoteX, lastRemoteY, lastRemoteZ) : 0f;
                largestInterpolatedStep = Math.max(largestInterpolatedStep, remoteStep);
                lastRemoteX = remote.getX();
                lastRemoteY = remote.getY();
                lastRemoteZ = remote.getZ();
                remoteObserved = true;
            }
        }

        return new ScenarioResult(immediateLocalDistance, immediateHostDistance,
                maxCorrectionError, snapCount, largestRawSnapshotStep,
                largestInterpolatedStep,
                distance(localX, localY, localZ,
                        predicted.getX(), predicted.getY(), predicted.getZ()),
                reconciler.getPendingInputCount(), lastAcknowledgedInputTick,
                network.droppedInputPackets, network.droppedSnapshotPackets,
                network.duplicatedPackets, network.reorderedPackets);
    }

    private MovementInputFrame input(long tick) {
        float forward = tick <= 150L ? 1f : 0f;
        float strafe = tick > 150L && tick <= 240L ? 1f : 0f;
        return new MovementInputFrame(tick, forward, strafe, 0f, tick == 60L);
    }

    private static float distance(float firstX, float firstY, float firstZ,
            float secondX, float secondY, float secondZ) {
        float x = firstX - secondX;
        float y = firstY - secondY;
        float z = firstZ - secondZ;
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static final class ScenarioResult {
        private final float immediateLocalDistance;
        private final float immediateHostDistance;
        private final float maxCorrectionError;
        private final int snapCount;
        private final float largestRawSnapshotStep;
        private final float largestInterpolatedStep;
        private final float finalPredictionDrift;
        private final int pendingPredictionCount;
        private final long lastAcknowledgedInputTick;
        private final int droppedInputPackets;
        private final int droppedSnapshotPackets;
        private final int duplicatedPackets;
        private final int reorderedPackets;

        private ScenarioResult(float immediateLocalDistance, float immediateHostDistance,
                float maxCorrectionError, int snapCount, float largestRawSnapshotStep,
                float largestInterpolatedStep, float finalPredictionDrift,
                int pendingPredictionCount, long lastAcknowledgedInputTick,
                int droppedInputPackets, int droppedSnapshotPackets,
                int duplicatedPackets, int reorderedPackets) {
            this.immediateLocalDistance = immediateLocalDistance;
            this.immediateHostDistance = immediateHostDistance;
            this.maxCorrectionError = maxCorrectionError;
            this.snapCount = snapCount;
            this.largestRawSnapshotStep = largestRawSnapshotStep;
            this.largestInterpolatedStep = largestInterpolatedStep;
            this.finalPredictionDrift = finalPredictionDrift;
            this.pendingPredictionCount = pendingPredictionCount;
            this.lastAcknowledgedInputTick = lastAcknowledgedInputTick;
            this.droppedInputPackets = droppedInputPackets;
            this.droppedSnapshotPackets = droppedSnapshotPackets;
            this.duplicatedPackets = duplicatedPackets;
            this.reorderedPackets = reorderedPackets;
        }
    }

    private static final class HostileNetwork {
        private static final int[] JITTER_TICKS = { 2, -2, 1, 0, -1, 3, -2, 0 };

        private final int oneWayDelayTicks;
        private final List<ScheduledInputs> inputs = new ArrayList<ScheduledInputs>();
        private final List<ScheduledSnapshot> snapshots = new ArrayList<ScheduledSnapshot>();
        private int nextInputSequence;
        private int nextSnapshotSequence;
        private int highestDeliveredInputSequence;
        private int highestDeliveredSnapshotSequence;
        private long insertionSequence;
        private int droppedInputPackets;
        private int droppedSnapshotPackets;
        private int duplicatedPackets;
        private int reorderedPackets;

        private HostileNetwork(int roundTripMilliseconds) {
            oneWayDelayTicks = Math.max(1,
                    Math.round(roundTripMilliseconds * 60f / 2000f));
        }

        private void sendInputs(int now, List<MovementInputFrame> frames) {
            int sequence = ++nextInputSequence;
            if(sequence % 11 == 0) {
                droppedInputPackets++;
                return;
            }
            int deliveryTick = deliveryTick(now, sequence);
            inputs.add(new ScheduledInputs(deliveryTick, sequence,
                    ++insertionSequence, frames));
            if(sequence % 13 == 0) {
                inputs.add(new ScheduledInputs(deliveryTick + 1, sequence,
                        ++insertionSequence, frames));
                duplicatedPackets++;
            }
        }

        private void sendSnapshot(int now, MovementSnapshot snapshot) {
            int sequence = ++nextSnapshotSequence;
            if(sequence % 9 == 0) {
                droppedSnapshotPackets++;
                return;
            }
            int deliveryTick = deliveryTick(now, sequence);
            snapshots.add(new ScheduledSnapshot(deliveryTick, sequence,
                    ++insertionSequence, snapshot));
            if(sequence % 11 == 0) {
                snapshots.add(new ScheduledSnapshot(deliveryTick + 1, sequence,
                        ++insertionSequence, snapshot));
                duplicatedPackets++;
            }
        }

        private List<List<MovementInputFrame>> deliverInputs(int now) {
            List<ScheduledInputs> due = new ArrayList<ScheduledInputs>();
            for(Iterator<ScheduledInputs> iterator = inputs.iterator(); iterator.hasNext();) {
                ScheduledInputs packet = iterator.next();
                if(packet.deliveryTick <= now) {
                    due.add(packet);
                    iterator.remove();
                }
            }
            Collections.sort(due, ScheduledPacket.ORDER);
            List<List<MovementInputFrame>> delivered =
                    new ArrayList<List<MovementInputFrame>>();
            for(ScheduledInputs packet : due) {
                if(packet.packetSequence < highestDeliveredInputSequence) reorderedPackets++;
                highestDeliveredInputSequence = Math.max(highestDeliveredInputSequence,
                        packet.packetSequence);
                delivered.add(packet.frames);
            }
            return delivered;
        }

        private List<MovementSnapshot> deliverSnapshots(int now) {
            List<ScheduledSnapshot> due = new ArrayList<ScheduledSnapshot>();
            for(Iterator<ScheduledSnapshot> iterator = snapshots.iterator(); iterator.hasNext();) {
                ScheduledSnapshot packet = iterator.next();
                if(packet.deliveryTick <= now) {
                    due.add(packet);
                    iterator.remove();
                }
            }
            Collections.sort(due, ScheduledPacket.ORDER);
            List<MovementSnapshot> delivered = new ArrayList<MovementSnapshot>();
            for(ScheduledSnapshot packet : due) {
                if(packet.packetSequence < highestDeliveredSnapshotSequence) reorderedPackets++;
                highestDeliveredSnapshotSequence = Math.max(highestDeliveredSnapshotSequence,
                        packet.packetSequence);
                delivered.add(packet.snapshot);
            }
            return delivered;
        }

        private int deliveryTick(int now, int sequence) {
            int jitter = JITTER_TICKS[(sequence - 1) % JITTER_TICKS.length];
            return Math.max(now + 1, now + oneWayDelayTicks + jitter);
        }
    }

    private abstract static class ScheduledPacket {
        private static final Comparator<ScheduledPacket> ORDER =
                new Comparator<ScheduledPacket>() {
                    @Override
                    public int compare(ScheduledPacket first, ScheduledPacket second) {
                        if(first.deliveryTick != second.deliveryTick) {
                            return first.deliveryTick - second.deliveryTick;
                        }
                        return first.insertionSequence < second.insertionSequence ? -1
                                : first.insertionSequence == second.insertionSequence ? 0 : 1;
                    }
                };

        final int deliveryTick;
        final int packetSequence;
        final long insertionSequence;

        private ScheduledPacket(int deliveryTick, int packetSequence,
                long insertionSequence) {
            this.deliveryTick = deliveryTick;
            this.packetSequence = packetSequence;
            this.insertionSequence = insertionSequence;
        }
    }

    private static final class ScheduledInputs extends ScheduledPacket {
        private final List<MovementInputFrame> frames;

        private ScheduledInputs(int deliveryTick, int packetSequence,
                long insertionSequence, List<MovementInputFrame> frames) {
            super(deliveryTick, packetSequence, insertionSequence);
            this.frames = new ArrayList<MovementInputFrame>(frames);
        }
    }

    private static final class ScheduledSnapshot extends ScheduledPacket {
        private final MovementSnapshot snapshot;

        private ScheduledSnapshot(int deliveryTick, int packetSequence,
                long insertionSequence, MovementSnapshot snapshot) {
            super(deliveryTick, packetSequence, insertionSequence);
            this.snapshot = snapshot;
        }
    }
}
