package com.interrupt.dungeoneer.multiplayer.lives;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-owned Lives, Downed bleedout and Revival for every Campaign Slot in play.
 * Time only advances through {@link #tick()}, which Host calls once per unpaused Host tick.
 */
public final class AuthoritativeLives {
    public static final int MINIMUM_STARTING_LIVES = 1;
    public static final int MAXIMUM_STARTING_LIVES = 5;
    public static final int DEFAULT_STARTING_LIVES = 3;
    public static final int BLEEDOUT_TICKS = 600;
    public static final int REVIVAL_TICKS = 180;
    public static final int REVIVAL_HEALTH_PERCENT = 25;
    public static final int RESPAWN_HEALTH_PERCENT = 50;

    public enum Condition { STANDING, DOWNED, EXHAUSTED }

    public enum OutcomeKind { REVIVED, RESPAWNED, EXHAUSTED }

    public static final class Outcome {
        public final OutcomeKind kind;
        public final ParticipantId participant;
        /** Reviver for REVIVED; otherwise null. */
        public final ParticipantId reviver;

        private Outcome(OutcomeKind kind, ParticipantId participant, ParticipantId reviver) {
            this.kind = kind;
            this.participant = participant;
            this.reviver = reviver;
        }
    }

    private final Map<ParticipantId, MutableSlot> slots =
            new LinkedHashMap<ParticipantId, MutableSlot>();
    private long revision = 1L;

    public AuthoritativeLives(int startingLives, Collection<ParticipantId> participants) {
        requireStartingLives(startingLives);
        if(participants == null || participants.isEmpty() || participants.size() > 4) {
            throw new IllegalArgumentException("Lives need one to four Participants.");
        }
        for(ParticipantId participant : participants) {
            if(participant == null || slots.containsKey(participant)) {
                throw new IllegalArgumentException("Lives Participants must be unique.");
            }
            slots.put(participant, new MutableSlot(startingLives));
        }
    }

    public static void requireStartingLives(int startingLives) {
        if(startingLives < MINIMUM_STARTING_LIVES || startingLives > MAXIMUM_STARTING_LIVES) {
            throw new IllegalArgumentException("Starting Lives must be 1-5.");
        }
    }

    /** Rounded up so any positive maximum returns at least one health. */
    public static int healthPercent(int maximumHealth, int percent) {
        return Math.max(1, (int)((maximumHealth * (long)percent + 99L) / 100L));
    }

    /** Zero health reached. Also ends any Revival this Participant was performing. */
    public synchronized boolean down(ParticipantId participant) {
        MutableSlot slot = slots.get(participant);
        if(slot == null || slot.condition != Condition.STANDING) return false;
        cancelRevival(participant);
        slot.condition = Condition.DOWNED;
        slot.bleedoutTicks = BLEEDOUT_TICKS;
        revision++;
        return true;
    }

    /** Restarting an identical Revival keeps its progress; one reviver serves one target. */
    public synchronized boolean beginRevival(ParticipantId reviver, ParticipantId target) {
        MutableSlot reviving = slots.get(reviver);
        MutableSlot downed = slots.get(target);
        if(reviving == null || downed == null || reviver.equals(target)
                || reviving.condition != Condition.STANDING
                || downed.condition != Condition.DOWNED) return false;
        if(reviver.equals(downed.reviver)) return true;
        if(downed.reviver != null) return false;
        cancelRevival(reviver);
        downed.reviver = reviver;
        downed.revivalTicks = REVIVAL_TICKS;
        revision++;
        return true;
    }

    /** Release, movement, damage, distance or lost line of sight; progress is discarded. */
    public synchronized boolean cancelRevival(ParticipantId reviver) {
        boolean cancelled = false;
        for(MutableSlot slot : slots.values()) {
            if(reviver == null || !reviver.equals(slot.reviver)) continue;
            slot.reviver = null;
            slot.revivalTicks = 0;
            cancelled = true;
        }
        if(cancelled) revision++;
        return cancelled;
    }

    /** One unpaused Host tick. Bleedout holds while an uninterrupted Revival is in progress. */
    public synchronized List<Outcome> tick() {
        List<Outcome> outcomes = new ArrayList<Outcome>();
        for(Map.Entry<ParticipantId, MutableSlot> entry : slots.entrySet()) {
            MutableSlot slot = entry.getValue();
            if(slot.condition != Condition.DOWNED) continue;
            if(slot.reviver != null) {
                if(--slot.revivalTicks > 0) continue;
                ParticipantId reviver = slot.reviver;
                slot.stand();
                outcomes.add(new Outcome(OutcomeKind.REVIVED, entry.getKey(), reviver));
            }
            else {
                if(--slot.bleedoutTicks > 0) continue;
                slot.stand();
                slot.lives--;
                if(slot.lives > 0) {
                    outcomes.add(new Outcome(OutcomeKind.RESPAWNED, entry.getKey(), null));
                }
                else {
                    slot.condition = Condition.EXHAUSTED;
                    outcomes.add(new Outcome(OutcomeKind.EXHAUSTED, entry.getKey(), null));
                }
            }
            revision++;
        }
        return outcomes;
    }

    public synchronized Condition getCondition(ParticipantId participant) {
        MutableSlot slot = slots.get(participant);
        return slot == null ? null : slot.condition;
    }

    public synchronized boolean isStanding(ParticipantId participant) {
        return getCondition(participant) == Condition.STANDING;
    }

    public synchronized int getRemainingLives(ParticipantId participant) {
        MutableSlot slot = slots.get(participant);
        return slot == null ? 0 : slot.lives;
    }

    public synchronized int getBleedoutTicks(ParticipantId participant) {
        MutableSlot slot = slots.get(participant);
        return slot == null ? 0 : slot.bleedoutTicks;
    }

    public synchronized int getRevivalTicks(ParticipantId participant) {
        MutableSlot slot = slots.get(participant);
        return slot == null ? 0 : slot.revivalTicks;
    }

    public synchronized ParticipantId getReviver(ParticipantId participant) {
        MutableSlot slot = slots.get(participant);
        return slot == null ? null : slot.reviver;
    }

    /** Downed Participant this reviver is currently reviving, or null. */
    public synchronized ParticipantId getRevivalTarget(ParticipantId reviver) {
        for(Map.Entry<ParticipantId, MutableSlot> entry : slots.entrySet()) {
            if(reviver != null && reviver.equals(entry.getValue().reviver)) return entry.getKey();
        }
        return null;
    }

    /** Changes on every transition, never on plain countdown. */
    public synchronized long getRevision() {
        return revision;
    }

    private static final class MutableSlot {
        private int lives;
        private Condition condition = Condition.STANDING;
        private int bleedoutTicks;
        private int revivalTicks;
        private ParticipantId reviver;

        private MutableSlot(int lives) {
            this.lives = lives;
        }

        private void stand() {
            condition = Condition.STANDING;
            bleedoutTicks = 0;
            revivalTicks = 0;
            reviver = null;
        }
    }
}
