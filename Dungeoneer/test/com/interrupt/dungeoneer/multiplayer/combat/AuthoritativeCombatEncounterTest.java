package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.HostDisconnectOutcome;
import com.interrupt.dungeoneer.multiplayer.host.HostPersistedState;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostTransitionOutcome;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSpawn;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.movement.RectangularMovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuthoritativeCombatEncounterTest {
    @Test(expected = IllegalArgumentException.class)
    public void targetedHarmfulWeaponIntentIsRejected() {
        request(1, 1L, CombatAction.MELEE,
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void directedNonDirectedIntentIsRejected() {
        directedRequest(1, 1L, CombatAction.SELF_DAMAGE, 1f, 0f, 0f);
    }

    @Test
    public void hostQueuesMeleeProjectilesAndSpellsForNativeResolution() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 0f, 1f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.MELEE,
                1f, 0f, 0f), output);
        encounter.apply(31L, directedRequest(1, 2L, CombatAction.PROJECTILE,
                1f, 0f, 0f), output);
        encounter.apply(3L, directedRequest(2, 1L, CombatAction.SPELL,
                1f, -1f, 0f), output);

        List<CombatRequest> requests = encounter.drainNativeCombatRequests();
        assertEquals(3, requests.size());
        assertEquals(CombatAction.MELEE, requests.get(0).getAction());
        assertEquals(CombatAction.PROJECTILE, requests.get(1).getAction());
        assertEquals(CombatAction.SPELL, requests.get(2).getAction());
        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.events.size());
    }

    @Test
    public void hostPublishesOrderedNativePresentationAndDamage() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.setMonsterPosition(0.5f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f, 0.5f);

        encounter.publishNativePresentation(1L,
                AuthoritativeCombatEncounter.participantTargetId(participant(1)),
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, CombatAction.SPELL,
                CombatPresentationPhase.ATTACK, 0f, 0f, 0.85f,
                0.5f, 0f, 0.5f, false, output);
        encounter.applyNativeMonsterDamage(180L, participant(1), 1,
                CombatAction.MELEE, 0.5f, 0f, 0.5f,
                0f, 0f, 0.5f, output);

        assertEquals(2, output.presentations.size());
        CombatPresentationEvent playerAttack = output.presentations.get(0);
        assertEquals(1L, playerAttack.getSequence());
        assertEquals(AuthoritativeCombatEncounter.participantTargetId(participant(1)),
                playerAttack.getSourceId());
        assertEquals(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                playerAttack.getTargetId());
        assertEquals(CombatAction.SPELL, playerAttack.getAction());
        assertEquals(0f, playerAttack.getOriginX(), 0f);
        assertEquals(0.5f, playerAttack.getImpactX(), 0f);
        assertTrue(!playerAttack.isStateChanged());

        CombatPresentationEvent monsterAttack = output.presentations.get(1);
        assertEquals(2L, monsterAttack.getSequence());
        assertEquals(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                monsterAttack.getSourceId());
        assertEquals(AuthoritativeCombatEncounter.participantTargetId(participant(1)),
                monsterAttack.getTargetId());
        assertEquals(CombatAction.MELEE, monsterAttack.getAction());
        assertTrue(monsterAttack.isStateChanged());
    }

    @Test
    public void monsterPrefersVisibleAttackerBeforeNearestVisibleParticipant() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.setMonsterPosition(1f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 0.9f, 0f);
        encounter.updateParticipantPosition(participant(2), 1.5f, 0f);
        encounter.recordNativeMonsterAttacker(1L,
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, participant(2));

        encounter.tick(180L, output);

        CombatSnapshot snapshot = encounter.getSnapshot(180L);
        assertEquals(8, snapshot.getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant(2))).getHealth());
        assertEquals(8, snapshot.getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant(1))).getHealth());
        assertEquals(AuthoritativeCombatEncounter.participantTargetId(participant(2)),
                snapshot.getMonsterTargetId());
    }

    @Test
    public void combatIneligibleParticipantCannotSubmitActions() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.setParticipantCombatEligible(participant(2), false);

        encounter.apply(1L, directedRequest(2, 1L, CombatAction.MELEE,
                1f, 0f, 0f), output);

        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.presentations.size());
    }

    @Test
    public void harmfulIntentQueuesWithoutSyntheticParticipantCollision() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 2f, 1f, 0.5f);
        encounter.setMonsterPosition(3f, 1f, 0.5f);
        encounter.setParticipantCombatEligible(participant(2), false);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.MELEE,
                1f, 0f, 0f), output);

        assertEquals(24, monsterHealth(encounter));
        assertEquals(1, encounter.drainNativeCombatRequests().size());
        assertEquals(0, output.presentations.size());
    }

    @Test
    public void monsterIgnoresCombatIneligibleParticipantUntilReconnect() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String second = AuthoritativeCombatEncounter.participantTargetId(participant(2));
        encounter.setMonsterPosition(1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 20f, 20f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 1.5f, 1f, 0.5f);
        encounter.setParticipantCombatEligible(participant(2), false);

        encounter.tick(180L, output);

        assertEquals("", encounter.getSnapshot(180L).getMonsterTargetId());
        assertEquals(8, encounter.getSnapshot(180L).getCombatant(second).getHealth());

        encounter.setParticipantCombatEligible(participant(2), true);
        encounter.tick(360L, output);

        assertEquals(second, encounter.getSnapshot(360L).getMonsterTargetId());
        assertEquals(8, encounter.getSnapshot(360L).getCombatant(second).getHealth());
    }

    @Test
    public void friendlyFireIsBlockedWhileBenefitsSelfDamageAndHazardsRemainActive() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String first = AuthoritativeCombatEncounter.participantTargetId(participant(1));
        String second = AuthoritativeCombatEncounter.participantTargetId(participant(2));
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 1f, 0f, 0.5f);
        encounter.setMonsterPosition(3f, 0f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.MELEE,
                1f, 0f, 0f), output);
        encounter.apply(2L, request(1, 2L, CombatAction.SELF_DAMAGE, first), output);
        encounter.apply(3L, request(2, 1L, CombatAction.BENEFICIAL_SPELL, first), output);
        encounter.apply(4L, request(1, 3L, CombatAction.ENVIRONMENTAL_HAZARD, first), output);

        CombatSnapshot snapshot = encounter.getSnapshot(4L);
        assertEquals(6, snapshot.getCombatant(first).getHealth());
        assertEquals(8, snapshot.getCombatant(second).getHealth());
        assertEquals(3, output.events.size());
    }

    @Test
    public void duplicateAndStaleRequestsQueueOnlyOneNativeAction() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f, 0.5f);

        encounter.apply(1L, directedRequest(1, 2L, CombatAction.MELEE,
                1f, 0f, 0f), output);
        encounter.apply(2L, directedRequest(1, 2L, CombatAction.PROJECTILE,
                1f, 0f, 0f), output);
        encounter.apply(3L, directedRequest(1, 1L, CombatAction.SPELL,
                1f, 0f, 0f), output);

        assertEquals(24, monsterHealth(encounter));
        assertEquals(1, encounter.drainNativeCombatRequests().size());
        assertEquals(0, output.events.size());
        assertEquals(0, output.presentations.size());
    }

    @Test
    public void maximumValidRequestIdProducesExhaustionSentinel() {
        AuthoritativeCombatEncounter encounter = encounter();
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);

        encounter.apply(1L, directedRequest(1, Long.MAX_VALUE - 1L, CombatAction.MELEE,
                1f, 0f, 0f), new EventOutput());

        assertEquals(Long.MAX_VALUE, encounter.getNextRequestId(participant(1)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void exhaustionSentinelCannotBeSubmittedAsARequestId() {
        directedRequest(1, Long.MAX_VALUE, CombatAction.MELEE, 1f, 0f, 0f);
    }

    @Test
    public void hostRejectsFreshAttackIdsInsideAuthoritativeCadence() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.SPELL,
                1f, 0f, 0f), output);
        encounter.apply(1L, directedRequest(1, 2L, CombatAction.PROJECTILE,
                1f, 0f, 0f), output);
        encounter.apply(2L, directedRequest(1, 3L, CombatAction.MELEE,
                1f, 0f, 0f), output);

        assertEquals(1, encounter.drainNativeCombatRequests().size());
        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.events.size());
        assertEquals(0, output.presentations.size());

        encounter.apply(31L, directedRequest(1, 4L, CombatAction.PROJECTILE,
                1f, 0f, 0f), output);

        assertEquals(1, encounter.drainNativeCombatRequests().size());
        assertEquals(24, monsterHealth(encounter));
    }

    @Test
    public void hostDefersRangeAndHitValidationToNativeWorld() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.setMonsterPosition(3f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.MELEE,
                1f, 0f, 0f), output);
        encounter.apply(31L, directedRequest(1, 2L, CombatAction.PROJECTILE,
                1f, 0f, 0f), output);

        List<CombatRequest> requests = encounter.drainNativeCombatRequests();
        assertEquals(2, requests.size());
        assertEquals(CombatAction.MELEE, requests.get(0).getAction());
        assertEquals(CombatAction.PROJECTILE, requests.get(1).getAction());
        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.events.size());
    }

    @Test
    public void hostDefersDirectedSpellTraceToNativeWorld() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.setMonsterPosition(3f, 1f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.SPELL,
                1f, 0f, 0f), output);

        List<CombatRequest> requests = encounter.drainNativeCombatRequests();
        assertEquals(1, requests.size());
        assertEquals(CombatAction.SPELL, requests.get(0).getAction());
        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.events.size());
        assertEquals(0, output.presentations.size());
    }

    @Test
    public void hostDoesNotInventFloorImpactBeforeNativeProjectileRuns() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.setMonsterPosition(10f, 10f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.SPELL,
                1f, 0f, -1f), output);

        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.events.size());
        assertEquals(0, output.presentations.size());
        assertEquals(1, encounter.drainNativeCombatRequests().size());
    }

    @Test
    public void directedMeleeAimIsPreservedForNativeTrace() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.setMonsterPosition(2f, 1f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.MELEE,
                0f, 1f, 0f), output);

        CombatRequest queued = encounter.drainNativeCombatRequests().get(0);
        assertEquals(0f, queued.getAimX(), 0f);
        assertEquals(1f, queued.getAimY(), 0f);
        assertEquals(24, monsterHealth(encounter));
        assertEquals(0, output.presentations.size());
    }

    @Test
    public void directedHarmfulIntentCannotDamageTeammateBeforeNativeResolution() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String second = AuthoritativeCombatEncounter.participantTargetId(participant(2));
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 2f, 1f, 0.5f);
        encounter.setMonsterPosition(3f, 1f, 0.5f);

        encounter.apply(1L, directedRequest(1, 1L, CombatAction.SPELL,
                1f, 0f, 0f), output);

        assertEquals(24, monsterHealth(encounter));
        assertEquals(8, encounter.getSnapshot(1L).getCombatant(second).getHealth());
        assertEquals(0, output.events.size());
        assertEquals(0, output.presentations.size());
        assertEquals(1, encounter.drainNativeCombatRequests().size());
    }

    @Test
    public void directedBeneficialSpellHealsTeammate() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String second = AuthoritativeCombatEncounter.participantTargetId(participant(2));
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 2f, 1f, 0.5f);
        encounter.setMonsterPosition(10f, 10f, 0.5f);
        encounter.apply(1L, request(2, 1L, CombatAction.SELF_DAMAGE, second), output);
        encounter.apply(7L, request(2, 2L, CombatAction.SELF_DAMAGE, second), output);

        encounter.apply(8L, directedRequest(1, 1L, CombatAction.BENEFICIAL_SPELL,
                1f, 0f, 0f), output);

        assertEquals(7, encounter.getSnapshot(8L).getCombatant(second).getHealth());
        assertEquals(second, output.presentations.get(2).getTargetId());
        assertTrue(output.presentations.get(2).isStateChanged());
    }

    @Test
    public void aimedHealIntentDoesNotSuppressNativeSelfHealIntentFromSameCast() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String first = AuthoritativeCombatEncounter.participantTargetId(participant(1));
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f, 0.5f);
        encounter.setMonsterPosition(20f, 20f, 0.5f);
        encounter.apply(1L, request(1, 1L, CombatAction.SELF_DAMAGE, first), output);
        encounter.apply(7L, request(1, 2L, CombatAction.SELF_DAMAGE, first), output);

        encounter.apply(8L, directedRequest(1, 3L, CombatAction.SPELL,
                1f, 0f, 0f), output);
        encounter.apply(8L, request(1, 4L, CombatAction.BENEFICIAL_SPELL, first), output);

        assertEquals(7, encounter.getSnapshot(8L).getCombatant(first).getHealth());
    }

    @Test
    public void beneficialSpellDoesNotReplaceVisibleMonsterAttacker() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String first = AuthoritativeCombatEncounter.participantTargetId(participant(1));
        String second = AuthoritativeCombatEncounter.participantTargetId(participant(2));
        encounter.setMonsterPosition(2f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 0f, 1f, 0.5f);
        encounter.recordNativeMonsterAttacker(1L,
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, participant(1));
        encounter.apply(2L, request(1, 2L, CombatAction.SELF_DAMAGE, first), output);
        encounter.setMonsterPosition(10f, 10f, 0.5f);
        encounter.apply(3L, directedRequest(2, 1L, CombatAction.BENEFICIAL_SPELL,
                1f, 0f, 0f), output);
        encounter.setMonsterPosition(1f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 1.5f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 0.9f, 0f, 0.5f);

        encounter.tick(180L, output);

        CombatSnapshot snapshot = encounter.getSnapshot(180L);
        assertEquals(first, snapshot.getMonsterTargetId());
        assertEquals(8, snapshot.getCombatant(first).getHealth());
        assertEquals(8, snapshot.getCombatant(second).getHealth());
    }

    @Test
    public void targetedBenefitRequiresAuthoritativeRangeAndLineOfSight() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String second = AuthoritativeCombatEncounter.participantTargetId(participant(2));
        encounter.updateParticipantPosition(participant(1), 1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 1f, 0.5f);
        encounter.apply(1L, request(2, 1L, CombatAction.SELF_DAMAGE, second), output);
        encounter.apply(7L, request(2, 2L, CombatAction.SELF_DAMAGE, second), output);

        encounter.apply(8L, request(1, 1L, CombatAction.BENEFICIAL_SPELL, second), output);

        assertEquals(4, encounter.getSnapshot(8L).getCombatant(second).getHealth());
        assertEquals(2, output.events.size());
    }

    @Test
    public void zeroHealthIsTerminalForDamageAndBenefits() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String first = AuthoritativeCombatEncounter.participantTargetId(participant(1));

        for(long requestId = 1L; requestId <= 4L; requestId++) {
            encounter.apply(1L + (requestId - 1L) * 30L, request(1, requestId,
                    CombatAction.ENVIRONMENTAL_HAZARD, first), output);
        }
        encounter.apply(92L, request(1, 5L, CombatAction.SELF_DAMAGE, first), output);
        encounter.apply(93L, request(2, 1L, CombatAction.BENEFICIAL_SPELL, first), output);

        assertEquals(0, encounter.getSnapshot(93L).getCombatant(first).getHealth());
        assertEquals(4, output.events.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void directedCombatRejectsNonFiniteAim() {
        directedRequest(1, 1L, CombatAction.SPELL, Float.NaN, 0f, 1f);
    }

    @Test
    public void encounterTracksTargetWithoutSimulatingNativeMonsterMovementOrDamage() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        encounter.setMonsterPosition(1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 8f, 1f);
        encounter.updateParticipantPosition(participant(2), 20f, 1f);

        encounter.tick(180L, output);

        CombatSnapshot snapshot = encounter.getSnapshot(180L);
        assertEquals(8, snapshot.getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant(1))).getHealth());
        assertEquals(AuthoritativeCombatEncounter.participantTargetId(participant(1)),
                snapshot.getMonsterTargetId());
        assertEquals(1f, snapshot.getMonsterX(), 0.0001f);
    }

    @Test
    public void monsterTargetsRecentlyHeardParticipantUntilMemoryExpires() {
        AuthoritativeCombatEncounter encounter = encounter();
        EventOutput output = new EventOutput();
        String first = AuthoritativeCombatEncounter.participantTargetId(participant(1));
        encounter.updateParticipantPosition(participant(1), 0f, 0f, 0.5f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f, 0.5f);
        encounter.apply(1L, directedRequest(1, 1L, CombatAction.MELEE,
                1f, 0f, 0f), output);
        encounter.updateParticipantPosition(participant(1), 20f, 0f, 0.5f);

        encounter.tick(2L, 1f / 60f, output);
        assertEquals(first, encounter.getSnapshot(2L).getMonsterTargetId());

        encounter.tick(182L, 1f / 60f, output);
        assertEquals("", encounter.getSnapshot(182L).getMonsterTargetId());
    }

    @Test
    public void monsterCannotTargetOrDamageParticipantThroughBlockedLineOfSight() {
        AuthoritativeCombatEncounter encounter = encounter(new BlockedLineOfSightWorld());
        EventOutput output = new EventOutput();
        encounter.setMonsterPosition(1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 1.5f, 1f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f);

        encounter.tick(180L, 1f / 60f, output);

        CombatSnapshot snapshot = encounter.getSnapshot(180L);
        assertEquals(8, snapshot.getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant(1))).getHealth());
        assertEquals("", snapshot.getMonsterTargetId());
        assertEquals(1f, snapshot.getMonsterX(), 0.0001f);
    }

    @Test
    public void monsterMovementCannotCrossHostCollision() {
        AuthoritativeCombatEncounter encounter = encounter(new BlockingMovementWorld());
        EventOutput output = new EventOutput();
        encounter.setMonsterPosition(1f, 1f, 0.5f);
        encounter.updateParticipantPosition(participant(1), 3f, 1f);
        encounter.updateParticipantPosition(participant(2), 20f, 20f);

        encounter.tick(180L, 1f / 60f, output);

        CombatSnapshot snapshot = encounter.getSnapshot(180L);
        assertEquals(1f, snapshot.getMonsterX(), 0.0001f);
        assertEquals(8, snapshot.getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant(1))).getHealth());
    }

    private AuthoritativeCombatEncounter encounter() {
        return encounter(new RectangularMovementCollisionWorld(32f, 32f, 1f, 1f, 0.5f));
    }

    private AuthoritativeCombatEncounter encounter(MovementCollisionWorld world) {
        AuthoritativeCombatEncounter encounter = new AuthoritativeCombatEncounter(Arrays.asList(
                descriptor(1), descriptor(2)), world);
        encounter.setMonsterPosition(1f, 0f, 0.5f);
        return encounter;
    }

    private CombatRequest request(int slot, long requestId, CombatAction action, String targetId) {
        return new CombatRequest(participant(slot), requestId, action, targetId);
    }

    private CombatRequest directedRequest(int slot, long requestId, CombatAction action,
            float aimX, float aimY, float aimZ) {
        return new CombatRequest(participant(slot), requestId, action, aimX, aimY, aimZ);
    }

    private int monsterHealth(AuthoritativeCombatEncounter encounter) {
        return encounter.getSnapshot(0L).getCombatant(
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID).getHealth();
    }

    private MovementEntityDescriptor descriptor(int slot) {
        return new MovementEntityDescriptor(slot, new NetworkEntityId(slot), participant(slot), slot,
                "Participant " + slot, new SlotPresentation("Participant " + slot,
                        "humanoid-" + slot).getAvatarId());
    }

    private ParticipantId participant(int slot) {
        return new ParticipantId("campaign-slot-" + slot);
    }

    private static final class EventOutput implements HostSessionOutput {
        private final List<CombatStateEvent> events = new ArrayList<CombatStateEvent>();
        private final List<CombatPresentationEvent> presentations =
                new ArrayList<CombatPresentationEvent>();

        @Override
        public void event(HostSessionEvent event) {
            if(event instanceof CombatStateEvent) events.add((CombatStateEvent)event);
            if(event instanceof CombatPresentationEvent) {
                presentations.add((CombatPresentationEvent)event);
            }
        }

        @Override public void disconnect(HostDisconnectOutcome outcome) { }
        @Override public void transition(HostTransitionOutcome outcome) { }
        @Override public void persist(HostPersistedState state) { }
    }

    private static class BlockedLineOfSightWorld implements MovementCollisionWorld {
        @Override public MovementSpawn getSpawn(int campaignSlot) {
            return new MovementSpawn(1f, 1f, 0.5f, 0f);
        }

        @Override public boolean canOccupy(float x, float y, float z) { return true; }

        @Override public float getFloorZ(float x, float y, float currentZ) { return 0.5f; }

        @Override public boolean hasLineOfSight(float fromX, float fromY, float toX, float toY) {
            return false;
        }
    }

    private static final class BlockingMovementWorld extends BlockedLineOfSightWorld {
        @Override public boolean canOccupy(float x, float y, float z) { return x <= 1f; }

        @Override public boolean hasLineOfSight(float fromX, float fromY, float toX, float toY) {
            return true;
        }
    }
}
