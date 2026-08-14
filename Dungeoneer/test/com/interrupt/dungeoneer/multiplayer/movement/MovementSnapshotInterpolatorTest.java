package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshotInterpolator.InterpolatedMovementState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MovementSnapshotInterpolatorTest {
    private static final NetworkEntityId ENTITY_ID = new NetworkEntityId(2L);

    @Test
    public void renderClockMovesBetweenTwentyHertzSnapshotsEveryFrame() {
        MovementSnapshotInterpolator interpolator = new MovementSnapshotInterpolator();
        List<MovementSnapshot> snapshots = snapshots(3L, 6L, 9L, 12L, 15L, 18L);

        interpolator.advance(snapshots, 0f);
        assertEquals(12d, interpolator.getRenderHostTick(), 0d);
        assertEquals(12f, interpolator.sample(ENTITY_ID, snapshots).getX(), 0f);

        interpolator.advance(snapshots, 1f / 60f);
        assertEquals(13d, interpolator.getRenderHostTick(), 0.0001d);
        assertEquals(13f, interpolator.sample(ENTITY_ID, snapshots).getX(), 0.0001f);
    }

    @Test
    public void missingSnapshotStillInterpolatesAcrossBufferedGap() {
        MovementSnapshotInterpolator interpolator = new MovementSnapshotInterpolator();
        List<MovementSnapshot> snapshots = snapshots(3L, 6L, 12L, 15L);

        interpolator.advance(snapshots, 0f);
        InterpolatedMovementState state = interpolator.sample(ENTITY_ID, snapshots);

        assertEquals(9d, interpolator.getRenderHostTick(), 0d);
        assertEquals(9f, state.getX(), 0.0001f);
    }

    @Test
    public void interpolationHoldsLatestStateDuringLongSnapshotLoss() {
        MovementSnapshotInterpolator interpolator = new MovementSnapshotInterpolator();
        List<MovementSnapshot> snapshots = snapshots(3L, 6L, 9L);

        interpolator.advance(snapshots, 0f);
        for(int frame = 0; frame < 30; frame++) {
            interpolator.advance(snapshots, 1f / 60f);
        }

        assertEquals(9d, interpolator.getRenderHostTick(), 0d);
        assertEquals(9f, interpolator.sample(ENTITY_ID, snapshots).getX(), 0f);
    }

    private List<MovementSnapshot> snapshots(long... hostTicks) {
        List<MovementSnapshot> snapshots = new ArrayList<MovementSnapshot>();
        for(long hostTick : hostTicks) {
            MovementEntityState state = new MovementEntityState(ENTITY_ID, 1L, hostTick,
                    hostTick, 2f, 0.5f, 1f, 0f, 0f, 0f, MovementState.MOVING);
            snapshots.add(new MovementSnapshot(hostTick, hostTick, Arrays.asList(state)));
        }
        return snapshots;
    }
}
