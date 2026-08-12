package com.interrupt.dungeoneer.multiplayer.host;

/** Advances all ticks made available by a monotonic controlled clock. */
public final class FixedStepHostSessionRunner {
    private final AuthoritativeHostSession session;
    private final HostSessionClock clock;

    public FixedStepHostSessionRunner(AuthoritativeHostSession session, HostSessionClock clock) {
        if(session == null) throw new IllegalArgumentException("Host session cannot be null.");
        if(clock == null) throw new IllegalArgumentException("Host clock cannot be null.");
        this.session = session;
        this.clock = clock;
    }

    public long runPendingTicks() {
        long targetTick = clock.getTargetTick();
        long currentTick = session.getHostTick();
        if(targetTick < currentTick) {
            throw new IllegalStateException("Host clock moved backwards from tick " + currentTick
                    + " to " + targetTick + ".");
        }

        while(session.getHostTick() < targetTick) {
            session.advanceOneTick();
        }
        return targetTick - currentTick;
    }
}
