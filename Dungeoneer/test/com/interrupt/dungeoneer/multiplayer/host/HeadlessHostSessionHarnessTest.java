package com.interrupt.dungeoneer.multiplayer.host;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.host.HeadlessHostSessionHarness.ObservedValue;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class HeadlessHostSessionHarnessTest {
    private static HeadlessApplication application;

    @BeforeClass
    public static void startHeadlessRuntime() {
        application = new HeadlessApplication(new ApplicationAdapter() { });
    }

    @AfterClass
    public static void stopHeadlessRuntime() {
        if(application != null) application.exit();
    }

    @Test
    public void advancesAuthoritativeSimulationAtControlledFixedSixtyHertz() {
        OpenSourceTestHostSimulation simulation =
                new OpenSourceTestHostSimulation(loadOpenSourceTestFloor());
        HeadlessHostSessionHarness harness = new HeadlessHostSessionHarness(simulation);

        harness.advanceTicks(120L);

        assertEquals(120L, harness.getHostTick());
        assertEquals(120L, simulation.getSimulationTicks());
        assertEquals(AuthoritativeHostSession.FIXED_DELTA_SECONDS,
                simulation.getLastFixedDeltaSeconds(), 0f);
        assertEquals(2f, simulation.getElapsedSeconds(), 0.00001f);
        assertEquals(120, harness.getSnapshots().size());
        ObservedValue<HostSessionSnapshot> last = harness.getSnapshots().get(119);
        assertEquals(120L, last.getHostTick());
        assertEquals(120L, ((OpenSourceTestHostSimulation.TestSnapshot)last.getValue())
                .getHostTick());
    }

    @Test
    public void exposesSnapshotsEventsDisconnectsTransitionsAndPersistedOutputs() {
        OpenSourceTestHostSimulation simulation =
                new OpenSourceTestHostSimulation(loadOpenSourceTestFloor());
        HeadlessHostSessionHarness harness = new HeadlessHostSessionHarness(simulation);
        HostSessionCommandGateway commands = harness.getCommandGateway();

        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("alpha"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("beta"));
        harness.advanceTicks(1L);
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.move("alpha", 1, 0));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.event("beta", "door-opened"));
        harness.advanceTicks(2L);
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.transition(
                "alpha", "open-source-test-annex"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.persist("alpha"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.disconnect(
                "beta", "requested"));
        harness.advanceTicks(1L);

        assertEquals(4, harness.getSnapshots().size());
        assertEquals(3, harness.getEvents().size());
        assertEquals(1, harness.getDisconnects().size());
        assertEquals(1, harness.getTransitions().size());
        assertEquals(1, harness.getPersistedStates().size());
        assertEquals(4L, harness.getDisconnects().get(0).getHostTick());
        assertEquals(4L, harness.getTransitions().get(0).getHostTick());
        assertEquals(4L, harness.getPersistedStates().get(0).getHostTick());

        OpenSourceTestHostSimulation.TestSnapshot snapshot =
                (OpenSourceTestHostSimulation.TestSnapshot)harness.getSnapshots().get(3).getValue();
        assertEquals("open-source-test-annex", snapshot.getFloorId());
        assertTrue(snapshot.getFloorWidth() > 0);
        assertTrue(snapshot.getFloorHeight() > 0);
        assertTrue(snapshot.getParticipants().containsKey(new ParticipantId("alpha")));
        assertFalse(snapshot.getParticipants().containsKey(new ParticipantId("beta")));

        OpenSourceTestHostSimulation.TestPersistedState persisted =
                (OpenSourceTestHostSimulation.TestPersistedState)
                        harness.getPersistedStates().get(0).getValue();
        assertTrue(persisted.getCanonicalState().contains("floor=open-source-test-annex"));
        assertTrue(persisted.getCanonicalState().contains("alpha="));
        assertTrue(persisted.getCanonicalState().contains("beta="));
    }

    @Test
    public void keepsTwoParticipantIdentitiesAndCharacterStateDistinctWithoutAdditionalPlayers() {
        OpenSourceTestHostSimulation simulation =
                new OpenSourceTestHostSimulation(loadOpenSourceTestFloor());
        HeadlessHostSessionHarness harness = new HeadlessHostSessionHarness(simulation);
        HostSessionCommandGateway commands = harness.getCommandGateway();

        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("alpha"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("beta"));
        harness.advanceTicks(1L);

        ParticipantContext alpha = simulation.getParticipant(new ParticipantId("alpha"));
        ParticipantContext beta = simulation.getParticipant(new ParticipantId("beta"));
        assertFalse(alpha.getParticipantId().equals(beta.getParticipantId()));
        assertNotSame(alpha.getCharacter(), beta.getCharacter());
        assertTrue(alpha.getCharacter() instanceof ParticipantCharacterState);
        assertTrue(beta.getCharacter() instanceof ParticipantCharacterState);
        assertFalse(Player.class.isAssignableFrom(alpha.getCharacter().getClass()));
        assertFalse(Player.class.isAssignableFrom(beta.getCharacter().getClass()));
    }

    @Test
    public void sameFloorTeleportMovesOnlyItsActivatingParticipant() {
        OpenSourceTestHostSimulation simulation =
                new OpenSourceTestHostSimulation(loadOpenSourceTestFloor());
        HeadlessHostSessionHarness harness = new HeadlessHostSessionHarness(simulation);
        HostSessionCommandGateway commands = harness.getCommandGateway();
        ParticipantId alphaId = new ParticipantId("alpha");
        ParticipantId betaId = new ParticipantId("beta");

        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("alpha"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("beta"));
        harness.advanceTicks(1L);
        OpenSourceTestHostSimulation.TestSnapshot before =
                (OpenSourceTestHostSimulation.TestSnapshot)harness.getSnapshots().get(0).getValue();
        OpenSourceTestHostSimulation.TestParticipantState alphaBefore =
                before.getParticipants().get(alphaId);
        int targetX = alphaBefore.getX() == 0f ? before.getFloorWidth() - 1 : 0;
        int targetY = alphaBefore.getY() == 0f ? before.getFloorHeight() - 1 : 0;

        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.teleportSameFloor(
                "beta", targetX, targetY));
        harness.advanceTicks(1L);

        OpenSourceTestHostSimulation.TestSnapshot after =
                (OpenSourceTestHostSimulation.TestSnapshot)harness.getSnapshots().get(1).getValue();
        assertEquals(alphaBefore.getX(), after.getParticipants().get(alphaId).getX(), 0f);
        assertEquals(alphaBefore.getY(), after.getParticipants().get(alphaId).getY(), 0f);
        assertEquals(targetX, after.getParticipants().get(betaId).getX(), 0f);
        assertEquals(targetY, after.getParticipants().get(betaId).getY(), 0f);
        assertEquals("beta:teleported", harness.getEvents().get(2).getValue().toString());
    }

    @Test
    public void appliesPartyMutationOnceAndShowsItToBothParticipants() {
        OpenSourceTestHostSimulation simulation =
                new OpenSourceTestHostSimulation(loadOpenSourceTestFloor());
        HeadlessHostSessionHarness harness = new HeadlessHostSessionHarness(simulation);
        HostSessionCommandGateway commands = harness.getCommandGateway();
        ParticipantId alphaId = new ParticipantId("alpha");
        ParticipantId betaId = new ParticipantId("beta");

        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("alpha"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("beta"));
        harness.advanceTicks(1L);
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.mutateParty(
                "alpha", "opened-test-door", "yes"));
        harness.advanceTicks(1L);

        OpenSourceTestHostSimulation.TestSnapshot snapshot =
                (OpenSourceTestHostSimulation.TestSnapshot)harness.getSnapshots().get(1).getValue();
        OpenSourceTestHostSimulation.TestParticipantState alpha =
                snapshot.getParticipants().get(alphaId);
        OpenSourceTestHostSimulation.TestParticipantState beta =
                snapshot.getParticipants().get(betaId);
        assertEquals("yes", alpha.getPartyProgression().get("opened-test-door"));
        assertEquals(alpha.getPartyProgression(), beta.getPartyProgression());
        assertEquals(1L, alpha.getPartyMutationCount());
        assertEquals(1L, beta.getPartyMutationCount());
        assertEquals("alpha:party-mutation:opened-test-door",
                harness.getEvents().get(2).getValue().toString());
    }

    @Test
    public void repeatedDeterministicScenariosProduceEquivalentVisibleOutcomes() {
        HeadlessHostSessionHarness first = runDeterministicScenario();
        HeadlessHostSessionHarness second = runDeterministicScenario();

        assertEquals(first.getSnapshots(), second.getSnapshots());
        assertEquals(first.getEvents(), second.getEvents());
        assertEquals(first.getDisconnects(), second.getDisconnects());
        assertEquals(first.getTransitions(), second.getTransitions());
        assertEquals(first.getPersistedStates(), second.getPersistedStates());
    }

    private HeadlessHostSessionHarness runDeterministicScenario() {
        HeadlessHostSessionHarness harness = new HeadlessHostSessionHarness(
                new OpenSourceTestHostSimulation(loadOpenSourceTestFloor()));
        HostSessionCommandGateway commands = harness.getCommandGateway();
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("bravo"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.join("alpha"));
        harness.advanceTicks(1L);
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.move("bravo", -1, 0));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.move("alpha", 1, 1));
        harness.advanceTicks(5L);
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.event("alpha", "ping"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.transition(
                "bravo", "deterministic-annex"));
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.persist("alpha"));
        harness.advanceTicks(1L);
        commands.submit(OpenSourceTestHostSimulation.SyntheticCommand.disconnect(
                "bravo", "scenario-complete"));
        harness.advanceTicks(1L);
        return harness;
    }

    private Level loadOpenSourceTestFloor() {
        File testFloor = new File("assets", GameApplication.OPEN_SOURCE_TEST_LEVEL);
        assertTrue("Open-source test floor is missing", testFloor.isFile());
        Level level = KryoSerializer.loadLevel(new FileHandle(testFloor));
        assertTrue("Open-source test floor could not be deserialized", level != null);
        return level;
    }
}
