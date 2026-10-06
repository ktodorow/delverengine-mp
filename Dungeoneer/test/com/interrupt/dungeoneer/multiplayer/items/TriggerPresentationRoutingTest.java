package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.LocalizedString;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.managers.StringManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TriggerPresentationRoutingTest {
    private Game previousGame;
    private com.badlogic.gdx.Application previousApplication;
    private HashMap<String, LocalizedString> previousStrings;
    private final List<String> delivered = new ArrayList<String>();

    @Before public void floor() {
        previousApplication = com.badlogic.gdx.Gdx.app;
        com.badlogic.gdx.Gdx.app = (com.badlogic.gdx.Application)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { com.badlogic.gdx.Application.class },
                (proxy, method, args) -> null);
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        previousGame = Game.instance;
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.progression = new Progression();
        game.level = new Level(4, 4);
        game.level.nativeTriggerPresentationListener = (trigger, activator, value) ->
                delivered.add(activator.getValue() + ":" + value);
        Game.instance = game;
    }

    @After public void restore() {
        StringManager.localizedStrings = previousStrings;
        Game.instance = previousGame;
        com.badlogic.gdx.Gdx.app = previousApplication;
    }

    @Test public void ownPlayerSeesItHereAndClosingItContinuesTheChain() {
        Sign sign = new Sign();
        sign.fire(LocalPlayerCompatibilityAdapter.adapt(Game.instance.player,
                Game.instance.progression), null);

        sign.doTriggerEvent("");

        assertEquals(1, sign.presented);
        assertTrue(sign.continuesChain);
        assertTrue(delivered.isEmpty());
    }

    @Test public void clientReadingASignSeesItOnItsOwnScreenNotHosts() {
        Sign sign = new Sign();
        sign.fire(remote(false), null);

        sign.doTriggerEvent("sign.dat");

        assertEquals("Host screen stays clear", 0, sign.presented);
        assertEquals(1, delivered.size());
        assertEquals("campaign-slot-2:sign.dat", delivered.get(0));
    }

    @Test public void clientWalkingIntoATriggerAlreadyShowedItsOwnCopy() {
        Sign sign = new Sign();
        sign.fire(remote(true), null);

        sign.doTriggerEvent("");

        assertEquals(0, sign.presented);
        assertTrue(delivered.isEmpty());
    }

    @Test public void onceGateCommitsBeforeDownstreamReentryAndRejectsDuplicateDelivery() {
        com.interrupt.dungeoneer.entities.triggers.ProgressionTrigger gate =
                new com.interrupt.dungeoneer.entities.triggers.ProgressionTrigger();
        gate.progressionKey = "gate";
        gate.newProgressionValue = "opened";
        gate.triggersId = "consequence";
        final int[] consequences = { 0 };
        com.interrupt.dungeoneer.entities.Entity consequence = new com.interrupt.dungeoneer.entities.Entity() {
            @Override public void onTrigger(com.interrupt.dungeoneer.entities.Entity source, String value) {
                consequences[0]++;
                if(consequences[0] == 1) gate.doTriggerEvent(value);
            }
        };
        consequence.id = "consequence";
        Game.instance.level.entities.add(consequence);
        gate.fire(remote(false), null);
        gate.doTriggerEvent("");
        gate.doTriggerEvent("");
        assertEquals("opened", Game.instance.progression.progressionTriggers.get("gate"));
        assertEquals("shared consequence executes once", 1, consequences[0]);
    }

    @Test public void remoteDialogueCommitsPartyHistoryWithoutHostPresentation() {
        com.interrupt.dungeoneer.entities.triggers.TriggeredMessage message =
                new com.interrupt.dungeoneer.entities.triggers.TriggeredMessage();
        message.messageFile = "first.dat,second.dat";
        message.progressionKey = "campfire";
        message.init(Game.instance.level, Level.Source.LEVEL_START);
        message.fire(remote(false), null);
        message.doTriggerEvent("");
        assertEquals("Host records dialogue even when another Participant reads it",
                Integer.valueOf(0), Game.instance.progression.messagesSeen.get("campfire"));
        assertEquals(1, delivered.size());
    }

    @Test public void basicTriggerMessageTargetsRemoteActivator() {
        String hostMessage = Game.message.toString();
        com.interrupt.dungeoneer.entities.triggers.BasicTrigger trigger =
                new com.interrupt.dungeoneer.entities.triggers.BasicTrigger();
        trigger.message = "Remote message";
        trigger.fire(remote(false), null);
        trigger.tick(Game.instance.level, 1f);
        assertEquals(java.util.Collections.singletonList("campaign-slot-2:"), delivered);
        assertEquals("Host message remains unchanged", hostMessage, Game.message.toString());
    }

    @Test public void buttonMessageTargetsRemoteActivator() {
        String hostMessage = Game.message.toString();
        com.interrupt.dungeoneer.entities.triggers.ButtonModel button =
                new com.interrupt.dungeoneer.entities.triggers.ButtonModel();
        button.message = "Remote button message";
        button.fire(remote(false), null);
        button.tick(Game.instance.level, 1f);
        assertEquals(java.util.Collections.singletonList("campaign-slot-2:"), delivered);
        assertEquals("Host message remains unchanged", hostMessage, Game.message.toString());
    }

    private static ParticipantContext remote(boolean presentedByActivator) {
        return new ParticipantContext(new ParticipantId("campaign-slot-2"),
                new ParticipantCharacterState(1f, 1f, 0f, 0f),
                LocalPlayerCompatibilityAdapter.fromGame().getPartyProgression(),
                presentedByActivator);
    }

    private static final class Sign extends Trigger {
        int presented;
        boolean continuesChain;

        @Override public void presentToActivator(String value, boolean continuesChain) {
            presented++;
            this.continuesChain = continuesChain;
        }
    }
}
