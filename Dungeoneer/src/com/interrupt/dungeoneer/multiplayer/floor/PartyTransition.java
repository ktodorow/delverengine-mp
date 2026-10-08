package com.interrupt.dungeoneer.multiplayer.floor;

/** Reliable Host-authored travel state. Time advances only on unpaused Host ticks. */
public final class PartyTransition {
    public enum Phase { IDLE, GATHERING, COUNTDOWN, LOADING }
    public static final int COUNTDOWN_TICKS = 180;
    public static final float GATHER_RADIUS = 3f;
    public final long sequence, generation;
    public final Phase phase;
    public final String portal;
    public final int remainingTicks;

    public PartyTransition(long sequence, long generation, Phase phase, String portal, int remainingTicks) {
        if(sequence < 1L || generation < 1L || phase == null || portal == null
                || portal.length() > 256 || remainingTicks < 0 || remainingTicks > COUNTDOWN_TICKS
                || (phase == Phase.IDLE) != portal.isEmpty())
            throw new IllegalArgumentException("Invalid Party Transition.");
        this.sequence = sequence; this.generation = generation; this.phase = phase;
        this.portal = portal; this.remainingTicks = remainingTicks;
    }

    public static PartyTransition idle(long sequence, long generation) {
        return new PartyTransition(sequence, generation, Phase.IDLE, "", 0);
    }
}
