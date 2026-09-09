package com.interrupt.dungeoneer.entities.triggers;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.WarpMarker;
import com.interrupt.dungeoneer.game.LocalizedString;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression;
import com.interrupt.managers.StringManager;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TriggerParticipantContextTest {
    private static HashMap<String, LocalizedString> previousLocalizedStrings;

    @BeforeClass
    public static void provideMinimalLocalizedStrings() {
        previousLocalizedStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
    }

    @AfterClass
    public static void restoreLocalizedStrings() {
        StringManager.localizedStrings = previousLocalizedStrings;
    }

    @Test
    public void explicitObjectUseRetainsParticipantAcrossDelay() {
        RecordingTrigger trigger = new RecordingTrigger();
        trigger.triggerDelay = 2f;
        ParticipantContext beta = new ParticipantContext(new ParticipantId("beta"),
                new ParticipantCharacterState(2f, 3f, 0f, 0f), new SharedPartyProgression());
        trigger.use(beta);
        trigger.tick(null, 2f);
        assertSame(beta, trigger.observedParticipant);
    }

    @Test
    public void retainsInitiatingParticipantAcrossTriggerDelay() {
        RecordingTrigger trigger = new RecordingTrigger();
        trigger.triggerDelay = 2f;
        ParticipantContext beta = new ParticipantContext(new ParticipantId("beta"),
                new ParticipantCharacterState(2f, 3f, 0f, 0f),
                new SharedPartyProgression());

        trigger.fire(beta, "activate");
        trigger.tick(null, 1f);
        assertNull(trigger.observedParticipant);
        trigger.tick(null, 1f);

        assertSame(beta, trigger.observedParticipant);
    }

    @Test
    public void propagatesInitiatingParticipantThroughLevelTriggerChain() {
        ParticipantContext alpha = new ParticipantContext(new ParticipantId("alpha"),
                new ParticipantCharacterState(1f, 1f, 0f, 0f),
                new SharedPartyProgression());
        RecordingTrigger receiver = new RecordingTrigger();
        receiver.id = "receiver";
        Level level = new Level();
        level.entities = new Array<Entity>();
        level.non_collidable_entities = new Array<Entity>();
        level.static_entities = new Array<Entity>();
        level.entities.add(receiver);

        level.trigger(new Entity(), "receiver", "activate", alpha);
        receiver.tick(null, 0f);

        assertSame(alpha, receiver.observedParticipant);
    }

    @Test
    public void triggeredTeleportMovesExplicitParticipantInsteadOfGlobalPlayer() {
        ParticipantCharacterState alphaCharacter =
                new ParticipantCharacterState(1f, 1f, 0f, 0f);
        ParticipantCharacterState betaCharacter =
                new ParticipantCharacterState(2f, 2f, 0f, 0f);
        SharedPartyProgression party = new SharedPartyProgression();
        ParticipantContext alpha = new ParticipantContext(
                new ParticipantId("alpha"), alphaCharacter, party);
        ParticipantContext beta = new ParticipantContext(
                new ParticipantId("beta"), betaCharacter, party);
        WarpMarker marker = new WarpMarker();
        marker.id = "teleport-destination";
        marker.setPosition(7f, 8f, 0.5f);
        marker.setRotation(0f, 0f, 90f);
        Level level = new Level();
        level.entities = new Array<Entity>();
        level.non_collidable_entities = new Array<Entity>();
        level.static_entities = new Array<Entity>();
        level.entities.add(marker);
        TriggeredTeleportPlayer teleport = new TriggeredTeleportPlayer();
        teleport.toWarpMarkerId = marker.id;

        teleport.teleportParticipant(beta, level);

        assertEquals(1f, alpha.getCharacter().getX(), 0f);
        assertEquals(1f, alpha.getCharacter().getY(), 0f);
        assertEquals(7f, beta.getCharacter().getX(), 0f);
        assertEquals(8f, beta.getCharacter().getY(), 0f);
        assertEquals((float)Math.PI, beta.getCharacter().getRotation(), 0.00001f);
    }

    private static final class RecordingTrigger extends Trigger {
        private ParticipantContext observedParticipant;

        @Override
        public void doTriggerEvent(String value) {
            observedParticipant = getTriggeringParticipantContext();
        }
    }
}
