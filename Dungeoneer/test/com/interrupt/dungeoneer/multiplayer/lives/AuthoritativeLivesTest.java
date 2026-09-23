package com.interrupt.dungeoneer.multiplayer.lives;

import com.interrupt.dungeoneer.multiplayer.lives.AuthoritativeLives.Condition;
import com.interrupt.dungeoneer.multiplayer.lives.AuthoritativeLives.Outcome;
import com.interrupt.dungeoneer.multiplayer.lives.AuthoritativeLives.OutcomeKind;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AuthoritativeLivesTest {
    private final ParticipantId alpha = new ParticipantId("campaign-slot-1");
    private final ParticipantId beta = new ParticipantId("campaign-slot-2");
    private final ParticipantId gamma = new ParticipantId("campaign-slot-3");

    private AuthoritativeLives lives(int startingLives) {
        return new AuthoritativeLives(startingLives, Arrays.asList(alpha, beta, gamma));
    }

    private static List<Outcome> advance(AuthoritativeLives lives, int ticks) {
        List<Outcome> outcomes = new ArrayList<Outcome>();
        for(int tick = 0; tick < ticks; tick++) outcomes.addAll(lives.tick());
        return outcomes;
    }

    @Test
    public void everySlotStartsWithSameIndependentlyTrackedLives() {
        AuthoritativeLives lives = lives(4);
        lives.down(alpha);
        advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);

        assertEquals(3, lives.getRemainingLives(alpha));
        assertEquals(4, lives.getRemainingLives(beta));
        assertEquals(4, lives.getRemainingLives(gamma));
    }

    @Test(expected = IllegalArgumentException.class)
    public void startingLivesBelowOneAreRejected() {
        lives(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void startingLivesAboveFiveAreRejected() {
        lives(6);
    }

    @Test
    public void bleedoutLastsTenSecondsOfHostTicksThenConsumesLifeAndRespawns() {
        AuthoritativeLives lives = lives(3);
        assertTrue(lives.down(alpha));
        assertEquals(Condition.DOWNED, lives.getCondition(alpha));

        assertTrue(advance(lives, AuthoritativeLives.BLEEDOUT_TICKS - 1).isEmpty());
        assertEquals(3, lives.getRemainingLives(alpha));
        List<Outcome> outcomes = advance(lives, 1);

        assertEquals(1, outcomes.size());
        assertEquals(OutcomeKind.RESPAWNED, outcomes.get(0).kind);
        assertEquals(alpha, outcomes.get(0).participant);
        assertEquals(2, lives.getRemainingLives(alpha));
        assertEquals(Condition.STANDING, lives.getCondition(alpha));
    }

    @Test
    public void pausedSessionNeverTicksSoBleedoutHolds() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        advance(lives, 100);
        // Host skips tick() entirely while Pause Session is active.
        assertEquals(AuthoritativeLives.BLEEDOUT_TICKS - 100, lives.getBleedoutTicks(alpha));
        assertEquals(Condition.DOWNED, lives.getCondition(alpha));
    }

    @Test
    public void uninterruptedThreeSecondRevivalRestoresWithoutLifeLoss() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        assertTrue(lives.beginRevival(beta, alpha));

        assertTrue(advance(lives, AuthoritativeLives.REVIVAL_TICKS - 1).isEmpty());
        List<Outcome> outcomes = advance(lives, 1);

        assertEquals(1, outcomes.size());
        assertEquals(OutcomeKind.REVIVED, outcomes.get(0).kind);
        assertEquals(beta, outcomes.get(0).reviver);
        assertEquals(3, lives.getRemainingLives(alpha));
        assertEquals(Condition.STANDING, lives.getCondition(alpha));
    }

    @Test
    public void interruptedRevivalDiscardsProgressAndBleedoutResumes() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        advance(lives, 60);
        lives.beginRevival(beta, alpha);
        advance(lives, AuthoritativeLives.REVIVAL_TICKS - 1);

        assertTrue(lives.cancelRevival(beta));
        assertNull(lives.getReviver(alpha));
        assertEquals(AuthoritativeLives.BLEEDOUT_TICKS - 60, lives.getBleedoutTicks(alpha));

        lives.beginRevival(beta, alpha);
        assertEquals(AuthoritativeLives.REVIVAL_TICKS, lives.getRevivalTicks(alpha));
    }

    @Test
    public void repeatedIntentForSameRevivalKeepsProgress() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        lives.beginRevival(beta, alpha);
        advance(lives, 90);

        assertTrue(lives.beginRevival(beta, alpha));
        assertEquals(AuthoritativeLives.REVIVAL_TICKS - 90, lives.getRevivalTicks(alpha));
    }

    @Test
    public void onlyStandingTeammateMayReviveAndOnlyOneAtATime() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        lives.down(beta);

        assertFalse(lives.beginRevival(beta, alpha));
        assertFalse(lives.beginRevival(alpha, alpha));
        assertFalse(lives.beginRevival(gamma, gamma));
        assertTrue(lives.beginRevival(gamma, alpha));
        // Turning to another Downed teammate abandons first Revival.
        assertTrue(lives.beginRevival(gamma, beta));
        assertNull(lives.getReviver(alpha));
        assertEquals(beta, lives.getRevivalTarget(gamma));
    }

    @Test
    public void reviverWhoGoesDownStopsReviving() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        lives.beginRevival(beta, alpha);

        lives.down(beta);

        assertNull(lives.getReviver(alpha));
        assertEquals(0, lives.getRevivalTicks(alpha));
    }

    @Test
    public void lastLifeBleedoutExhaustsSlotPermanently() {
        AuthoritativeLives lives = lives(1);
        lives.down(alpha);

        List<Outcome> outcomes = advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);

        assertEquals(OutcomeKind.EXHAUSTED, outcomes.get(0).kind);
        assertEquals(0, lives.getRemainingLives(alpha));
        assertEquals(Condition.EXHAUSTED, lives.getCondition(alpha));
        assertFalse(lives.down(alpha));
        assertFalse(lives.beginRevival(beta, alpha));
        assertFalse(lives.beginRevival(alpha, beta));
    }

    @Test
    public void simultaneousDowningResolvesBleedoutsImmediatelyAndRespawnsTogether() {
        AuthoritativeLives lives = lives(2);
        lives.down(alpha);
        advance(lives, 200);
        lives.down(beta);
        assertFalse(lives.collapseBleedouts(Arrays.asList(alpha, beta, gamma)));

        lives.down(gamma);
        assertTrue(lives.collapseBleedouts(Arrays.asList(alpha, beta, gamma)));
        List<Outcome> outcomes = advance(lives, 1);

        assertEquals(3, outcomes.size());
        for(Outcome outcome : outcomes) assertEquals(OutcomeKind.RESPAWNED, outcome.kind);
        assertEquals(1, lives.getRemainingLives(alpha));
        assertFalse(lives.isPartyWiped());
    }

    @Test
    public void absentSlotsDoNotCountAsStandingForSimultaneousDowning() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        // gamma is disconnected and returned to its Campaign Slot; beta stands.
        assertFalse(lives.collapseBleedouts(Arrays.asList(alpha, beta)));
        lives.down(beta);
        assertTrue(lives.collapseBleedouts(Arrays.asList(alpha, beta)));
        assertEquals(2, advance(lives, 1).size());
    }

    @Test
    public void partyWipeOnlyWhenNoSlotCanReturn() {
        AuthoritativeLives lives = lives(1);
        lives.down(alpha);
        lives.down(beta);
        advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);
        assertFalse(lives.isPartyWiped());

        lives.down(gamma);
        advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);
        assertTrue(lives.isPartyWiped());
    }

    @Test
    public void lifeLossesCountOnlyConsumedLivesNeverRevivals() {
        AuthoritativeLives lives = lives(3);
        lives.down(alpha);
        lives.beginRevival(beta, alpha);
        advance(lives, AuthoritativeLives.REVIVAL_TICKS);
        assertEquals(0, lives.getLifeLosses(alpha));

        lives.down(alpha);
        advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);
        lives.down(alpha);
        advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);
        assertEquals(2, lives.getLifeLosses(alpha));
        assertEquals(0, lives.getLifeLosses(beta));
        assertEquals(0, lives.getLifeLosses(new ParticipantId("campaign-slot-9")));
    }

    @Test
    public void devStandReturnsDownedOrExhaustedSlotsAndCapsGrantedLives() {
        AuthoritativeLives lives = lives(1);
        lives.down(alpha);
        advance(lives, AuthoritativeLives.BLEEDOUT_TICKS);
        assertEquals(Condition.EXHAUSTED, lives.getCondition(alpha));

        assertTrue(lives.devStand(alpha, false));
        assertEquals(Condition.STANDING, lives.getCondition(alpha));
        assertEquals(1, lives.getRemainingLives(alpha));
        assertFalse(lives.devStand(alpha, true));
        assertEquals(2, lives.getRemainingLives(alpha));

        lives.down(beta);
        lives.beginRevival(gamma, beta);
        assertTrue(lives.devStand(beta, false));
        assertNull(lives.getRevivalTarget(gamma));
        assertEquals(1, lives.getRemainingLives(beta));
        for(int i = 0; i < 10; i++) lives.devStand(gamma, true);
        assertEquals(AuthoritativeLives.MAXIMUM_STARTING_LIVES, lives.getRemainingLives(gamma));
        assertFalse(lives.devStand(new ParticipantId("campaign-slot-9"), true));
    }

    @Test
    public void revisionChangesOnTransitionsOnly() {
        AuthoritativeLives lives = lives(3);
        long initial = lives.getRevision();
        lives.down(alpha);
        long downed = lives.getRevision();
        advance(lives, 10);

        assertTrue(downed > initial);
        assertEquals(downed, lives.getRevision());
    }

    @Test
    public void revivalAndRespawnHealthRoundUpToAtLeastOne() {
        assertEquals(2, AuthoritativeLives.healthPercent(8, 25));
        assertEquals(4, AuthoritativeLives.healthPercent(8, 50));
        assertEquals(3, AuthoritativeLives.healthPercent(9, 25));
        assertEquals(1, AuthoritativeLives.healthPercent(1, 25));
    }
}
