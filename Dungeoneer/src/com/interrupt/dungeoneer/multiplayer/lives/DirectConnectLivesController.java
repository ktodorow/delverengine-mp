package com.interrupt.dungeoneer.multiplayer.lives;

import com.interrupt.dungeoneer.GameInput;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.input.Actions.Action;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * Native side of Host-owned Lives: keeps original death away from the local Player, presents
 * Downed Participants, turns held Use into Revival intent and starts each new Life clean.
 */
public final class DirectConnectLivesController {
    /** Inside Host reach so a prompt shown here is a Revival Host will accept. */
    static final float REVIVAL_PROMPT_REACH = 1.4f;
    private static final float INTENT_RESEND_SECONDS = 0.5f;
    private static final float TICKS_PER_SECOND = 60f;

    private final DirectConnectPeer peer;
    private final DirectConnectMovementController movement;
    private final Map<Integer, Integer> observedLives = new HashMap<Integer, Integer>();
    private long observedStatusSequence = -1L;
    private float secondsSinceStatus;
    private int intendedRevivalSlot;
    private float secondsSinceIntent;
    private String prompt;

    public DirectConnectLivesController(DirectConnectPeer peer,
            DirectConnectMovementController movement) {
        if(peer == null || movement == null) {
            throw new IllegalArgumentException("Lives controller dependencies cannot be null.");
        }
        this.peer = peer;
        this.movement = movement;
    }

    /** Runs before native Game.tick so zero health never reaches original Player death. */
    public void prepare(Game game) {
        if(game == null || game.player == null) return;
        game.player.deathDeferredToAuthority = true;
    }

    public void update(Game game, GameInput input, float deltaSeconds) {
        if(game == null || game.player == null || input == null) return;
        prompt = null;
        PartyStatusSnapshot status = peer.getPartyStatus();
        if(status == null || peer.getLocalMovementEntityId() == null) return;
        if(status.getSequence() != observedStatusSequence) {
            observedStatusSequence = status.getSequence();
            secondsSinceStatus = 0f;
        }
        else if(!peer.isSessionPaused()) secondsSinceStatus += Math.max(0f, deltaSeconds);

        PartyMemberStatus local = status.getMember(peer.getLocalMovementEntityId());
        for(PartyMemberStatus member : status.getMembers()) {
            Integer previous = observedLives.put(member.getCampaignSlot(), member.getRemainingLives());
            if(previous != null && member.getRemainingLives() < previous) {
                beginNewLife(game, member, member == local);
            }
            presentRemote(member, member == local);
        }
        if(local == null) return;

        boolean incapacitated = local.getState() == PartyMemberState.DOWNED
                || local.getState() == PartyMemberState.SPECTATING;
        game.player.setMultiplayerIncapacitated(incapacitated);
        if(incapacitated) {
            releaseRevival();
            prompt = incapacitatedPrompt(status, local);
            return;
        }
        updateRevival(game.player, input, status, local, deltaSeconds);
    }

    /** Centered status line for the local Participant, or null. */
    public String getPrompt() {
        return prompt;
    }

    public void dispose(Game game) {
        releaseRevival();
        if(game == null || game.player == null) return;
        game.player.deathDeferredToAuthority = false;
        game.player.setMultiplayerIncapacitated(false);
    }

    private void updateRevival(Player player, GameInput input, PartyStatusSnapshot status,
            PartyMemberStatus local, float deltaSeconds) {
        PartyMemberStatus target = nearestDowned(player, status, local);
        if(target == null || peer.isSessionPaused()) {
            releaseRevival();
            return;
        }
        boolean mine = target.getReviverSlot() == local.getCampaignSlot();
        if(!input.isActionRequested(Action.USE)) {
            releaseRevival();
            prompt = target.getReviverSlot() != 0 && !mine
                    ? target.getNickname() + " IS BEING REVIVED"
                    : "HOLD USE TO REVIVE " + target.getNickname();
            return;
        }
        secondsSinceIntent += Math.max(0f, deltaSeconds);
        if(intendedRevivalSlot != target.getCampaignSlot()
                || (!mine && secondsSinceIntent >= INTENT_RESEND_SECONDS)) {
            // Host may have interrupted; holding Use simply starts a fresh Revival.
            intendedRevivalSlot = target.getCampaignSlot();
            secondsSinceIntent = 0f;
            peer.submitReviveIntent(intendedRevivalSlot, true);
        }
        prompt = mine ? "REVIVING " + target.getNickname() + "  "
                + seconds(remaining(target.getRevivalTicks())) + "s"
                : "HOLD STILL TO REVIVE " + target.getNickname();
    }

