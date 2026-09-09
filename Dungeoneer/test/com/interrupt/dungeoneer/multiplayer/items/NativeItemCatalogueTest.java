package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.items.Gold;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.*;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import com.interrupt.managers.StringManager;
import com.interrupt.dungeoneer.game.LocalizedString;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class NativeItemCatalogueTest {
    private HashMap<String, LocalizedString> previousStrings;
    @Before public void strings() {
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        StringManager.localizedStrings.put("items.Gold.defaultNameText", new LocalizedString("Gold", ""));
    }
    @After public void restoreStrings() { StringManager.localizedStrings = previousStrings; }
    @Test
    public void runtimeGoldAbsentFromInitialFloorStillMaterializesOnClient() {
        PhysicalItemState gold = new PhysicalItemState(1L, 1L,
                "3d289dce0c7823af164db61776edda11a44c78ab08d4b5a524e90094f89b6f72",
                null, 1, 1, 0);
        DirectConnectPeer peer = (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getNextItemRequestId")) return 1L;
            if(method.getName().equals("getLocalMovementEntityId")) return new NetworkEntityId(2L);
            if(method.getName().equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(2L),
                            new ParticipantId("campaign-slot-2"), 2, "Client", "humanoid-2"));
            if(method.getName().equals("getPhysicalItems")) return Collections.singletonList(gold);
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == int.class) return 0;
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.level = new Level(4, 4) {
            @Override public void SpawnEntity(com.interrupt.dungeoneer.entities.Entity entity) { entities.add(entity); }
        };
        new DirectConnectItemController(peer).prepare(game);
        assertEquals(1, game.level.entities.size);
        assertTrue(game.level.entities.first() instanceof Gold);
    }
}
