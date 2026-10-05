package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Mover;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Native body blocking across peers, as single-player Delver has between its Player and
 * Monsters. On the Host, native Monsters stop at remote Participants, placed where Host movement
 * has them now rather than where their Avatars are drawn, so a Monster never ends up inside a
 * Participant it cannot see, and native Movers feel them standing on pressure plates and lifts.
 * On a client, its own Player stops at replica Monsters, which stay non-solid for everything else.
 */
public final class NativeMovementBodies implements MovementBodies {
    private static final float RADIUS = 0.2f;
    private static final float HEIGHT = 0.65f;

    private final List<ParticipantBody> participants = new ArrayList<ParticipantBody>();
    private Collection<Monster> replicaMonsters;

    /** Remote Participants' current Host positions; empty on a client. Render thread only. */
    public void updateParticipants(Collection<RemoteAvatar> avatars, List<MovementEntityState> states) {
        participants.clear();
        if(avatars == null || states == null) return;
        for(RemoteAvatar avatar : avatars) {
            // A Life-exhausted Spectator leaves no body behind.
            if(!avatar.isActive || (avatar.isIncapacitated() && avatar.hidden)) continue;
            for(MovementEntityState state : states) {
                if(state.getEntityId().equals(avatar.getDescriptor().getEntityId())) {
                    participants.add(new ParticipantBody(avatar, state.getX(), state.getY(), state.getZ()));
                    break;
                }
            }
        }
    }

    /** Live view of the client's replica Monsters. */
    public void setReplicaMonsters(Collection<Monster> monsters) {
        replicaMonsters = monsters;
    }

    public int getParticipantBodyCount() {
        return participants.size();
    }

    @Override
    public void addColliding(float x, float y, float z, float widthX, float widthY, float height,
            Entity checking, Array<Entity> result) {
        boolean hostMover = checking instanceof Mover && !((Mover)checking).isNetworkReplica();
        if(hostMover || checking instanceof Monster && !((Monster)checking).isNetworkReplica()) {
            // Native Level filters for a Monster or Mover checking against a solid Player.
            if(checking.ignorePlayerCollision || checking.collidesWith == Entity.CollidesWith.staticOnly
                    || checking.collidesWith == Entity.CollidesWith.nonActors) return;
            for(ParticipantBody body : participants) {
                if(overlaps(x, y, z, widthX, widthY, height, body.x, body.y, body.z, RADIUS, RADIUS, HEIGHT)) {
                    result.add(body.avatar);
                }
            }
        }
        else if(checking instanceof Player && replicaMonsters != null
                && Game.instance != null && checking == Game.instance.player) {
            for(Monster monster : replicaMonsters) {
                if(!monster.isNetworkReplica() || !monster.blocksNativeMovement()) continue;
                // Native Level filters for the Player checking against a solid Monster.
                if(monster.ignorePlayerCollision || monster.collidesWith == Entity.CollidesWith.staticOnly
                        || monster.collidesWith == Entity.CollidesWith.nonActors) continue;
                if(overlaps(x, y, z, widthX, widthY, height, monster.x, monster.y, monster.z,
                        monster.collision.x, monster.collision.y, monster.collision.z)) {
                    result.add(monster);
                }
            }
        }
    }

    /** Native Level AABB test of a moving box against an entity's bounds. */
    private static boolean overlaps(float x, float y, float z, float widthX, float widthY, float height,
            float bodyX, float bodyY, float bodyZ, float bodyWidthX, float bodyWidthY, float bodyHeight) {
        return x > bodyX - bodyWidthX - widthX && x < bodyX + bodyWidthX + widthX
                && y > bodyY - bodyWidthY - widthY && y < bodyY + bodyWidthY + widthY
                && z > bodyZ - height && z < bodyZ + bodyHeight;
    }

    private static final class ParticipantBody {
        final RemoteAvatar avatar;
        final float x, y, z;

        ParticipantBody(RemoteAvatar avatar, float x, float y, float z) {
            this.avatar = avatar;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
