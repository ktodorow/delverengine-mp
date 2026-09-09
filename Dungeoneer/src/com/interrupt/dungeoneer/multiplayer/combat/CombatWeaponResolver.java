package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Render-thread lookup; only Host-owned inventory identities can select weapon data. */
public interface CombatWeaponResolver {
    long identity(Weapon weapon);
    Weapon ownedWeapon(ParticipantId participant, long entityId);
}
