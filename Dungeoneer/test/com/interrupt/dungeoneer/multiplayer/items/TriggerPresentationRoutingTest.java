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
    private HashMap<String, LocalizedString> previousStrings;
    private final List<String> delivered = new ArrayList<String>();

    @Before public void floor() {
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
