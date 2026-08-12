package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Progression;

/** Temporary adapter retaining original local first-person Player behavior behind Participant APIs. */
public final class LocalPlayerCompatibilityAdapter {
    public static final ParticipantId LOCAL_PARTICIPANT_ID = new ParticipantId("local-player");

    private LocalPlayerCompatibilityAdapter() { }

    public static ParticipantContext fromGame() {
        if(Game.instance == null || Game.instance.player == null || Game.instance.progression == null) {
            throw new IllegalStateException("Local Player compatibility requires an active Game.");
        }
        return adapt(Game.instance.player, Game.instance.progression);
    }

    public static ParticipantContext adapt(Player player, Progression progression) {
        if(player == null) throw new IllegalArgumentException("Local Player cannot be null.");
        if(progression == null) throw new IllegalArgumentException("Local progression cannot be null.");
        return new ParticipantContext(LOCAL_PARTICIPANT_ID,
                new LocalPlayerCharacter(player), new LocalPartyProgression(progression));
    }

    private static final class LocalPlayerCharacter implements ParticipantCharacter {
        private final Player player;

        private LocalPlayerCharacter(Player player) {
            this.player = player;
        }

        @Override
        public float getX() {
            return player.x;
        }

        @Override
        public float getY() {
            return player.y;
        }

        @Override
        public float getZ() {
            return player.z;
        }

        @Override
        public float getRotation() {
            return player.rot;
        }

        @Override
        public boolean isHoldingOrb() {
            return player.isHoldingOrb;
        }

        @Override
        public void setPosition(float x, float y, float z) {
            player.x = x;
            player.y = y;
            player.z = z;
        }

        @Override
        public void setRotation(float rotation) {
            player.rot = rotation;
        }
    }

    private static final class LocalPartyProgression implements PartyProgression {
        private final Progression progression;

        private LocalPartyProgression(Progression progression) {
            this.progression = progression;
        }

        @Override
        public String getPersistent(String key) {
            return progression.progressionTriggers.get(key);
        }

        @Override
        public String getUntilDeath(String key) {
            return progression.untilDeathProgressionTriggers.get(key);
        }

        @Override
        public void putPersistent(String key, String value) {
            progression.progressionTriggers.put(key, value);
        }

        @Override
        public void putUntilDeath(String key, String value) {
            progression.untilDeathProgressionTriggers.put(key, value);
        }
    }
}
