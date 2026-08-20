package com.interrupt.dungeoneer.multiplayer.communication;

/** Host-owned shared-time state. Sequence zero represents initial unpaused state. */
public final class PauseSessionState {
    private final long sequence;
    private final boolean paused;

    public PauseSessionState(long sequence, boolean paused) {
        if(sequence < 0L) throw new IllegalArgumentException("Pause Session sequence cannot be negative.");
        this.sequence = sequence;
        this.paused = paused;
    }

    public long getSequence() { return sequence; }
    public boolean isPaused() { return paused; }
}
