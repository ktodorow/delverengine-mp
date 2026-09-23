package com.interrupt.dungeoneer.multiplayer.combat;

/** Host classification of the last damage that reached a Participant; decides Life-loss item fate. */
public enum DeathCause {
    /** Melee, arrows, magic missiles, poison, or anything else that keeps every item intact. */
    COMBAT,
    EXPLOSION,
    LAVA,
    BURNING,
    FALL,
    TRAP,
    FROST,
    DROWNING
}
