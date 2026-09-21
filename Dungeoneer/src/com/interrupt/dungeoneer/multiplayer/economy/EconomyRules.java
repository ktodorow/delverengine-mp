package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pure cooperative reward rules layered over unchanged v1.08 reward values. */
public final class EconomyRules {
    /** v1.08 Monster.die plays its death sound with a 12 tile radius; hearing it shares the kill. */
    public static final float SHARED_EXPERIENCE_RADIUS = 12f;

    public static final class Candidate {
        public final ParticipantId participantId;
        public final float x, y;
        public final boolean living;

        public Candidate(ParticipantId participantId, float x, float y, boolean living) {
            if(participantId == null) throw new IllegalArgumentException("Candidate requires a Participant.");
            this.participantId = participantId;
            this.x = x;
            this.y = y;
            this.living = living;
        }
    }

    private EconomyRules() { }

    /** Living Participants within earshot plus a living killer receive the full native award. */
    public static List<ParticipantId> experienceRecipients(float x, float y,
            List<Candidate> candidates, ParticipantId killer) {
        List<ParticipantId> recipients = new ArrayList<ParticipantId>();
        float radius = SHARED_EXPERIENCE_RADIUS * SHARED_EXPERIENCE_RADIUS;
        for(Candidate candidate : candidates) {
            if(!candidate.living) continue;
            float dx = candidate.x - x, dy = candidate.y - y;
            float distance = dx * dx + dy * dy;
            if(distance <= radius || candidate.participantId.equals(killer)) {
                if(!recipients.contains(candidate.participantId)) recipients.add(candidate.participantId);
            }
        }
        return Collections.unmodifiableList(recipients);
    }

    /** One living Participant for one stat-biased loot roll; null leaves native local stats. */
    public static ParticipantId selectLootParticipant(List<ParticipantId> living, Random random) {
        if(living == null || living.isEmpty()) return null;
        return living.get(random.nextInt(living.size()));
    }
}
