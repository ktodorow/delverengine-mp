package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.Mover;
import com.interrupt.dungeoneer.entities.Player;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** A client's Mover shows the Host's and never moves by itself. */
public class MoverReplicaTest {
    @Test public void replicaIgnoresTouchesAndTriggersButFollowsHost() {
        Mover crusher = new Mover();
        crusher.moverMode = Mover.MoverStartMode.ON_ANY_TOUCH;
        crusher.z = -0.88f;
        crusher.setNetworkReplica(true);

        crusher.encroached((Player)null);
        crusher.onTrigger(null, "");
        assertFalse("A local press or button never starts it", crusher.isMovingForNetwork());

        crusher.applyNetworkState(38.5f, 10.5f, 0.12f, 0f, 0f, 0f, true);
        assertEquals("Host's raised door shows on the client", 0.12f, crusher.z, 0f);
    }
}
