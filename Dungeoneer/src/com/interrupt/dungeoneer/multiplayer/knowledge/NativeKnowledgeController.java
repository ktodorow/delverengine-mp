package com.interrupt.dungeoneer.multiplayer.knowledge;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Render-thread adapter: native visibility writes only accepted living Slot's overlay. */
public final class NativeKnowledgeController {
    private final DirectConnectPeer peer;
    private final DirectConnectHost host;
    private final Player observer = new Player();
    private final Map<ParticipantId, String> lastReveal = new HashMap<>();
    private Level lastLevel;

    public NativeKnowledgeController(DirectConnectPeer peer) {
        this.peer = peer;
        host = peer instanceof DirectConnectHost ? (DirectConnectHost)peer : null;
    }

    public void prepare(Game game) {
        game.level.personalMapManaged = true;
        PersonalKnowledge knowledge = peer.getPersonalKnowledge();
        if(knowledge == null) return;
        knowledge.applyTo(game.player);
        knowledge.map(peer.getStatus().getFloorId(), game.level.width, game.level.height).applyTo(game.level);
    }

    public List<MovementEntityState> mapMarkers(Level level) {
        List<MovementEntityState> result = new java.util.ArrayList<>();
        PersonalKnowledge knowledge = peer.getPersonalKnowledge();
        if(knowledge == null) return result;
        MapKnowledge map = knowledge.map(peer.getStatus().getFloorId(), level.width, level.height);
        List<MovementEntityState> current = peer.getCurrentMovementStates();
        List<com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot> snapshots = peer.getMovementSnapshots();
        for(com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
            if(descriptor.getEntityId().equals(peer.getLocalMovementEntityId())) continue;
            com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus member = peer.getPartyStatus() == null
                    ? null : peer.getPartyStatus().getMember(descriptor.getCampaignSlot());
            if(member == null || member.getState() == com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState.SPECTATING) continue;
            MovementEntityState state = null;
            if(current != null) for(MovementEntityState candidate : current) {
                if(candidate.getEntityId().equals(descriptor.getEntityId())) { state = candidate; break; }
            }
            else if(!snapshots.isEmpty()) state = snapshots.get(snapshots.size() - 1).getEntity(descriptor.getEntityId());
            if(state != null && map.isExplored((int)Math.floor(state.getX()), (int)Math.floor(state.getY()))) result.add(state);
        }
        return result;
    }

    public void update(Game game) {
        if(host == null || peer.isSessionPaused()) return;
        Level level = game.level;
        if(lastLevel != level) { lastLevel = level; lastReveal.clear(); }
        List<MovementEntityState> states = host.getCurrentMovementStates();
        if(states == null) return;
        String floor = peer.getStatus().getFloorId();
        List<MovementEntityState> eligible = new java.util.ArrayList<>();
        for(MovementEntityState state : states) {
            ParticipantId owner = new ParticipantId("campaign-slot-" + state.getEntityId().getValue());
            if(!host.canApplyNativeParticipantEffect(owner)) continue;
            String position = revealKey(state, host.getPersonalKnowledge(owner), floor);
            if(level.mapIsDirty || !position.equals(lastReveal.get(owner))) eligible.add(state);
        }
        if(eligible.isEmpty()) return;
        MapKnowledge visible = MapKnowledge.capture(level);
        Array<Vector2> dirty = new Array<>(level.dirtyMapTiles);
        try {
            for(MovementEntityState state : eligible) {
                ParticipantId owner = new ParticipantId("campaign-slot-" + state.getEntityId().getValue());
                if(!host.canApplyNativeParticipantEffect(owner)) continue;
                PersonalKnowledge knowledge = host.getPersonalKnowledge(owner);
                knowledge.map(floor, level.width, level.height).applyTo(level, false);
                observer.x = state.getX(); observer.y = state.getY(); observer.z = state.getZ();
                level.revealSeenTiles(observer);
                PersonalKnowledge learned = knowledge.learnMap(floor, MapKnowledge.capture(level));
                host.publishPersonalKnowledge(owner, learned);
                lastReveal.put(owner, revealKey(state, learned, floor));
            }
        }
        finally {
            visible.applyTo(level, false);
            level.dirtyMapTiles.clear(); level.dirtyMapTiles.addAll(dirty);
        }
        prepare(game);
    }

    private String revealKey(MovementEntityState state, PersonalKnowledge knowledge, String floor) {
        return floor + ":" + peer.getNativeWorldGeneration() + ":" + (int)state.getX()
                + ":" + (int)state.getY() + ":" + knowledge.revision;
    }
}
