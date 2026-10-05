package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.MonsterSpawner;

/** Asked before a native MonsterSpawner adds Monsters. Only Host may; never a client. */
public interface NativeMonsterSpawnerListener {
    boolean allowSpawn(MonsterSpawner spawner);
}
