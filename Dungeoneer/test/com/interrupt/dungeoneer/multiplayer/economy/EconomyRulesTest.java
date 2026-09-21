package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class EconomyRulesTest {
    private final ParticipantId near = new ParticipantId("campaign-slot-1");
    private final ParticipantId far = new ParticipantId("campaign-slot-2");
    private final ParticipantId downed = new ParticipantId("campaign-slot-3");
    private final ParticipantId sniper = new ParticipantId("campaign-slot-4");

    @Test
    public void nearbyLivingParticipantsAndLivingKillerShareExperience() {
        List<ParticipantId> recipients = EconomyRules.experienceRecipients(10f, 10f, Arrays.asList(
                new EconomyRules.Candidate(near, 14f, 10f, true),
                new EconomyRules.Candidate(far, 40f, 10f, true),
                new EconomyRules.Candidate(downed, 10f, 11f, false),
                new EconomyRules.Candidate(sniper, 60f, 60f, true)), sniper);

        assertEquals(Arrays.asList(near, sniper), recipients);
    }

    @Test
    public void edgeOfNativeDeathSoundRadiusStillCounts() {
        List<ParticipantId> recipients = EconomyRules.experienceRecipients(0f, 0f, Collections.singletonList(
                new EconomyRules.Candidate(near, EconomyRules.SHARED_EXPERIENCE_RADIUS, 0f, true)), null);

        assertEquals(Collections.singletonList(near), recipients);
    }

    @Test
    public void unattendedDeathDoesNotAwardDistantParticipant() {
        List<ParticipantId> recipients = EconomyRules.experienceRecipients(0f, 0f, Arrays.asList(
                new EconomyRules.Candidate(far, 80f, 0f, true),
                new EconomyRules.Candidate(near, 30f, 0f, true),
                new EconomyRules.Candidate(downed, 1f, 0f, false)), downed);

        assertTrue(recipients.isEmpty());
    }

    @Test
    public void nobodyLivingReceivesNoExperience() {
        assertTrue(EconomyRules.experienceRecipients(0f, 0f, Collections.singletonList(
                new EconomyRules.Candidate(downed, 0f, 0f, false)), downed).isEmpty());
    }

    @Test
    public void lootRollSelectsExactlyOneListedLivingParticipant() {
        List<ParticipantId> living = Arrays.asList(near, far);
        Random random = new Random(17L);
        boolean sawNear = false, sawFar = false;
        for(int i = 0; i < 200; i++) {
            ParticipantId selected = EconomyRules.selectLootParticipant(living, random);
            assertTrue(living.contains(selected));
            sawNear |= near.equals(selected);
            sawFar |= far.equals(selected);
        }
        assertTrue(sawNear && sawFar);
        assertNull(EconomyRules.selectLootParticipant(Collections.<ParticipantId>emptyList(), random));
    }
}
