package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Render-thread lookup; only Host-owned inventory identities can select weapon data. */
public interface CombatWeaponResolver {
    default void synchronizeEquipment(ParticipantId participant, com.interrupt.dungeoneer.entities.Player player) { }
    long identity(Weapon weapon);
    default long physicalIdentity(Entity entity) { return 0L; }
    default Entity physicalEntity(long entityId) { return null; }

    default long worldObjectIdentity(Entity entity) { return 0L; }

    default Entity worldObject(long entityId) { return null; }
    default com.interrupt.dungeoneer.entities.projectiles.Missile takeOwnedMissile(
            ParticipantId participant) { return null; }
    Weapon ownedWeapon(ParticipantId participant, long entityId);
}