    private void releaseRevival() {
        if(intendedRevivalSlot == 0) return;
        peer.submitReviveIntent(intendedRevivalSlot, false);
        intendedRevivalSlot = 0;
        secondsSinceIntent = 0f;
    }

    private PartyMemberStatus nearestDowned(Player player, PartyStatusSnapshot status,
            PartyMemberStatus local) {
        PartyMemberStatus nearest = null;
        float nearestDistance = REVIVAL_PROMPT_REACH * REVIVAL_PROMPT_REACH;
        for(PartyMemberStatus member : status.getMembers()) {
            if(member == local || member.getState() != PartyMemberState.DOWNED) continue;
            RemoteAvatar avatar = movement.getRemoteAvatar(participantId(member));
            if(avatar == null || Math.abs(avatar.z - player.z) > 1f) continue;
            float x = avatar.x - player.x, y = avatar.y - player.y;
            float distance = x * x + y * y;
            if(distance > nearestDistance) continue;
            nearest = member;
            nearestDistance = distance;
        }
        return nearest;
    }

    private String incapacitatedPrompt(PartyStatusSnapshot status, PartyMemberStatus local) {
        if(local.getState() == PartyMemberState.SPECTATING) return "NO LIVES REMAINING";
        if(local.getReviverSlot() != 0) {
            PartyMemberStatus reviver = status.getMember(local.getReviverSlot());
            return (reviver == null ? "A TEAMMATE" : reviver.getNickname()) + " IS REVIVING YOU  "
                    + seconds(remaining(local.getRevivalTicks())) + "s";
        }
        int livesAfter = Math.max(0, local.getRemainingLives() - 1);
        return "DOWNED - BLEEDOUT IN " + seconds(remaining(local.getBleedoutTicks())) + "s  ("
                + livesAfter + (livesAfter == 1 ? " LIFE" : " LIVES") + " AFTER THIS)";
    }

    /** Host publishes timing on transitions only; presentation counts down in unpaused time. */
    private int remaining(int publishedTicks) {
        return Math.max(0, publishedTicks - (int)(secondsSinceStatus * TICKS_PER_SECOND));
    }

    private static int seconds(int ticks) {
        return (int)Math.ceil(ticks / TICKS_PER_SECOND);
    }

    private void presentRemote(PartyMemberStatus member, boolean local) {
        if(local) return;
        RemoteAvatar avatar = movement.getRemoteAvatar(participantId(member));
        if(avatar != null) avatar.setIncapacitated(member.getState() == PartyMemberState.DOWNED
                || member.getState() == PartyMemberState.SPECTATING);
    }

    /** A consumed Life: selected hotbar item stays behind and no temporary effect survives. */
    private void beginNewLife(Game game, PartyMemberStatus member, boolean local) {
        Actor actor = local ? game.player : movement.getRemoteAvatar(participantId(member));
        if(actor != null && peer instanceof DirectConnectHost) {
            // Host owns native status effects for every Participant; observers follow its snapshots.
            boolean authority = actor.hasStatusEffectAuthority();
            actor.setStatusEffectAuthority(true);
            actor.clearStatusEffects();
            actor.setStatusEffectAuthority(authority);
            actor.drunkMod = 0f;
        }
        if(!local) return;
        Player player = game.player;
        player.attackCharge = 0;
        player.drunkMod = 0f;
        if(player.selectedBarItem != null) {
            player.dropItem(player.selectedBarItem, game.level, 0.075f);
        }
    }

    private static ParticipantId participantId(PartyMemberStatus member) {
        return new ParticipantId("campaign-slot-" + member.getCampaignSlot());
    }
}
