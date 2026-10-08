package com.interrupt.dungeoneer.multiplayer.combat;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.spells.Spell;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Corpse;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Monster.MultiplayerAttackKind;
import com.interrupt.dungeoneer.entities.MonsterSpawner;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.ProjectedDecal;
import com.interrupt.dungeoneer.entities.Spikes;
import com.interrupt.dungeoneer.entities.items.Bow;
import com.interrupt.dungeoneer.entities.items.Gun;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.items.Scroll;
import com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile;
import com.interrupt.dungeoneer.entities.projectiles.Missile;
import com.interrupt.dungeoneer.entities.projectiles.Projectile;
import com.interrupt.dungeoneer.entities.projectiles.ProjectileImpactListener;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Render-thread bridge between native Delver combat and Direct Connect authority. */
public final class DirectConnectCombatController implements Player.WeaponAttackListener,
        Player.HealthAuthorityListener, Monster.MultiplayerAttackListener,
        Monster.MultiplayerDamageListener, RemoteAvatar.DamageAuthorityListener,
        ProjectileImpactListener, Spikes.MultiplayerTrapListener {
    private static final float ATTACK_TRACE_STEP = 0.1f;
    private static final float ATTACK_ORIGIN_HEIGHT = 0.35f;

    private final DirectConnectPeer peer;
    private final DirectConnectMovementController movementController;
    private final NativeCombatAuthority nativeAuthority;
    private final List<RemoteAvatar> attachedRemoteAvatars =
            new ArrayList<RemoteAvatar>();
    private final Map<ParticipantId, CombatAction> participantActions =
            new LinkedHashMap<ParticipantId, CombatAction>();
    private final Map<String, Monster> monsters = new LinkedHashMap<String, Monster>();
    private static final int WALK_SPEED_INTERVAL_FRAMES = 15;
    private int walkSpeedFrames;
    /** Host frame a tracked Monster was first seen dead; its slot frees after RETIRE_AFTER_FRAMES. */
    private final Map<Monster, Integer> monsterDeathFrames = new IdentityHashMap<Monster, Integer>();
    private static final int RETIRE_AFTER_FRAMES = 120;
    private int monsterFrame;
    private final Map<Monster, String> monsterIds = new IdentityHashMap<Monster, String>();
    private final Set<String> boundMonsterIds = new LinkedHashSet<String>();
    private final Set<String> restoredAuthoritativeActors = new LinkedHashSet<String>();
    private final Map<String, com.interrupt.dungeoneer.entities.Actor> restoredParticipantEffects =
            new LinkedHashMap<>();
    /** Floor whose initial hostile Monsters have been keyed; later arrivals get Host-assigned ids. */
    private Level monstersAttachedLevel;
    private int monsterIndexCounter;
    private int nativeDecalCounter;
    /** Floor whose saved late Monsters Host has recreated after a cold resume. */
    private Level lateMonstersRestoredLevel;
    /** Floor MonsterSpawners keyed by placement at attach, before any of them can move or fire. */
    private final Map<MonsterSpawner, String> monsterSpawnerKeys =
            new IdentityHashMap<MonsterSpawner, String>();
    private final Map<Monster, String> devSpawnThemes = new IdentityHashMap<Monster, String>();
    /** Host: dead Monsters whose slot went to newer ones; never announced again. */
    private final Map<Monster, Boolean> retiredMonsters = new IdentityHashMap<Monster, Boolean>();
    /**
     * Host: resumed combatants with no native Monster left to track, oldest first. A floor
     * checkpoint keeps only living Monsters, so one that died before the save stays in the
     * encounter and effects table as dead until its slot is needed.
     */
    private final List<String> orphanedMonsterIds = new ArrayList<String>();
    private int orphansAdoptedFrame;
    private final Set<String> loggedPresentationFailures = new LinkedHashSet<String>();
    /** Host: native dynamics that could not be described; skipped so they never stop the session. */
    private final Map<Entity, Boolean> unpresentableDynamics = new IdentityHashMap<Entity, Boolean>();
    private final Map<Monster, CombatAction> lastMonsterActions =
            new IdentityHashMap<Monster, CombatAction>();
    private final Map<String, Spikes> traps = new LinkedHashMap<String, Spikes>();
    private final Map<Spikes, String> trapIds = new IdentityHashMap<Spikes, String>();
    private final Map<com.interrupt.dungeoneer.entities.Actor, NativeStatusPresentation> participantEffects =
            new IdentityHashMap<com.interrupt.dungeoneer.entities.Actor, NativeStatusPresentation>();
    private long participantEffectGeneration;
    private CombatWeaponResolver weaponResolver;
    private com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgressResolver progressResolver;
    private final Map<Monster, ParticipantId> lastParticipantAttackers =
            new java.util.WeakHashMap<Monster, ParticipantId>();
    private final Map<ParticipantId, Player> authoritativePlayers =
            new LinkedHashMap<ParticipantId, Player>();
    private final Map<Entity, ProjectilePresentation> projectilePresentations =
            new IdentityHashMap<Entity, ProjectilePresentation>();
    private final Map<Entity, Long> nativeDynamicIds =
            new IdentityHashMap<Entity, Long>();
    private final Map<Long, Long> nativeDynamicItemIds =
            new LinkedHashMap<Long, Long>();
    private final Map<Long, Entity> nativeDynamicReplicas =
            new LinkedHashMap<Long, Entity>();
    private final Set<Entity> createdNativeDynamicReplicas =
            Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
    private long nativeDynamicGeneration;
    private long nextNativeDynamicId = 1L;
    private long pendingNativeSpellItemId;
    private Level attachedLevel;
    private Player attachedPlayer;
    private int lastLocalHealth = -1;
    private long lastPresentationSequence;
    private long nextRequestId;

    public DirectConnectCombatController(DirectConnectPeer peer, boolean useOwnedAssets) {
        this(peer, null, useOwnedAssets);
    }

    public DirectConnectCombatController(DirectConnectPeer peer,
            DirectConnectMovementController movementController, boolean useOwnedAssets) {
        if(peer == null) throw new IllegalArgumentException("Direct Connect peer cannot be null.");
        this.peer = peer;
        this.movementController = movementController;
        // A client's own Player stops at replica Monsters as native Player stops at Monsters.
        if(movementController != null) movementController.getMovementBodies().setReplicaMonsters(monsters.values());
        nativeAuthority = peer instanceof NativeCombatAuthority
                ? (NativeCombatAuthority)peer : null;
        nextRequestId = peer.getNextCombatRequestId();
        if(nextRequestId < 1L) {
            throw new IllegalArgumentException("Combat request ID floor must be positive.");
        }
    }

    /** Must run before Level.tick so native Monster AI sees authoritative role and target. */
    public void prepare(Game game) {
        if(game == null || game.player == null || game.level == null) return;
        if(attachedLevel != game.level) attachToLevel(game.level);
        if(attachedPlayer != game.player) attachToPlayer(game.player);
        attachNativeMonstersIfPresent();
        if(nativeAuthority != null) {
            restoreLateNativeMonsters(game);
            attachLateNativeMonsters();
        }
        else {
            materializeNativeMonsterSpawns(game);
            discardUnannouncedNativeMonsters();
            materializeNativeDecals();
        }
        attachNativeTrapsIfPresent();
        attachNativeProjectiles();
        if(nativeAuthority == null) applyNativeDynamicStates(game.level);
        applySnapshot(peer.getCombatSnapshot());
        restoreAuthoritativeEffects();
        synchronizeWorldTime(game);
    }

    private void synchronizeWorldTime(Game game) {
        float scale = 1f;
        if(nativeAuthority != null) {
            if(attachedPlayer != null) scale = ActorEffectsSnapshot.worldTimeScale(attachedPlayer);
            for(RemoteAvatar avatar : attachedRemoteAvatars)
                scale = Math.min(scale, ActorEffectsSnapshot.worldTimeScale(avatar));
        } else {
            for(ActorEffectsSnapshot state : peer.getActorEffects())
                scale = Math.min(scale, state.worldTimeScale);
        }
        game.SetGameTimeScale(scale);
    }

    /** Runs after movement reconciliation and native Level.tick. */
    public void update(Game game) {
        if(game == null || game.player == null || game.level == null) return;
        if(nativeAuthority != null && !peer.isSessionPaused() && attachedLevel == game.level) {
            // Native restore/consumable effects may change hp directly during the tick.
            synchronizeNativeParticipantHealth(attachedPlayer, localParticipantId());
            for(RemoteAvatar avatar : attachedRemoteAvatars)
                synchronizeNativeParticipantHealth(avatar, avatar.getDescriptor().getParticipantId());
        }
        prepare(game);
        if(nativeAuthority != null) {
            resolveNativeCombatRequests();
            synchronizeNativeMonsters();
            if(attachedPlayer != null && localParticipantId() != null)
                nativeAuthority.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(
                        localCombatantId(), 1, attachedPlayer));
            for(RemoteAvatar avatar : attachedRemoteAvatars)
                nativeAuthority.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(
                        AuthoritativeCombatEncounter.participantTargetId(avatar.getDescriptor().getParticipantId()),
                        1, avatar));
            synchronizeNativeWalkSpeeds();
            attachNativeProjectiles();
            synchronizeNativeDynamics();
            synchronizeNativeDecals();
        }
        applySnapshot(peer.getCombatSnapshot());
        synchronizeWorldTime(game);
        replayPresentations(game.level, localCombatantId());
        if(nativeAuthority == null) {
            for(NativeAnimationCue cue : peer.drainNativeAnimationCues()) cue.replay();
            replayNativeSpellPresentations(localCombatantId());
            replayNativeMeleePresentations(localCombatantId());
            replayNativeRangedPresentations(localCombatantId());
            for(NativeDynamicCue cue : peer.drainNativeDynamicCues()) cue.replay(game.level);
            for(NativeExplosionPresentation explosion : peer.drainNativeExplosions()) explosion.replay(game.level);
        }
    }

    public void setWeaponResolver(CombatWeaponResolver resolver) { weaponResolver = resolver; }

    public void setProgressResolver(
            com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgressResolver resolver) {
        progressResolver = resolver;
    }

    /** Participant whose accepted native damage last hurt this Host Monster, if any. */
    public ParticipantId lastParticipantAttacker(Monster monster) {
        return monster == null ? null : lastParticipantAttackers.get(monster);
    }

    public boolean consumeNativeItem(ParticipantId participant, Item item) {
        return consumeNativeItem(participant, item, null);
    }

    public boolean consumeNativeItem(ParticipantId participant, Item item,
            com.badlogic.gdx.math.Vector3 direction) {
        if(nativeAuthority == null || !nativeAuthority.canApplyNativeParticipantEffect(participant)) return false;
        com.interrupt.dungeoneer.entities.Actor target = participant.equals(localParticipantId())
                ? attachedPlayer : remoteAvatar(AuthoritativeCombatEncounter.participantTargetId(participant));
        if(target == null) return false;
        if(item instanceof com.interrupt.dungeoneer.entities.items.Potion) {
            com.interrupt.dungeoneer.entities.items.Potion potion = (com.interrupt.dungeoneer.entities.items.Potion)item;
            potion.applyNativeEffect(target);
            Player owner = authoritativePlayer(participant);
            DirectConnectHost host = (DirectConnectHost)peer;
            host.getPersonalKnowledge(participant).applyTo(owner);
            if(potion.discoverOnDrink(owner))
                host.publishPersonalKnowledge(participant, host.getPersonalKnowledge(participant).learn(owner));
        }
        else if(item instanceof com.interrupt.dungeoneer.entities.items.Food)
            ((com.interrupt.dungeoneer.entities.items.Food)item).applyNativeEffect(target);
        else if(item instanceof com.interrupt.dungeoneer.entities.items.Scroll) {
            if(direction == null) return false;
            com.interrupt.dungeoneer.entities.items.Scroll scroll =
                    (com.interrupt.dungeoneer.entities.items.Scroll)item;
            if(scroll.spell instanceof com.interrupt.dungeoneer.entities.spells.Identify
                    || scroll.spell instanceof com.interrupt.dungeoneer.entities.spells.FillMap) {
                DirectConnectHost host = (DirectConnectHost)peer;
                Player owner = target instanceof Player ? (Player)target : authoritativePlayer(participant);
                owner.x = target.x; owner.y = target.y; owner.z = target.z;
                if(target instanceof RemoteAvatar) owner.multiplayerDamageSource = participant.getValue();
                if(target instanceof RemoteAvatar && weaponResolver != null) weaponResolver.synchronizeInventory(participant, owner);
                host.getPersonalKnowledge(participant).applyTo(owner);
                com.interrupt.dungeoneer.game.Level level = attachedLevel;
                com.interrupt.dungeoneer.multiplayer.knowledge.MapKnowledge visible =
                        com.interrupt.dungeoneer.multiplayer.knowledge.MapKnowledge.capture(level);
                com.badlogic.gdx.utils.Array<com.badlogic.gdx.math.Vector2> dirty = new com.badlogic.gdx.utils.Array<>(level.dirtyMapTiles);
                String floor = peer.getStatus().getFloorId();
                long previousSpellItemId = pendingNativeSpellItemId;
                pendingNativeSpellItemId = weaponResolver == null ? 0L : weaponResolver.physicalIdentity(item);
                try {
                    host.getPersonalKnowledge(participant).map(floor, level.width, level.height).applyTo(level, false);
                    scroll.applyNativeEffect(owner, direction);
                    com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge learned =
                            host.getPersonalKnowledge(participant).learn(owner);
                    if(scroll.spell instanceof com.interrupt.dungeoneer.entities.spells.FillMap)
                        learned = learned.learnMap(floor, com.interrupt.dungeoneer.multiplayer.knowledge.MapKnowledge.capture(level));
                    host.publishPersonalKnowledge(participant, learned);
                }
                finally {
                    pendingNativeSpellItemId = previousSpellItemId;
                    visible.applyTo(level, false);
                    level.dirtyMapTiles.clear(); level.dirtyMapTiles.addAll(dirty);
                }
                return true;
            }
            if(target instanceof RemoteAvatar && isPersonalPlayerSpell(scroll.spell)) return false;
            float previousX = target.x, previousY = target.y, previousZ = target.z;
            long previousSpellItemId = pendingNativeSpellItemId;
            pendingNativeSpellItemId = weaponResolver == null ? 0L
                    : weaponResolver.physicalIdentity(item);
            try {
                scroll.applyNativeEffect(target, direction);
            }
            finally { pendingNativeSpellItemId = previousSpellItemId; }
            if(target.x != previousX || target.y != previousY || target.z != previousZ) {
                nativeAuthority.setNativeParticipantPosition(participant,
                        target.x, target.y, target.z);
            }
        }
        else return false;
        synchronizeNativeParticipantHealth(target, participant);
        nativeAuthority.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(
                AuthoritativeCombatEncounter.participantTargetId(participant), 1, target));
        return true;
    }

    private static boolean isPersonalPlayerSpell(Spell spell) {
        return spell instanceof com.interrupt.dungeoneer.entities.spells.Identify
                || spell instanceof com.interrupt.dungeoneer.entities.spells.FillMap
                || spell instanceof com.interrupt.dungeoneer.entities.spells.EnchantWeapon
                || spell instanceof com.interrupt.dungeoneer.entities.spells.EnchantArmor;
    }

    public void dispose() {
        if(Game.instance != null) Game.instance.SetGameTimeScale(1f);
        detachFromPlayer();
        detachFromLevel();
        restoredParticipantEffects.clear();
    }

    public Monster getMonster() {
        return monsters.isEmpty() ? null : monsters.values().iterator().next();
    }

    public List<Monster> getMonsters() {
        return Collections.unmodifiableList(new ArrayList<Monster>(monsters.values()));
    }

    public long getLastPresentationSequence() { return lastPresentationSequence; }

    @Override
    public void onWeaponAttack(Weapon weapon, Vector3 direction) {
        onWeaponAttack(weapon, direction, 1f);
    }

    @Override
    public void onWeaponAttack(Weapon weapon, Vector3 direction, float attackPower) {
        if(weapon == null || !isUsableDirection(direction) || !hasRequestId()) return;
        if(!isFinite(attackPower) || attackPower < 0f
                || attackPower > CombatRequest.MAX_ATTACK_POWER) return;
        CombatAction action = classify(weapon, weapon.getDamageType());
        ParticipantId participantId = localParticipantId();
        if(participantId != null) participantActions.put(participantId, action);
        long weaponId = weaponResolver == null ? 0L : weaponResolver.identity(weapon);
        if(weaponResolver != null && weaponId == 0L) return;
        peer.submitCombatAction(takeNextRequestId(), action,
                direction.x, direction.z, direction.y, attackPower, weaponId);
    }

    @Override
    public boolean deferWeaponWorldAttack() { return nativeAuthority == null; }

    @Override
    public boolean onHealthIntent(Player player, int amount, DamageType damageType,
            Entity instigator) {
        ParticipantId participantId = localParticipantId();
        String targetId = participantId == null ? null
                : AuthoritativeCombatEncounter.participantTargetId(participantId);
        if(player == null || targetId == null || amount == 0) return false;
        ParticipantId participantSource = participantId(instigator);
        if(nativeAuthority != null) {
            applyNativeParticipantDamage(player, participantId, amount, damageType, instigator);
            return true;
        }
        if(amount > 0 && damageType != DamageType.HEALING && participantSource != null
                && !participantSource.equals(participantId)) return true;
        CombatAction action;
        if(damageType == DamageType.HEALING || amount < 0) {
            action = CombatAction.BENEFICIAL_SPELL;
        }
        else if(instigator == player || participantSource != null) {
            action = CombatAction.SELF_DAMAGE;
        }
        else {
            action = CombatAction.ENVIRONMENTAL_HAZARD;
        }
        if(!hasRequestId()) return true;
        peer.submitCombatAction(takeNextRequestId(), action, targetId);
        return true;
    }

    @Override
    public void onDamageIntent(RemoteAvatar avatar, int damage, DamageType damageType,
            Entity instigator) {
        if(nativeAuthority == null || avatar == null || damage == 0) return;
        applyNativeParticipantDamage(avatar, avatar.getDescriptor().getParticipantId(),
                damage, damageType, instigator);
    }

    @Override
    public boolean onPhysicsImpulseIntent(Player player, Vector3 impulse) {
        ParticipantId participant = localParticipantId();
        if(participant == null || impulse == null) return false;
        if(nativeAuthority != null)
            nativeAuthority.applyNativeParticipantImpulse(participant, impulse.x, impulse.y, impulse.z);
        return true;
    }

    @Override
    public boolean onPhysicsImpulseIntent(RemoteAvatar avatar, Vector3 impulse) {
        if(avatar == null || impulse == null) return false;
        if(nativeAuthority != null) nativeAuthority.applyNativeParticipantImpulse(
                avatar.getDescriptor().getParticipantId(), impulse.x, impulse.y, impulse.z);
        return true;
    }

    /**
     * Forwards health the native tick changed directly (restore, consumables, effects) since
     * the last snapshot this replica applied. Comparing against the live snapshot instead would
     * turn a Host-thread change made mid-frame, such as a respawn restore, into native damage.
     */
    private void synchronizeNativeParticipantHealth(com.interrupt.dungeoneer.entities.Actor actor,
            ParticipantId participant) {
        if(actor == null || participant == null) return;
        int applied = actor == attachedPlayer ? lastLocalHealth
                : actor instanceof RemoteAvatar ? ((RemoteAvatar)actor).getAppliedHealth() : -1;
        if(applied < 0 || !nativeAuthority.canApplyNativeParticipantEffect(participant)) return;
        int amount = applied - Math.max(0, actor.hp);
        if(amount == 0) return;
        nativeAuthority.applyNativeParticipantDamage("native-effect", participant,
                amount, actor.x, actor.y, actor.z);
        if(actor == attachedPlayer) lastLocalHealth = Math.max(0, actor.hp);
        else ((RemoteAvatar)actor).applyAuthoritativeHealth(actor.hp, actor.maxHp);
    }

    private void applyNativeParticipantDamage(com.interrupt.dungeoneer.entities.Actor target,
            ParticipantId targetId, int amount, DamageType damageType, Entity instigator) {
        if(!nativeAuthority.canApplyNativeParticipantEffect(targetId)) return;
        ParticipantId source = participantId(instigator);
        if(amount > 0 && damageType != DamageType.HEALING && source != null
                && !source.equals(targetId)) return;
        Monster monster = nativeMonsterInstigator(instigator);
        Spikes trap = nativeTrapInstigator(instigator);
        String sourceId = source != null ? AuthoritativeCombatEncounter.participantTargetId(source)
                : monster != null ? monsterIds.get(monster)
                : trap != null ? trapIds.get(trap) : "native-world";
        if(sourceId == null) sourceId = "native-world";
        if(target instanceof RemoteAvatar) ((RemoteAvatar)target).setNativeCombatStats(authoritativePlayer(targetId));
        DeathCause cause = classifyCause(target, damageType, instigator, trap != null);
        int accepted = target.applyNativeDamage(amount, damageType, instigator);
        nativeAuthority.applyNativeParticipantDamage(sourceId, targetId, accepted,
                target.x, target.y, target.z, cause);
        nativeAuthority.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(
                AuthoritativeCombatEncounter.participantTargetId(targetId), 1, target));
    }

    @Override
    public void addMultiplayerTargets(Spikes spikes, Array<Entity> colliding) {
        if(nativeAuthority == null || spikes == null || colliding == null) return;
        for(RemoteAvatar avatar : attachedRemoteAvatars) {
            if(avatar.isActive && overlaps(spikes, avatar)
                    && !colliding.contains(avatar, true)) colliding.add(avatar);
        }
    }

    @Override
    public boolean hasNearbyMovingMultiplayerTarget(Spikes spikes,
            Vector3 sensorSize) {
        if(nativeAuthority == null || spikes == null || sensorSize == null) return false;
        float sensorZ = spikes.z - sensorSize.z * 0.5f;
        for(RemoteAvatar avatar : attachedRemoteAvatars) {
            if(!avatar.isActive || Math.abs(avatar.xa) <= 0.01f
                    && Math.abs(avatar.ya) <= 0.01f) continue;
            if(Math.abs(avatar.x - spikes.x) < sensorSize.x + avatar.collision.x
                    && Math.abs(avatar.y - spikes.y) < sensorSize.y + avatar.collision.y
                    && Math.abs(avatar.z - sensorZ) < sensorSize.z + avatar.collision.z) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onTrapActivated(Spikes spikes) {
        String trapId = trapIds.get(spikes);
        if(nativeAuthority == null || trapId == null) return;
        nativeAuthority.publishNativePresentation(trapId, "",
                CombatAction.ENVIRONMENTAL_HAZARD,
                CombatPresentationPhase.ATTACK,
                spikes.x, spikes.y, spikes.z,
                spikes.x, spikes.y, spikes.z, false);
    }

    @Override
    public void onAttackStarted(Monster source, Entity target,
            MultiplayerAttackKind kind) {
        String monsterId = monsterIds.get(source);
        if(nativeAuthority == null || monsterId == null || target == null) return;
        CombatAction action = combatAction(kind);
        lastMonsterActions.put(source, action);
        nativeAuthority.publishNativePresentation(
                monsterId, combatantId(target), action,
                CombatPresentationPhase.ATTACK,
                source.x, source.y, source.z + ATTACK_ORIGIN_HEIGHT,
                target.x, target.y, target.z + ATTACK_ORIGIN_HEIGHT, false);
    }

    @Override
    public void onDamageApplied(Monster damagedMonster, Entity instigator,
            int damage, DamageType damageType) {
        String monsterId = monsterIds.get(damagedMonster);
        if(nativeAuthority == null || monsterId == null || damage <= 0) return;
        ParticipantId sourceId = participantId(instigator);
        if(sourceId != null) {
            lastParticipantAttackers.put(damagedMonster, sourceId);
            nativeAuthority.recordNativeMonsterAttacker(monsterId, sourceId);
            CombatAction action = participantActions.get(sourceId);
            if(action == null) action = classify(null, damageType);
            Entity source = participantEntity(sourceId);
            float originX = source == null ? damagedMonster.x : source.x;
            float originY = source == null ? damagedMonster.y : source.y;
            float originZ = source == null ? damagedMonster.z + ATTACK_ORIGIN_HEIGHT
                    : source.z + ATTACK_ORIGIN_HEIGHT;
            nativeAuthority.publishNativePresentation(
                    AuthoritativeCombatEncounter.participantTargetId(sourceId),
                    monsterId, action, CombatPresentationPhase.DAMAGE,
                    originX, originY, originZ,
                    damagedMonster.x, damagedMonster.y,
                    damagedMonster.z + ATTACK_ORIGIN_HEIGHT, true);
        }
        if(damagedMonster.hp > 0) synchronizeNativeMonster(monsterId, damagedMonster);
    }

    @Override
    public void onProjectileBreak(Entity projectile) {
        if(nativeAuthority == null || !(projectile instanceof Missile)
                || projectile.nativePresentationReplica) return;
        Long dynamicId = ensureNativeDynamicIdentity(projectile);
        if(dynamicId == null) return;
        try {
            nativeAuthority.publishNativeDynamicCue(NativeDynamicCue.captureBreak(dynamicId,
                    nativeDynamicItemIds.containsKey(dynamicId) ? nativeDynamicItemIds.get(dynamicId) : 0L,
                    (Missile)projectile));
        }
        catch(IllegalArgumentException invalid) {
            skipNativePresentation(invalid);
        }
    }

    @Override
    public void onProjectileImpact(Entity projectile, Entity hit,
            float impactX, float impactY, float impactZ) {
        // Bomb-spawned arrows have no attacker and therefore no trail presentation; their
        // wall hits still need to show on every peer.
        if(nativeAuthority == null || projectile.nativePresentationReplica) return;
        Long dynamicId = ensureNativeDynamicIdentity(projectile);
        if(dynamicId != null) {
            try {
                nativeAuthority.publishNativeDynamicCue(NativeDynamicCue.captureImpact(
                        dynamicId, nativeDynamicItemIds.containsKey(dynamicId)
                                ? nativeDynamicItemIds.get(dynamicId) : 0L, projectile,
                        hit != null, impactX, impactY, impactZ));
            }
            catch(IllegalArgumentException invalid) {
                skipNativePresentation(invalid);
            }
        }
        ProjectilePresentation presentation = projectilePresentations.get(projectile);
        if(presentation == null) return;
        nativeAuthority.publishNativePresentation(
                presentation.sourceId, impactTargetId(hit), presentation.action,
                CombatPresentationPhase.IMPACT,
                presentation.originX, presentation.originY, presentation.originZ,
                impactX, impactY, impactZ, false, presentation.visual);
    }

    void replayPresentations(Level level, String localCombatantId) {
        if(level == null) return;
        for(CombatPresentationEvent event : peer.getCombatPresentationEvents()) {
            if(event.getSequence() <= lastPresentationSequence) continue;
            lastPresentationSequence = event.getSequence();
            boolean damageAcknowledgement =
                    event.getPhase() == CombatPresentationPhase.DAMAGE;
            if(replayTrapActivation(event)) continue;
            if(isHostNativeMonsterPresentation(event)) continue;
            if(isLocalNativePresentation(event, localCombatantId)
                    && !damageAcknowledgement) continue;
            animateCombatants(event, damageAcknowledgement);
            if(damageAcknowledgement || suppressHostNativeProjectileEffect(event)) continue;
            level.addEntity(new CombatPresentationEffect(event));
            if(event.getPhase() == CombatPresentationPhase.IMPACT) {
                level.addEntity(new CombatImpactDecal(event));
            }
        }
    }

    private void resolveNativeCombatRequests() {
        for(CombatRequest request : nativeAuthority.drainNativeCombatRequests()) {
            ParticipantId participantId = request.getParticipantId();
            CombatAction action = request.getAction();
            participantActions.put(participantId, action);
            Entity source = participantEntity(participantId);
            if(source == null || !source.isActive) continue;
            Weapon weapon = weaponResolver == null ? authoritativeWeapon(participantId, action)
                    : weaponResolver.ownedWeapon(participantId, request.getWeaponEntityId());
            if(weapon == null || classify(weapon, weapon.getDamageType()) != action) continue;
            float maximumRange = action == CombatAction.MELEE && weapon != null
                    ? Math.max(ATTACK_TRACE_STEP, weapon.reach * 1.5f)
                    : action.getMaximumRange();
            NativeTrace trace = traceMonsters(source, request, maximumRange);
            if(action == CombatAction.MELEE) nativeAuthority.publishNativePresentation(
                    AuthoritativeCombatEncounter.participantTargetId(participantId),
                    trace.monsterId == null ? "" : trace.monsterId,
                    action, CombatPresentationPhase.ATTACK,
                    trace.originX, trace.originY, trace.originZ,
                    trace.impactX, trace.impactY, trace.impactZ, false);
            boolean localParticipant = participantId.equals(localParticipantId());
            if(!localParticipant && weapon instanceof Sword) {
                publishRemoteMelee(participantId, request.getWeaponEntityId(), (Sword)weapon,
                        NativeMeleePresentation.Kind.SWING,
                        new Vector3(source.x, source.y, source.z),
                        new Vector3(trace.directionX, trace.directionY, trace.directionZ));
            }
            if(localParticipant) continue;
            if(action == CombatAction.MELEE && weapon instanceof Sword) {
                ((Sword)weapon).resolveNetworkAttack(source,
                        authoritativePlayer(participantId), attachedLevel,
                        new Vector3(trace.directionX, trace.directionY, trace.directionZ),
                        request.getAttackPower(), target -> !(target instanceof Player)
                                && !(target instanceof RemoteAvatar));
                continue;
            }
            if(action == CombatAction.MELEE
                    && (trace.monster != null || trace.corpse != null) && weapon != null) {
                Player stats = authoritativePlayer(participantId);
                int damage = weapon.doAttackRoll(request.getAttackPower(), stats);
                float knockback = request.getAttackPower()
                        * (weapon.knockback + stats.getKnockbackStatBoost());
                if(trace.monster != null) {
                    trace.monster.hit(trace.directionX, trace.directionY, damage,
                            knockback, weapon.getDamageType(), source);
                }
                else {
                    trace.corpse.hit(trace.directionX, trace.directionY, damage,
                            knockback, weapon.getDamageType(), source);
                }
                if(weapon instanceof Sword) publishRemoteMelee(participantId,
                        request.getWeaponEntityId(), (Sword)weapon,
                        NativeMeleePresentation.Kind.ENTITY_HIT,
                        new Vector3(trace.impactX, trace.impactY, trace.impactZ),
                        new Vector3(trace.directionX, trace.directionY, trace.directionZ));
            }
            else if(action == CombatAction.MELEE && trace.blocked
                    && weapon instanceof Sword) {
                ((Sword)weapon).wasUsed();
                publishRemoteMelee(participantId, request.getWeaponEntityId(), (Sword)weapon,
                        NativeMeleePresentation.Kind.WORLD_HIT,
                        new Vector3(trace.impactX, trace.impactY, trace.impactZ),
                        new Vector3(trace.directionX, trace.directionY, trace.directionZ));
            }
            else if(action == CombatAction.PROJECTILE && weapon instanceof Bow) {
                spawnNativeArrow(source, participantId, request, trace, (Bow)weapon);
            }
            else if(action == CombatAction.SPELL && weapon instanceof Wand) {
                spawnNativeSpell(source, participantId, request, trace, (Wand)weapon);
            }
        }
    }

    private NativeTrace traceMonsters(Entity source, CombatRequest request,
            float maximumRange) {
        return traceMonsters(source, request, maximumRange, ATTACK_ORIGIN_HEIGHT);
    }

    private NativeTrace traceMonsters(Entity source, CombatRequest request,
            float maximumRange, float originHeight) {
        float length = (float)Math.sqrt(request.getAimX() * request.getAimX()
                + request.getAimY() * request.getAimY()
                + request.getAimZ() * request.getAimZ());
        float directionX = request.getAimX() / length;
        float directionY = request.getAimY() / length;
        float directionZ = request.getAimZ() / length;
        float originZ = source.z + originHeight;
        float previousX = source.x;
        float previousY = source.y;
        float previousZ = originZ;

        for(float distance = ATTACK_TRACE_STEP;
                distance <= maximumRange + 0.0001f;
                distance += ATTACK_TRACE_STEP) {
            float x = source.x + directionX * distance;
            float y = source.y + directionY * distance;
            float z = originZ + directionZ * distance;
            if(!hasLineOfSight(source.x, source.y, x, y)) {
                return new NativeTrace(null, null, null, true,
                        directionX, directionY, directionZ,
                        source.x, source.y, originZ, previousX, previousY, previousZ);
            }
            Monster hit = intersectedMonster(x, y, z);
            if(hit != null) {
                return new NativeTrace(hit, null, monsterIds.get(hit), false,
                        directionX, directionY, directionZ,
                        source.x, source.y, originZ, hit.x, hit.y,
                        hit.z + hit.collision.z * 0.5f);
            }
            Corpse corpse = intersectedCorpse(x, y, z);
            if(corpse != null) {
                return new NativeTrace(null, corpse, monsterIdForCorpse(corpse), false,
                        directionX, directionY, directionZ,
                        source.x, source.y, originZ, corpse.x, corpse.y,
                        corpse.z + corpse.collision.z * 0.5f);
            }
            previousX = x;
            previousY = y;
            previousZ = z;
        }
        return new NativeTrace(null, null, null, false,
                directionX, directionY, directionZ,
                source.x, source.y, originZ, previousX, previousY, previousZ);
    }

    private void spawnNativeArrow(Entity source, ParticipantId participantId,
            CombatRequest request, NativeTrace trace, Bow bow) {
        Missile missile = weaponResolver == null ? null
                : weaponResolver.takeOwnedMissile(participantId);
        if(missile == null) return;
        Player stats = authoritativePlayer(participantId);
        bow.configureNetworkMissile(missile, stats, source,
                new Vector3(trace.directionX, trace.directionY, trace.directionZ),
                request.getAttackPower());
        attachedLevel.entities.add(missile);
        attachNativeProjectile(missile);
        bow.playNetworkFirePresentation(source.x, source.y, source.z);
        try {
            nativeAuthority.publishNativeRangedPresentation(NativeRangedPresentation.capture(
                    AuthoritativeCombatEncounter.participantTargetId(participantId),
                    request.getWeaponEntityId(), new Vector3(source.x, source.y, source.z)));
        }
        catch(IllegalArgumentException invalid) {
            skipNativePresentation(invalid);
        }
    }

    private void spawnNativeSpell(Entity source, ParticipantId participantId,
            CombatRequest request, NativeTrace trace, Wand wand) {
        // Preserve configured spell class, colour, appearance, spread, speed, and impact effects.
        Spell spell =
                (Spell)
                        KryoSerializer.copyObject(wand.spell);
        spell.baseDamage = wand.getBaseDamage();
        spell.randDamage = wand.getRandDamage();
        spell.damageType = wand.getDamageType();
        long previousSpellItemId = pendingNativeSpellItemId;
        pendingNativeSpellItemId = request.getWeaponEntityId();
        try {
            spell.zap((com.interrupt.dungeoneer.entities.Actor)source,
                    new Vector3(trace.directionX, trace.directionZ, trace.directionY),
                    new Vector3(source.x, source.y, source.z));
        }
        finally { pendingNativeSpellItemId = previousSpellItemId; }
        attachNativeProjectiles();
    }

    private void replayNativeSpellPresentations(String localCombatantId) {
        for(NativeSpellPresentation presentation : peer.drainNativeSpellPresentations()) {
            if(presentation.sourceId.equals(localCombatantId)) continue;
            if(presentation.itemId == 0L) {
                if(!presentation.sound.isEmpty()) Audio.playPositionedSound(
                        presentation.sound,
                        new Vector3(presentation.x, presentation.y, presentation.z),
                        presentation.volume, presentation.range);
                continue;
            }
            Entity source = presentationSource(presentation.sourceId);
            Entity item = weaponResolver == null ? null
                    : weaponResolver.physicalEntity(presentation.itemId);
            Spell spell = item instanceof Wand ? ((Wand)item).spell
                    : item instanceof Scroll ? ((Scroll)item).spell : null;
            if(!(source instanceof com.interrupt.dungeoneer.entities.Actor) || spell == null) {
                peer.failNativePresentation("Accepted spell item " + presentation.itemId
                        + " is unavailable for native cast presentation.");
                continue;
            }
            Vector3 position = new Vector3(presentation.x, presentation.y, presentation.z);
            if(presentation.zap) spell.playZapPresentation(
                    (com.interrupt.dungeoneer.entities.Actor)source, position);
            else spell.playCastPresentation(
                    (com.interrupt.dungeoneer.entities.Actor)source, position);
        }
    }

    private void publishRemoteMelee(ParticipantId participantId, long itemId, Sword sword,
            NativeMeleePresentation.Kind kind, Vector3 position, Vector3 direction) {
        if(kind == NativeMeleePresentation.Kind.SWING) {
            sword.playNetworkSwingPresentation(position.x, position.y, position.z);
        }
        else if(kind == NativeMeleePresentation.Kind.WORLD_HIT) {
            sword.playNetworkWorldHitPresentation(position.x, position.y, position.z,
                    attachedLevel, direction);
        }
        else sword.playNetworkEntityHitPresentation(position.x, position.y, position.z,
                    attachedLevel);
        try {
            nativeAuthority.publishNativeMeleePresentation(NativeMeleePresentation.capture(
                    AuthoritativeCombatEncounter.participantTargetId(participantId), itemId,
                    kind, position, direction));
        }
        catch(IllegalArgumentException invalid) {
            skipNativePresentation(invalid);
        }
    }

    private void replayNativeMeleePresentations(String localCombatantId) {
        for(NativeMeleePresentation presentation : peer.drainNativeMeleePresentations()) {
            if(presentation.kind == NativeMeleePresentation.Kind.SWING
                    && presentation.sourceId.equals(localCombatantId)) continue;
            Entity item = weaponResolver == null ? null
                    : weaponResolver.physicalEntity(presentation.itemId);
            if(!(item instanceof Sword)) {
                peer.failNativePresentation("Accepted Sword item " + presentation.itemId
                        + " is unavailable for native melee presentation.");
                continue;
            }
            Sword sword = (Sword)item;
            if(presentation.kind == NativeMeleePresentation.Kind.SWING) {
                sword.playNetworkSwingPresentation(presentation.x, presentation.y, presentation.z);
            }
            else if(presentation.kind == NativeMeleePresentation.Kind.WORLD_HIT) {
                sword.playNetworkWorldHitPresentation(presentation.x, presentation.y,
                        presentation.z, attachedLevel, new Vector3(presentation.directionX,
                                presentation.directionY, presentation.directionZ));
            }
            else {
                if(presentation.targetObjectId > 0L) {
                    Entity target = weaponResolver == null ? null
                            : weaponResolver.worldObject(presentation.targetObjectId);
                    if(target == null) {
                        peer.failNativePresentation("Accepted Sword target "
                                + presentation.targetObjectId
                                + " is unavailable for native hit presentation.");
                        continue;
                    }
                    if(target instanceof Breakable) {
                        ((Breakable)target).playNetworkHitPresentation(presentation.x,
                                presentation.y, presentation.z, sword, attachedLevel);
                    }
                    else if(target instanceof Door) {
                        ((Door)target).playNetworkHitPresentation(presentation.x,
                                presentation.y, presentation.z, sword, attachedLevel);
                    }
                    // Other shared objects, such as a solid shopkeeper trigger, have only Sword feedback.
                }
                sword.playNetworkEntityHitPresentation(presentation.x, presentation.y,
                        presentation.z, attachedLevel);
            }
        }
    }

    private void replayNativeRangedPresentations(String localCombatantId) {
        for(NativeRangedPresentation presentation : peer.drainNativeRangedPresentations()) {
            if(presentation.sourceId.equals(localCombatantId)) continue;
            Entity item = weaponResolver == null ? null
                    : weaponResolver.physicalEntity(presentation.itemId);
            if(!(item instanceof Bow)) {
                peer.failNativePresentation("Accepted Bow item " + presentation.itemId
                        + " is unavailable for native ranged presentation.");
                continue;
            }
            ((Bow)item).playNetworkFirePresentation(
                    presentation.x, presentation.y, presentation.z);
        }
    }

    private Entity presentationSource(String sourceId) {
        Monster monster = monsters.get(sourceId);
        if(monster != null) return monster;
        if(sourceId != null && sourceId.equals(localCombatantId())) return attachedPlayer;
        return remoteAvatar(sourceId);
    }

    private void attachNativeProjectiles() {
        if(nativeAuthority == null || attachedLevel == null) return;
        attachNativeProjectiles(attachedLevel.entities);
        attachNativeProjectiles(attachedLevel.non_collidable_entities);
        attachNativeProjectiles(attachedLevel.static_entities);
        for(Entity projectile : new ArrayList<Entity>(projectilePresentations.keySet())) {
            if(projectile.isActive) continue;
            clearNativeProjectile(projectile);
            projectilePresentations.remove(projectile);
        }
    }

    private void synchronizeNativeDynamics() {
        if(nativeAuthority == null || attachedLevel == null) return;
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        synchronizeNativeDynamics(attachedLevel.entities, seen);
        synchronizeNativeDynamics(attachedLevel.non_collidable_entities, seen);
        synchronizeNativeDynamics(attachedLevel.static_entities, seen);
        for(Map.Entry<Entity, Long> entry : new ArrayList<Map.Entry<Entity, Long>>(
                nativeDynamicIds.entrySet())) {
            if(seen.contains(entry.getKey())) continue;
            long itemId = nativeDynamicItemIds.containsKey(entry.getValue())
                    ? nativeDynamicItemIds.get(entry.getValue()) : 0L;
            publishNativeDynamic(entry.getValue(), itemId, entry.getKey(), false);
            nativeDynamicIds.remove(entry.getKey());
            nativeDynamicItemIds.remove(entry.getValue());
            unpresentableDynamics.remove(entry.getKey());
        }
    }

    private void synchronizeNativeDynamics(Array<Entity> entities, Set<Entity> seen) {
        for(Entity entity : entities) {
            if(entity == null || entity.nativePresentationReplica) continue;
            // Fire bombs spawn hidden particles that carry their fires as attachments.
            Array<Entity> attached = entity.getAttached();
            if(attached != null) for(Entity child : attached) {
                if(child instanceof com.interrupt.dungeoneer.entities.Fire && child.isActive
                        && !child.nativePresentationReplica && NativeDynamicState.supports(child)) {
                    seen.add(child);
                    Long childId = ensureNativeDynamicIdentity(child);
                    if(childId == null || unpresentableDynamics.containsKey(child)) continue;
                    publishNativeDynamic(childId, 0L, child, child.isActive);
                }
            }
            if(!NativeDynamicState.supports(entity)) continue;
            seen.add(entity);
            Long id = ensureNativeDynamicIdentity(entity);
            if(id == null || unpresentableDynamics.containsKey(entity)) continue;
            long itemId = nativeDynamicItemIds.containsKey(id) ? nativeDynamicItemIds.get(id) : 0L;
            // A spawned arrow becomes a physical item only once it lands; observers must then
            // present the item instead of a second synthetic arrow.
            if(itemId == 0L && weaponResolver != null) {
                itemId = weaponResolver.physicalIdentity(entity);
                if(itemId != 0L) nativeDynamicItemIds.put(id, itemId);
            }
            publishNativeDynamic(id, itemId, entity, entity.isActive);
        }
    }

    private Long ensureNativeDynamicIdentity(Entity entity) {
        Long id = nativeDynamicIds.get(entity);
        if(id != null) return id;
        if(nativeDynamicIds.size() >= com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES
                || nextNativeDynamicId == Long.MAX_VALUE) {
            // More projectiles in flight than clients draw: this one stays Host-only until room frees.
            return null;
        }
        id = nextNativeDynamicId++;
        nativeDynamicIds.put(entity, id);
        long itemId = weaponResolver == null ? 0L : weaponResolver.physicalIdentity(entity);
        nativeDynamicItemIds.put(id, itemId);
        return id;
    }

    /**
     * Client presentation (projectile, impact, explosion, spell, swing, cue) is cosmetic; Host
     * alone applies its effect. One Host cannot describe is logged once, never ends the session.
     */
    private void skipNativePresentation(IllegalArgumentException invalid) {
        String reason = String.valueOf(invalid.getMessage());
        if(loggedPresentationFailures.size() >= 64 || !loggedPresentationFailures.add(reason)) return;
        if(com.badlogic.gdx.Gdx.app == null) return;
        Throwable cause = invalid.getCause() == null ? invalid : invalid.getCause();
        com.badlogic.gdx.Gdx.app.error("DelverMultiplayer", "Not shown to clients: " + reason + " (" + cause + ")");
    }

    /**
     * Projectiles, bombs and fires are presentation for clients; Host alone applies their damage.
     * One that cannot be described (an out-of-range value) is logged once and left Host-only,
     * instead of stopping the session for everyone.
     */
    private void publishNativeDynamic(long id, long itemId, Entity entity, boolean active) {
        try {
            nativeAuthority.synchronizeNativeDynamicState(
                    NativeDynamicState.capture(id, itemId, entity, active));
        }
        catch(IllegalArgumentException invalid) {
            if(unpresentableDynamics.put(entity, Boolean.TRUE) == null) {
                skipNativePresentation(new IllegalArgumentException("native "
                        + entity.getClass().getSimpleName() + ": " + invalid.getMessage(), invalid));
            }
            // Clients drop whatever they drew of it before.
            nativeAuthority.retireNativeDynamicState(id);
        }
    }

    private void applyNativeDynamicStates(Level level) {
        long generation = peer.getNativeWorldGeneration();
        if(nativeDynamicGeneration != generation) {
            clearNativeDynamicReplicas(level);
            nativeDynamicGeneration = generation;
        }
        for(NativeDynamicState state : peer.getNativeDynamicStates()) {
            Entity replica = nativeDynamicReplicas.get(state.id);
            if(!state.active) {
                if(replica != null) removeNativeDynamicReplica(level, replica);
                nativeDynamicReplicas.remove(state.id);
                continue;
            }
            if(state.itemId > 0L) {
                if(weaponResolver == null) continue;
                Entity physical = weaponResolver.physicalEntity(state.itemId);
                if(physical == null) continue;
                if(replica != null && replica != physical) {
                    // Synthetic in-flight replica hands over to the physical item it became.
                    removeNativeDynamicReplica(level, replica);
                    nativeDynamicReplicas.remove(state.id);
                    replica = null;
                }
                if(replica == null) replica = physical;
                if(!createdNativeDynamicReplicas.contains(physical)) {
                    createdNativeDynamicReplicas.add(physical);
                }
            }
            boolean newlyTracked = !nativeDynamicReplicas.containsKey(state.id);
            replica = state.apply(replica);
            nativeDynamicReplicas.put(state.id, replica);
            if(newlyTracked) {
                createdNativeDynamicReplicas.add(replica);
                if(!level.entities.contains(replica, true)
                        && !level.non_collidable_entities.contains(replica, true)
                        && !level.static_entities.contains(replica, true)) level.addEntity(replica);
            }
        }
    }

    private void clearNativeDynamicReplicas(Level level) {
        for(Entity replica : new ArrayList<Entity>(createdNativeDynamicReplicas))
            removeNativeDynamicReplica(level, replica);
        createdNativeDynamicReplicas.clear();
        nativeDynamicReplicas.clear();
    }

    private void removeNativeDynamicReplica(Level level, Entity replica) {
        if(createdNativeDynamicReplicas.remove(replica)) {
            level.entities.removeValue(replica, true);
            level.non_collidable_entities.removeValue(replica, true);
            level.static_entities.removeValue(replica, true);
        }
        replica.isActive = false;
    }

    private void attachNativeProjectiles(Array<Entity> entities) {
        for(Entity entity : entities) attachNativeProjectile(entity);
    }

    private void attachNativeProjectile(Entity projectile) {
        if(nativeAuthority == null || projectile == null || !projectile.isActive
                || (!(projectile instanceof Projectile)
                        && !(projectile instanceof Missile))) return;
        if(!NativeDynamicState.supports(projectile)) {
            skipNativePresentation(new IllegalArgumentException("Unsupported native projectile class: "
                    + projectile.getClass().getName()));
            return;
        }
        if(projectilePresentations.containsKey(projectile)) return;

        if(projectile instanceof Missile && !((Missile)projectile).isInFlight()) return;
        ParticipantId participantId = participantId(projectile);
        Monster sourceMonster = nativeMonsterInstigator(projectile);
        Entity source = participantId == null ? sourceMonster
                : participantEntity(participantId);
        String sourceId = participantId == null ? monsterIds.get(sourceMonster)
                : AuthoritativeCombatEncounter.participantTargetId(participantId);
        if(source == null || sourceId == null || sourceId.isEmpty()) return;

        CombatAction action = participantId == null
                ? lastMonsterActions.get(sourceMonster)
                : participantActions.get(participantId);
        if(action == null) action = projectileAction(projectile);
        float speed = (float)Math.sqrt(projectile.xa * projectile.xa
                + projectile.ya * projectile.ya + projectile.za * projectile.za);
        ProjectileVisual visual = speed > 0f ? new ProjectileVisual(
                projectile.spriteAtlas != null ? projectile.spriteAtlas
                        : projectile.artType == null ? "sprite" : projectile.artType.toString(),
                projectile.tex, Color.rgba8888(projectile.color),
                Math.max(0.001f, projectile.scale), Math.min(64f, speed),
                projectile.blendMode == Entity.BlendMode.ADD, projectile.fullbrite) : null;
        ProjectilePresentation presentation = new ProjectilePresentation(
                sourceId, action, projectile.x, projectile.y, projectile.z, visual);
        projectilePresentations.put(projectile, presentation);
        if(visual != null) {
            ParticipantId traceActor = participantId == null ? new ParticipantId("projectile-trace") : participantId;
            NativeTrace trace = traceMonsters(projectile, new CombatRequest(traceActor, 1L, action,
                    projectile.xa / speed, projectile.ya / speed, projectile.za / speed),
                    action.getMaximumRange(), 0f);
            nativeAuthority.publishNativePresentation(sourceId, "", action, CombatPresentationPhase.ATTACK,
                    projectile.x, projectile.y, projectile.z,
                    trace.impactX, trace.impactY, trace.impactZ, false, visual);
        }
        if(projectile instanceof Projectile) {
            ((Projectile)projectile).setMultiplayerImpactListener(this);
        }
        else {
            ((Missile)projectile).setMultiplayerImpactListener(this);
        }
    }

    private void clearNativeProjectile(Entity projectile) {
        if(projectile instanceof Projectile) {
            ((Projectile)projectile).clearMultiplayerImpactListener(this);
        }
        else if(projectile instanceof Missile) {
            ((Missile)projectile).clearMultiplayerImpactListener(this);
        }
    }

    private CombatAction projectileAction(Entity projectile) {
        DamageType damageType = null;
        if(projectile instanceof Projectile) {
            damageType = ((Projectile)projectile).damageType;
        }
        else if(projectile instanceof Missile) {
            damageType = ((Missile)projectile).damageType;
        }
        if(damageType == DamageType.HEALING) return CombatAction.BENEFICIAL_SPELL;
        if(projectile instanceof MagicMissileProjectile
                || (damageType != null && damageType != DamageType.PHYSICAL)) {
            return CombatAction.SPELL;
        }
        return CombatAction.PROJECTILE;
    }

    private void applyNativeTrapDamage(Spikes trap, ParticipantId targetId,
            int damage, Entity target) {
        String trapId = trapIds.get(trap);
        if(nativeAuthority == null || trapId == null || targetId == null
                || target == null || damage <= 0) return;
        nativeAuthority.applyNativeEnvironmentalDamage(trapId, targetId, damage,
                trap.x, trap.y, trap.z,
                target.x, target.y, target.z + ATTACK_ORIGIN_HEIGHT);
    }

    private Weapon authoritativeWeapon(ParticipantId participantId, CombatAction action) {
        if(participantId == null || action == null || attachedPlayer == null
                || !participantId.equals(localParticipantId())) return null;
        Item held = attachedPlayer.GetHeldItem();
        return held instanceof Weapon ? (Weapon)held : null;
    }

    /**
     * Host movement walks each Participant at its native Player speed: Speed stat, equipment
     * and the held item's enchantments, before status effects, which reach it with the effects.
     */
    private void synchronizeNativeWalkSpeeds() {
        if(walkSpeedFrames++ % WALK_SPEED_INTERVAL_FRAMES != 0) return;
        ParticipantId local = localParticipantId();
        if(attachedPlayer != null && local != null) pushWalkSpeed(local, attachedPlayer.getUnaffectedWalkSpeed());
        for(RemoteAvatar avatar : attachedRemoteAvatars) {
            ParticipantId participant = avatar.getDescriptor().getParticipantId();
            Player stats = authoritativePlayer(participant);
            Entity held = weaponResolver == null ? null : weaponResolver.wieldedItem(participant);
            if(held instanceof Item) stats.calculatedStats.addItemStats((Item)held);
            pushWalkSpeed(participant, stats.getUnaffectedWalkSpeed());
        }
    }

    private void pushWalkSpeed(ParticipantId participant, float walkSpeed) {
        if(Float.isNaN(walkSpeed) || Float.isInfinite(walkSpeed)) return;
        nativeAuthority.setNativeParticipantWalkSpeed(participant, Math.max(0f, Math.min(1f, walkSpeed)));
    }

    private Player authoritativePlayer(ParticipantId participantId) {
        Player player = authoritativePlayers.get(participantId);
        if(player == null) {
            player = new Player();
            authoritativePlayers.put(participantId, player);
        }
        if(weaponResolver != null) weaponResolver.synchronizeEquipment(participantId, player);
        if(progressResolver != null) progressResolver.synchronizeProgress(participantId, player);
        player.calculatedStats.Recalculate(player);
        return player;
    }

    private Monster intersectedMonster(float x, float y, float z) {
        Monster nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for(Monster monster : monsters.values()) {
            float deltaX = x - monster.x;
            float deltaY = y - monster.y;
            float distance = deltaX * deltaX + deltaY * deltaY;
            float radius = Math.max(0.3f, monster.collision.x + 0.2f);
            float centerZ = monster.z + monster.collision.z * 0.5f;
            if(monster.isActive && monster.hp > 0 && distance <= radius * radius
                    && Math.abs(z - centerZ) <= monster.collision.z + 0.25f
                    && distance < nearestDistance) {
                nearest = monster;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private Corpse intersectedCorpse(float x, float y, float z) {
        Corpse nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for(Monster monster : monsters.values()) {
            Corpse corpse = monster.getMultiplayerCorpse();
            if(corpse == null || !corpse.isActive || corpse.isGibbed()) continue;
            float deltaX = x - corpse.x;
            float deltaY = y - corpse.y;
            float distance = deltaX * deltaX + deltaY * deltaY;
            float radius = Math.max(0.3f, corpse.collision.x + 0.2f);
            float centerZ = corpse.z + corpse.collision.z * 0.5f;
            if(distance <= radius * radius
                    && Math.abs(z - centerZ) <= corpse.collision.z + 0.25f
                    && distance < nearestDistance) {
                nearest = corpse;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void applySnapshot(CombatSnapshot snapshot) {
        if(nativeAuthority == null) {
            if(participantEffectGeneration != peer.getNativeWorldGeneration()) {
                clearParticipantEffects(); participantEffectGeneration = peer.getNativeWorldGeneration();
            }
            for(Monster monster : monsters.values()) monster.beginNetworkEffectGeneration(peer.getNativeWorldGeneration());
        }
        if(snapshot == null) return;
        for(MonsterSnapshot networkState : snapshot.getMonsters()) {
            Monster monster = monsters.get(networkState.getId());
            CombatantSnapshot monsterState = snapshot.getCombatant(networkState.getId());
            if(monster == null || monsterState == null) continue;
            if(nativeAuthority == null) {
                monster.applyNetworkState(monsterState.getHealth(),
                        monsterState.getMaximumHealth(), networkState.getX(),
                        networkState.getY(), networkState.getZ(),
                        networkState.isGibbed());
            }
            else {
                monster.setMultiplayerTarget(monsterTarget(networkState.getTargetId()));
            }
        }

        if(nativeAuthority == null) {
            for(ActorEffectsSnapshot effects : peer.getActorEffects()) {
                Monster monster = monsters.get(effects.monsterId);
                if(monster != null) monster.applyNetworkEffects(effects);
                else {
                    com.interrupt.dungeoneer.entities.Actor actor = effects.monsterId.equals(localCombatantId())
                            ? attachedPlayer : remoteAvatar(effects.monsterId);
                    if(actor != null) {
                        NativeStatusPresentation presentation = participantEffects.get(actor);
                        if(presentation == null) {
                            presentation = new NativeStatusPresentation(); participantEffects.put(actor, presentation);
                        }
                        presentation.apply(actor, effects); presentation.updateAttachments(actor);
                    }
                }
            }
            for(NativeStatusCue cue : peer.drainNativeStatusCues()) {
                Monster monster = monsters.get(cue.monsterId);
                if(monster != null) monster.playNetworkStatusStart(cue);
                else {
                    com.interrupt.dungeoneer.entities.Actor actor = cue.monsterId.equals(localCombatantId())
                            ? attachedPlayer : remoteAvatar(cue.monsterId);
                    NativeStatusPresentation presentation = participantEffects.get(actor);
                    if(presentation != null) presentation.playStart(actor, cue);
                }
            }
        }

        CombatantSnapshot local = localCombatant(snapshot);
        if(local != null && attachedPlayer != null) {
            applyLocalHealth(attachedPlayer, local.getHealth());
        }
        synchronizeRemoteAvatars(snapshot);
    }

    private void synchronizeRemoteAvatars(CombatSnapshot snapshot) {
        List<RemoteAvatar> visible = new ArrayList<RemoteAvatar>();
        for(CombatantSnapshot combatant : snapshot.getCombatants()) {
            RemoteAvatar avatar = remoteAvatar(combatant.getId());
            if(avatar == null) continue;
            visible.add(avatar);
            if(!attachedRemoteAvatars.contains(avatar)) {
                attachedRemoteAvatars.add(avatar);
                avatar.setDamageAuthorityListener(this);
                avatar.setStatusEffectAuthority(nativeAuthority != null);
            }
            avatar.setStatusEffectAuthority(nativeAuthority != null
                    && nativeAuthority.canApplyNativeParticipantEffect(avatar.getDescriptor().getParticipantId()));
            avatar.applyAuthoritativeHealth(combatant.getHealth(),
                    combatant.getMaximumHealth());
        }
        for(RemoteAvatar avatar : new ArrayList<RemoteAvatar>(attachedRemoteAvatars)) {
            if(visible.contains(avatar)) continue;
            avatar.clearDamageAuthorityListener(this);
            NativeStatusPresentation presentation = participantEffects.remove(avatar);
            if(presentation != null) presentation.clear(avatar);
            attachedRemoteAvatars.remove(avatar);
        }
    }

    private void attachNativeMonstersIfPresent() {
        if(!monsters.isEmpty() || attachedLevel == null || monstersAttachedLevel == attachedLevel) return;
        monstersAttachedLevel = attachedLevel;
        List<MonsterCandidate> candidates = new ArrayList<MonsterCandidate>();
        IdentityHashMap<Monster, Boolean> seen = new IdentityHashMap<Monster, Boolean>();
        int order = collectMonsters(attachedLevel.entities, candidates, seen, 0);
        order = collectMonsters(attachedLevel.non_collidable_entities, candidates, seen, order);
        collectMonsters(attachedLevel.static_entities, candidates, seen, order);
        Collections.sort(candidates, new Comparator<MonsterCandidate>() {
            @Override
            public int compare(MonsterCandidate left, MonsterCandidate right) {
                int compared = left.stableKey.compareTo(right.stableKey);
                return compared != 0 ? compared : Integer.compare(left.levelOrder, right.levelOrder);
            }
        });
        if(attachedLevel.restoredCampaignFloor) {
            // Host floor checkpoint: bound Monsters kept the ids clients derive from a fresh build.
            // One without a saved id is keyed as a late arrival and announced.
            for(MonsterCandidate candidate : candidates) {
                String saved = candidate.monster.multiplayerIdentity;
                if(monsterIndex(saved) < 1 || monsters.containsKey(saved)
                        || nativeMonsterSlotsFull()) continue;
                trackMonster(saved, candidate.monster);
                monsterIndexCounter = Math.max(monsterIndexCounter, monsterIndex(saved));
            }
            return;
        }
        int count = Math.min(candidates.size(), CombatSnapshot.MAX_NATIVE_MONSTERS);
        for(int index = 0; index < count; index++) {
            trackMonster(AuthoritativeCombatEncounter.monsterTargetId(index + 1),
                    candidates.get(index).monster);
        }
        monsterIndexCounter = count;
    }

    private void trackMonster(String monsterId, Monster monster) {
        monsters.put(monsterId, monster);
        monsterIds.put(monster, monsterId);
        monster.multiplayerIdentity = monsterId;
        lastMonsterActions.put(monster, CombatAction.MELEE);
        if(nativeAuthority == null) {
            monster.setNetworkReplica(true);
        }
        else {
            monster.setNetworkReplica(false);
            monster.setMultiplayerAttackListener(this);
            monster.setMultiplayerDamageListener(this);
            bindNativeMonster(monsterId, monster);
        }
    }

    /**
     * Host: hostile Monsters that entered the floor after the initial attach (ambient spawns,
     * spawners, eggs, dev menu) get the next Host id, bind to the encounter and are announced so
     * clients materialize the same replica.
     */
    private void attachLateNativeMonsters() {
        if(attachedLevel == null || monstersAttachedLevel != attachedLevel) return;
        List<MonsterCandidate> candidates = new ArrayList<MonsterCandidate>();
        IdentityHashMap<Monster, Boolean> seen = new IdentityHashMap<Monster, Boolean>();
        int order = collectMonsters(attachedLevel.entities, candidates, seen, 0);
        order = collectMonsters(attachedLevel.non_collidable_entities, candidates, seen, order);
        collectMonsters(attachedLevel.static_entities, candidates, seen, order);
        for(MonsterCandidate candidate : candidates) {
            Monster monster = candidate.monster;
            if(monsterIds.containsKey(monster) || !monster.isActive || monster.hp <= 0
                    || retiredMonsters.containsKey(monster)) continue;
            if(nativeMonsterSlotsFull() && !retireLongestDeadMonster()) return;
            String monsterId = AuthoritativeCombatEncounter.monsterTargetId(++monsterIndexCounter);
            trackMonster(monsterId, monster);
            String theme = devSpawnThemes.remove(monster);
            if(theme == null) theme = attachedLevel.theme == null ? "" : attachedLevel.theme;
            String name = monster.name == null || monster.name.isEmpty() ? "?" : monster.name;
            Entity state = nativeMonsterStateEntity(monster);
            try {
                nativeAuthority.publishNativeMonsterSpawn(new NativeMonsterSpawn(monsterId, theme, name,
                        state.x, state.y, state.z, nativeHealth(monster), nativeMaximumHealth(monster)));
            }
            catch(IllegalArgumentException invalid) {
                // Clients ignore combat state for a Monster they never materialized.
                skipNativePresentation(new IllegalArgumentException("Native monster spawn: "
                        + invalid.getMessage(), invalid));
            }
        }
    }

    /** Client: create a replica for each Host-announced late Monster from local content. */
    private void materializeNativeMonsterSpawns(Game game) {
        if(attachedLevel == null || game.monsterManager == null) return;
        for(NativeMonsterSpawn spawn : peer.drainNativeMonsterSpawns()) {
            if(monsters.containsKey(spawn.monsterId)) continue;
            Monster monster = lookupMonsterTemplate(game, spawn.theme, spawn.name);
            if(monster == null) {
                peer.failNativePresentation("Host monster is unavailable in local content: "
                        + spawn.theme + "/" + spawn.name);
                return;
            }
            monster.spawnChance = 1f;
            monster.x = spawn.x;
            monster.y = spawn.y;
            monster.z = spawn.z;
            monster.maxHp = spawn.maximumHealth;
            monster.hp = spawn.health;
            monster.isActive = true;
            attachedLevel.SpawnEntity(monster);
            trackMonster(spawn.monsterId, monster);
        }
    }

    private static Monster lookupMonsterTemplate(Game game, String theme, String name) {
        Monster monster = theme.isEmpty() ? null : game.monsterManager.GetMonster(theme, name);
        if(monster != null || game.monsterManager.monsters == null) return monster;
        for(String candidateTheme : game.monsterManager.monsters.keySet()) {
            monster = game.monsterManager.GetMonster(candidateTheme, name);
            if(monster != null) return monster;
        }
        return null;
    }

    /**
     * Host cold resume: a rebuilt floor holds only its initial Monsters. Late ones (spawner,
     * trigger, egg, dev menu) return from the save under their saved ids; binding applies saved
     * health, position and corpse outcome. Clients get the same ids from the replayed spawns.
     */
    private void restoreLateNativeMonsters(Game game) {
        if(attachedLevel == null || monstersAttachedLevel != attachedLevel
                || lateMonstersRestoredLevel == attachedLevel) return;
        lateMonstersRestoredLevel = attachedLevel;
        // New late arrivals must never reuse an id the save already owns.
        CombatSnapshot saved = peer.getCombatSnapshot();
        if(saved != null) {
            for(MonsterSnapshot monster : saved.getMonsters()) {
                monsterIndexCounter = Math.max(monsterIndexCounter, monsterIndex(monster.getId()));
            }
        }
        List<NativeMonsterSpawn> restored = nativeAuthority.getRestoredNativeMonsterSpawns();
        for(NativeMonsterSpawn spawn : restored) {
            monsterIndexCounter = Math.max(monsterIndexCounter, monsterIndex(spawn.monsterId));
        }
        // A floor checkpoint already holds its living late Monsters natively.
        if(!attachedLevel.restoredCampaignFloor) restoreLateNativeMonsters(game, restored);
        adoptOrphanedMonsters(saved);
    }

    private void restoreLateNativeMonsters(Game game, List<NativeMonsterSpawn> restored) {
        for(NativeMonsterSpawn spawn : restored) {
            if(monsters.containsKey(spawn.monsterId)) continue;
            if(nativeMonsterSlotsFull()) return;
            Monster monster = game.monsterManager == null ? null
                    : lookupMonsterTemplate(game, spawn.theme, spawn.name);
            if(monster == null) {
                nativeAuthority.failNativePresentation("Saved monster is unavailable in local content: "
                        + spawn.theme + "/" + spawn.name);
                return;
            }
            monster.spawnChance = 1f;
            monster.x = spawn.x;
            monster.y = spawn.y;
            monster.z = spawn.z;
            monster.Init(attachedLevel, game.player.level);
            // Host admitted this Monster before the save; the rebuilt floor must not veto it.
            monster.isActive = true;
            monster.maxHp = spawn.maximumHealth;
            attachedLevel.SpawnEntity(monster);
            trackMonster(spawn.monsterId, monster);
        }
    }

    /**
     * Saved Monsters this floor has no native body for. A dead one keeps its slot, so clients
     * still draw its corpse, until a new Monster needs it; a living one cannot be simulated.
     */
    private void adoptOrphanedMonsters(CombatSnapshot saved) {
        if(saved == null) return;
        List<String> dead = new ArrayList<String>();
        for(CombatantSnapshot combatant : saved.getCombatants()) {
            String id = combatant.getId();
            if(combatant.getKind() != CombatantKind.MONSTER || monsterIndex(id) < 1
                    || monsters.containsKey(id)) continue;
            if(combatant.getHealth() > 0) nativeAuthority.retireNativeMonster(id);
            else dead.add(id);
        }
        Collections.sort(dead, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Integer.compare(monsterIndex(left), monsterIndex(right));
            }
        });
        orphanedMonsterIds.addAll(dead);
        orphansAdoptedFrame = monsterFrame;
    }

    private boolean nativeMonsterSlotsFull() {
        return monsters.size() + orphanedMonsterIds.size() >= CombatSnapshot.MAX_NATIVE_MONSTERS;
    }

    private static int monsterIndex(String monsterId) {
        String prefix = AuthoritativeCombatEncounter.MONSTER_ID_PREFIX;
        if(monsterId == null || !monsterId.startsWith(prefix)) return 0;
        try { return Integer.parseInt(monsterId.substring(prefix.length())); }
        catch(NumberFormatException ignored) { return 0; }
    }

    /** Client: Host announces every Monster; one this peer's own floor logic added is not in play. */
    private void discardUnannouncedNativeMonsters() {
        if(attachedLevel == null || monstersAttachedLevel != attachedLevel) return;
        discardUnannouncedNativeMonsters(attachedLevel.entities);
        discardUnannouncedNativeMonsters(attachedLevel.non_collidable_entities);
        discardUnannouncedNativeMonsters(attachedLevel.static_entities);
    }

    private void discardUnannouncedNativeMonsters(Array<Entity> entities) {
        for(Entity entity : entities) {
            if(!(entity instanceof Monster)) continue;
            Monster monster = (Monster)entity;
            if(monster.hostile && monster.isActive && !monsterIds.containsKey(monster)) {
                monster.isActive = false;
            }
        }
    }

    private static final String DECAL_IDENTITY = "decal:";
    /** A decal every peer's own build of the floor already has. */
    private static final String FLOOR_DECAL = "decal:floor";

    /**
     * Host: marks made in play (blood, sword, scorch) live in level entities. Ones restored from
     * a floor checkpoint go to clients connected now, which never saw them made; any other
     * decal already there came with the floor build every peer makes.
     */
    private void keyNativeDecals(Level level) {
        nativeDecalCounter = 0;
        for(int index = 0; index < level.entities.size; index++) {
            Entity entity = level.entities.get(index);
            if(!(entity instanceof ProjectedDecal)) continue;
            int saved = decalIndex(entity.multiplayerIdentity);
            if(saved > 0) {
                nativeDecalCounter = Math.max(nativeDecalCounter, saved);
                recordNativeDecal((ProjectedDecal)entity, true);
            }
            else entity.multiplayerIdentity = FLOOR_DECAL;
        }
    }

    /** Host: live clients drew this mark from its presentation; only later joiners need it. */
    private void synchronizeNativeDecals() {
        if(attachedLevel == null) return;
        for(int index = 0; index < attachedLevel.entities.size; index++) {
            Entity entity = attachedLevel.entities.get(index);
            if(!(entity instanceof ProjectedDecal) || entity.multiplayerIdentity != null) continue;
            entity.multiplayerIdentity = DECAL_IDENTITY + (++nativeDecalCounter);
            recordNativeDecal((ProjectedDecal)entity, false);
        }
    }

    private void recordNativeDecal(ProjectedDecal decal, boolean announce) {
        try {
            nativeAuthority.recordNativeDecal(NativeDecalState.capture(decal), announce);
        }
        catch(IllegalArgumentException outsideBounds) {
            // Presentation only: a mark the wire cannot carry stays on Host alone.
        }
    }

    private static int decalIndex(String identity) {
        if(identity == null || !identity.startsWith(DECAL_IDENTITY)) return 0;
        try { return Integer.parseInt(identity.substring(DECAL_IDENTITY.length())); }
        catch(NumberFormatException floor) { return 0; }
    }

    /** Client: Host's marks from before this peer joined, rebuilt on its own floor. */
    private void materializeNativeDecals() {
        if(attachedLevel == null) return;
        for(NativeDecalState decal : peer.drainNativeDecals()) {
            attachedLevel.entities.add(decal.materialize());
        }
    }

    /** Same key on every build of this floor; repeated placements get their level-order ordinal. */
    private void keyNativeMonsterSpawners(Level level) {
        monsterSpawnerKeys.clear();
        Map<String, Integer> repeats = new LinkedHashMap<String, Integer>();
        keyNativeMonsterSpawners(level.entities, repeats);
        keyNativeMonsterSpawners(level.non_collidable_entities, repeats);
        keyNativeMonsterSpawners(level.static_entities, repeats);
    }

    private void keyNativeMonsterSpawners(Array<Entity> entities, Map<String, Integer> repeats) {
        for(Entity entity : entities) {
            if(!(entity instanceof MonsterSpawner) || monsterSpawnerKeys.containsKey(entity)) continue;
            String key = com.interrupt.dungeoneer.multiplayer.floor.SharedFloorIdentity.placementKey(entity);
            Integer seen = repeats.get(key);
            repeats.put(key, seen == null ? 1 : seen + 1);
            monsterSpawnerKeys.put((MonsterSpawner)entity, seen == null ? key : key + "#" + (seen + 1));
        }
    }

    private boolean allowNativeMonsterSpawn(MonsterSpawner spawner) {
        // Host announces every Monster a spawner adds; a client never adds its own.
        if(nativeAuthority == null) return false;
        String key = monsterSpawnerKeys.get(spawner);
        if(key == null) return true;
        if(nativeAuthority.isNativeMonsterSpawnerConsumed(key)) return false;
        if(spawner.destroyAfterSpawn) nativeAuthority.consumeNativeMonsterSpawner(key);
        return true;
    }

    /**
     * Dev menu on Host: place one catalogue Monster on the floor. It binds and is announced on
     * the next prepare like any other late arrival. False when the spot is blocked.
     */
    public boolean spawnNativeMonster(Game game, String theme, String name, float x, float y, float z) {
        if(nativeAuthority == null || game == null || game.level == null || game.player == null
                || attachedLevel != game.level || game.monsterManager == null) return false;
        Monster monster = game.monsterManager.GetMonster(theme, name);
        if(monster == null) return false;
        monster.spawnChance = 1f;
        monster.x = x;
        monster.y = y;
        monster.z = z;
        monster.Init(game.level, game.player.level);
        if(!monster.isActive) return false;
        game.level.entities.add(monster);
        game.level.addEntityToSpatialHash(monster);
        monster.updateDrawable();
        devSpawnThemes.put(monster, theme == null ? "" : theme);
        attachLateNativeMonsters();
        return true;
    }

    private void attachNativeTrapsIfPresent() {
        if(!traps.isEmpty() || attachedLevel == null) return;
        List<TrapCandidate> candidates = new ArrayList<TrapCandidate>();
        IdentityHashMap<Spikes, Boolean> seen = new IdentityHashMap<Spikes, Boolean>();
        int order = collectTraps(attachedLevel.entities, candidates, seen, 0);
        order = collectTraps(attachedLevel.non_collidable_entities, candidates, seen, order);
        collectTraps(attachedLevel.static_entities, candidates, seen, order);
        Collections.sort(candidates, new Comparator<TrapCandidate>() {
            @Override
            public int compare(TrapCandidate left, TrapCandidate right) {
                int compared = left.stableKey.compareTo(right.stableKey);
                return compared != 0 ? compared
                        : Integer.compare(left.levelOrder, right.levelOrder);
            }
        });
        int count = Math.min(candidates.size(), CombatSnapshot.MAX_MONSTERS);
        for(int index = 0; index < count; index++) {
            Spikes trap = candidates.get(index).trap;
            String saved = trap.multiplayerIdentity;
            // A restored Host floor keeps each hazard's id even if one before it is gone.
            String trapId = attachedLevel.restoredCampaignFloor && saved != null
                    && saved.startsWith("hazard:") && !traps.containsKey(saved)
                    ? saved : "hazard:" + (index + 1);
            traps.put(trapId, trap);
            trapIds.put(trap, trapId);
            trap.multiplayerIdentity = trapId;
            if(nativeAuthority == null) {
                trap.setNetworkReplica(true);
            }
            else {
                trap.setNetworkReplica(false);
                trap.setMultiplayerTrapListener(this);
            }
        }
    }

    private int collectMonsters(Array<Entity> entities, List<MonsterCandidate> candidates,
            IdentityHashMap<Monster, Boolean> seen, int order) {
        for(Entity entity : entities) {
            if(!(entity instanceof Monster)) continue;
            Monster monster = (Monster)entity;
            if(!monster.hostile || seen.put(monster, Boolean.TRUE) != null) continue;
            candidates.add(new MonsterCandidate(monster, order++));
        }
        return order;
    }

    private int collectTraps(Array<Entity> entities, List<TrapCandidate> candidates,
            IdentityHashMap<Spikes, Boolean> seen, int order) {
        for(Entity entity : entities) {
            if(!(entity instanceof Spikes)) continue;
            Spikes trap = (Spikes)entity;
            if(seen.put(trap, Boolean.TRUE) != null) continue;
            candidates.add(new TrapCandidate(trap, order++));
        }
        return order;
    }

    private void bindNativeMonster(String monsterId, Monster monster) {
        if(boundMonsterIds.contains(monsterId) || nativeAuthority == null || monster == null) return;
        restoreAuthoritativeMonster(monsterId, monster);
        Entity stateEntity = nativeMonsterStateEntity(monster);
        nativeAuthority.bindNativeMonster(monsterId, nativeHealth(monster),
                nativeMaximumHealth(monster), stateEntity.x, stateEntity.y, stateEntity.z,
                nativeMonsterGibbed(monster));
        boundMonsterIds.add(monsterId);
    }

    private void restoreAuthoritativeMonster(String monsterId, Monster monster) {
        // A floor checkpoint restored the Monster natively; the saved combat record is older.
        if(attachedLevel != null && attachedLevel.restoredCampaignFloor) return;
        String key = "state:" + monsterId;
        if(restoredAuthoritativeActors.contains(key)) return;
        CombatSnapshot snapshot = peer.getCombatSnapshot();
        if(snapshot == null) return;
        MonsterSnapshot movement = snapshot.getMonster(monsterId);
        CombatantSnapshot combatant = snapshot.getCombatant(monsterId);
        if(movement == null || combatant == null) return;
        monster.restoreMultiplayerAuthorityState(combatant.getHealth(),
                combatant.getMaximumHealth(), movement.getX(), movement.getY(),
                movement.getZ(), movement.isGibbed(), attachedLevel);
        restoredAuthoritativeActors.add(key);
    }

    private void restoreAuthoritativeEffects() {
        if(nativeAuthority == null) return;
        for(ActorEffectsSnapshot effects : peer.getActorEffects()) {
            String key = "effects:" + effects.monsterId;
            com.interrupt.dungeoneer.entities.Actor actor = monsters.get(effects.monsterId);
            if(actor == null) {
                actor = effects.monsterId.equals(localCombatantId())
                        ? attachedPlayer : remoteAvatar(effects.monsterId);
            }
            if(actor == null) continue;
            if(actor instanceof Monster && restoredAuthoritativeActors.contains(key)
                    || !(actor instanceof Monster) && restoredParticipantEffects.get(key) == actor) continue;
            // Native floor checkpoint keeps private status cadence and callback state.
            // Detached effects describe observers; they must not restart native monsters.
            if(actor instanceof Monster && attachedLevel.restoredCampaignFloor) {
                restoredAuthoritativeActors.add(key);
                continue;
            }
            effects.restoreAuthoritative(actor);
            if(actor instanceof Monster) restoredAuthoritativeActors.add(key);
            else restoredParticipantEffects.put(key, actor);
        }
    }

    private void synchronizeNativeMonsters() {
        monsterFrame++;
        for(Map.Entry<String, Monster> entry : monsters.entrySet()) {
            Monster monster = entry.getValue();
            if(monster != null && monster.hp <= 0 && !monsterDeathFrames.containsKey(monster)) {
                monsterDeathFrames.put(monster, monsterFrame);
            }
            synchronizeNativeMonster(entry.getKey(), monster);
        }
    }

    /**
     * Frees the slot of the Monster dead longest, once every client has had two seconds of
     * combat state showing it dead; clients keep its corpse. Without this, the 65th Monster to
     * enter a floor was never announced and stayed invisible to clients.
     */
    private boolean retireLongestDeadMonster() {
        // Resumed clients get two seconds of combat state to draw the orphans' corpses too.
        if(!orphanedMonsterIds.isEmpty() && monsterFrame - orphansAdoptedFrame >= RETIRE_AFTER_FRAMES) {
            nativeAuthority.retireNativeMonster(orphanedMonsterIds.remove(0));
            return true;
        }
        String retiredId = null;
        Monster retired = null;
        int earliest = Integer.MAX_VALUE;
        for(Map.Entry<String, Monster> entry : monsters.entrySet()) {
            Integer died = monsterDeathFrames.get(entry.getValue());
            if(died == null || monsterFrame - died < RETIRE_AFTER_FRAMES || died >= earliest) continue;
            earliest = died;
            retiredId = entry.getKey();
            retired = entry.getValue();
        }
        if(retiredId == null) return false;
        monsters.remove(retiredId);
        monsterIds.remove(retired);
        boundMonsterIds.remove(retiredId);
        retired.clearMultiplayerAttackListener(this);
        retired.clearMultiplayerDamageListener(this);
        retired.clearMultiplayerTarget();
        lastMonsterActions.remove(retired);
        lastParticipantAttackers.remove(retired);
        devSpawnThemes.remove(retired);
        monsterDeathFrames.remove(retired);
        retiredMonsters.put(retired, Boolean.TRUE);
        nativeAuthority.retireNativeMonster(retiredId);
        return true;
    }

    private void synchronizeNativeMonster(String monsterId, Monster monster) {
        if(nativeAuthority == null || monster == null) return;
        nativeAuthority.synchronizeNativeActorEffects(ActorEffectsSnapshot.capture(monsterId, 1, monster));
        if(monster.hp <= 0 && !monster.isMultiplayerDeathProcessed()) return;
        bindNativeMonster(monsterId, monster);
        Entity stateEntity = nativeMonsterStateEntity(monster);
        nativeAuthority.synchronizeNativeMonster(monsterId, nativeHealth(monster),
                nativeMaximumHealth(monster), stateEntity.x, stateEntity.y, stateEntity.z,
                nativeMonsterGibbed(monster));
    }

    private int nativeMaximumHealth(Monster monster) {
        return Math.max(1, monster.getMaxHp());
    }

    private int nativeHealth(Monster monster) {
        return Math.max(0, Math.min(monster.hp, nativeMaximumHealth(monster)));
    }

    private boolean nativeMonsterGibbed(Monster monster) {
        return monster != null && monster.hp <= 0
                && monster.isMultiplayerDeathGibbed();
    }

    private Entity nativeMonsterStateEntity(Monster monster) {
        Corpse corpse = monster == null ? null : monster.getMultiplayerCorpse();
        return corpse == null ? monster : corpse;
    }

    private void animateCombatants(CombatPresentationEvent event,
            boolean damageAcknowledgement) {
        if(event.getPhase() == CombatPresentationPhase.ATTACK) {
            Monster sourceMonster = monsters.get(event.getSourceId());
            if(sourceMonster != null) {
                if(nativeAuthority == null) {
                    sourceMonster.playNetworkAttackPresentation(attackKind(event.getAction()));
                }
            }
            else {
                RemoteAvatar source = remoteAvatar(event.getSourceId());
                if(source != null) source.playCombatAction(event.getAction());
            }
        }
        if(damageAcknowledgement) {
            Monster targetMonster = monsters.get(event.getTargetId());
            if(targetMonster != null) {
                if(nativeAuthority == null) targetMonster.playNetworkDamagePresentation();
            }
            else {
                RemoteAvatar target = remoteAvatar(event.getTargetId());
                if(target != null) target.playDamageReaction();
            }
        }
    }

    private void applyLocalHealth(Player player, int health) {
        if(lastLocalHealth >= 0 && health < lastLocalHealth) player.shake(2f);
        player.hp = Math.max(0, Math.min(health, player.getMaxHp()));
        lastLocalHealth = player.hp;
    }

    private CombatantSnapshot localCombatant(CombatSnapshot snapshot) {
        String combatantId = localCombatantId();
        return combatantId == null ? null : snapshot.getCombatant(combatantId);
    }

    private ParticipantId localParticipantId() {
        if(peer.getPartyStatus() == null || peer.getLocalMovementEntityId() == null) return null;
        for(PartyMemberStatus member : peer.getPartyStatus().getMembers()) {
            if(peer.getLocalMovementEntityId().equals(member.getEntityId())) {
                return new ParticipantId("campaign-slot-" + member.getCampaignSlot());
            }
        }
        return null;
    }

    private String localCombatantId() {
        ParticipantId participantId = localParticipantId();
        return participantId == null ? null
                : AuthoritativeCombatEncounter.participantTargetId(participantId);
    }

    private ParticipantId participantId(Entity entity) {
        Entity current = entity;
        for(int depth = 0; current != null && depth < 4; depth++) {
            if(current.multiplayerDamageSource != null) return new ParticipantId(current.multiplayerDamageSource);
            if(current == attachedPlayer) return localParticipantId();
            if(current instanceof RemoteAvatar) {
                return ((RemoteAvatar)current).getDescriptor().getParticipantId();
            }
            current = current.owner;
        }
        return null;
    }

    /**
     * A Monster's or the world's shot hits a remote Participant as native shots hit the Player;
     * a Participant's own shot never hits a teammate, and a Spectator leaves no body behind.
     */
    private boolean projectileMayHit(Entity projectile, RemoteAvatar avatar) {
        return avatar.isActive && !(avatar.isIncapacitated() && avatar.hidden)
                && projectile.owner != avatar && participantId(projectile) == null;
    }

    private Entity participantEntity(ParticipantId participantId) {
        if(participantId == null) return null;
        if(participantId.equals(localParticipantId())) return attachedPlayer;
        return movementController == null ? null
                : movementController.getRemoteAvatar(participantId);
    }

    private Entity monsterTarget(String combatantId) {
        if(combatantId == null || combatantId.isEmpty()) return null;
        if(combatantId.equals(localCombatantId())) return attachedPlayer;
        return remoteAvatar(combatantId);
    }

    private String combatantId(Entity entity) {
        ParticipantId participantId = participantId(entity);
        return participantId == null ? ""
                : AuthoritativeCombatEncounter.participantTargetId(participantId);
    }

    private String impactTargetId(Entity entity) {
        if(entity instanceof Monster) {
            String monsterId = monsterIds.get((Monster)entity);
            return monsterId == null ? "" : monsterId;
        }
        if(entity instanceof Corpse) {
            String monsterId = monsterIdForCorpse((Corpse)entity);
            return monsterId == null ? "" : monsterId;
        }
        if(entity == attachedPlayer) return localCombatantId();
        if(entity instanceof RemoteAvatar) return combatantId(entity);
        return "";
    }

    private String monsterIdForCorpse(Corpse corpse) {
        if(corpse == null) return null;
        for(Map.Entry<String, Monster> entry : monsters.entrySet()) {
            if(entry.getValue().getMultiplayerCorpse() == corpse) return entry.getKey();
        }
        return null;
    }

    private boolean replayTrapActivation(CombatPresentationEvent event) {
        if(event.getPhase() != CombatPresentationPhase.ATTACK
                || event.getAction() != CombatAction.ENVIRONMENTAL_HAZARD) return false;
        Spikes trap = traps.get(event.getSourceId());
        if(trap == null) return false;
        if(nativeAuthority == null) trap.playNetworkActivation();
        return true;
    }

    private RemoteAvatar remoteAvatar(String combatantId) {
        if(movementController == null || combatantId == null
                || !combatantId.startsWith("participant:")) return null;
        String participantValue = combatantId.substring("participant:".length());
        if(participantValue.isEmpty()) return null;
        return movementController.getRemoteAvatar(new ParticipantId(participantValue));
    }

    /**
     * Cause of a native damage intent, from what original Delver passes: lava and poison tiles
     * hit with a null instigator and the tile's damage type; explosions, fires and traps
     * instigate themselves; burning and poison status effects tick with a null instigator.
     */
    static DeathCause classifyCause(com.interrupt.dungeoneer.entities.Actor target,
            DamageType damageType, Entity instigator, boolean trap) {
        if(damageType == DamageType.ICE) return DeathCause.FROST;
        Entity current = instigator;
        for(int depth = 0; current != null && depth < 4; depth++) {
            if(current instanceof com.interrupt.dungeoneer.entities.Explosion) return DeathCause.EXPLOSION;
            if(current instanceof com.interrupt.dungeoneer.entities.Fire) return DeathCause.BURNING;
            if(current instanceof Spikes
                    || current instanceof com.interrupt.dungeoneer.entities.triggers.DamageTrigger) {
                return DeathCause.TRAP;
            }
            current = current.owner;
        }
        if(trap) return DeathCause.TRAP;
        if(instigator != null) return DeathCause.COMBAT;
        if(damageType == DamageType.FIRE) return DeathCause.LAVA;
        if(damageType == DamageType.PHYSICAL && target != null && target.statusEffects != null) {
            for(com.interrupt.dungeoneer.statuseffects.StatusEffect effect : target.statusEffects) {
                if(effect != null && effect.active
                        && effect instanceof com.interrupt.dungeoneer.statuseffects.BurningEffect) {
                    return DeathCause.BURNING;
                }
            }
        }
        return DeathCause.COMBAT;
    }

    private Monster nativeMonsterInstigator(Entity instigator) {
        Entity current = instigator;
        for(int depth = 0; current != null && depth < 4; depth++) {
            if(current instanceof Monster && monsterIds.containsKey((Monster)current)) {
                return (Monster)current;
            }
            current = current.owner;
        }
        return null;
    }

    private Spikes nativeTrapInstigator(Entity instigator) {
        Entity current = instigator;
        for(int depth = 0; current != null && depth < 4; depth++) {
            if(current instanceof Spikes && trapIds.containsKey((Spikes)current)) {
                return (Spikes)current;
            }
            current = current.owner;
        }
        return null;
    }

    private boolean overlaps(Entity left, Entity right) {
        return Math.abs(left.x - right.x) < left.collision.x + right.collision.x
                && Math.abs(left.y - right.y) < left.collision.y + right.collision.y
                && Math.abs(left.z - right.z) < left.collision.z + right.collision.z;
    }

    private boolean isHostNativeMonsterPresentation(CombatPresentationEvent event) {
        return nativeAuthority != null && monsters.containsKey(event.getSourceId());
    }

    private boolean isLocalNativePresentation(CombatPresentationEvent event,
            String localCombatantId) {
        if(localCombatantId == null || !localCombatantId.equals(event.getSourceId())) return false;
        CombatAction action = event.getAction();
        return action == CombatAction.MELEE || action == CombatAction.PROJECTILE
                || action == CombatAction.SPELL || action == CombatAction.BENEFICIAL_SPELL;
    }

    private boolean suppressHostNativeProjectileEffect(CombatPresentationEvent event) {
        if(event.getPhase() == CombatPresentationPhase.DAMAGE) return false;
        CombatAction action = event.getAction();
        return action == CombatAction.PROJECTILE || action == CombatAction.SPELL
                || action == CombatAction.BENEFICIAL_SPELL;
    }

    private void attachToLevel(Level level) {
        detachFromLevel();
        restoredAuthoritativeActors.clear();
        attachedLevel = level;
        if(nativeAuthority != null) nativeAuthority.beginNativeWorld();
        // Host announces every late Monster; a client must not roll ambient spawns of its own.
        else level.spawnMonsters = false;
        keyNativeMonsterSpawners(level);
        attachedLevel.nativeMonsterSpawnerListener = this::allowNativeMonsterSpawn;
        if(nativeAuthority != null) keyNativeDecals(level);
        if(nativeAuthority != null) attachedLevel.nativeProjectileTargets = new NativeProjectileTargets() {
            @Override public Entity collidingTarget(Entity projectile, float x, float y, float z,
                    float widthX, float widthY, float height) {
                for(RemoteAvatar avatar : attachedRemoteAvatars) {
                    if(!projectileMayHit(projectile, avatar)) continue;
                    if(x > avatar.x - avatar.collision.x - widthX && x < avatar.x + avatar.collision.x + widthX
                            && y > avatar.y - avatar.collision.y - widthY
                            && y < avatar.y + avatar.collision.y + widthY
                            && z > avatar.z - height && z < avatar.z + avatar.collision.z) return avatar;
                }
                return null;
            }
            @Override public void addLineTargets(Entity projectile, Array<Entity> candidates) {
                for(RemoteAvatar avatar : attachedRemoteAvatars) {
                    if(projectileMayHit(projectile, avatar) && !candidates.contains(avatar, true)) candidates.add(avatar);
                }
            }
        };
        attachedLevel.nativeExplosionListener = new NativeExplosionListener() {
            @Override public boolean isSimulationAuthority() { return nativeAuthority != null; }
            @Override public void addTargets(com.interrupt.dungeoneer.entities.Explosion explosion, Array<Entity> targets) {
                if(nativeAuthority == null) return;
                for(RemoteAvatar avatar : attachedRemoteAvatars) {
                    if(avatar.isActive && !targets.contains(avatar, true)) targets.add(avatar);
                }
            }
            @Override public boolean canAffect(com.interrupt.dungeoneer.entities.Explosion explosion, Entity target) {
                ParticipantId source = participantId(explosion);
                ParticipantId recipient = participantId(target);
                return source == null || recipient == null || source.equals(recipient)
                        || explosion.damageType == DamageType.HEALING;
            }
            @Override public boolean onExplosion(com.interrupt.dungeoneer.entities.Explosion explosion, float amount) {
                if(nativeAuthority == null) return false;
                try {
                    nativeAuthority.publishNativeExplosion(NativeExplosionPresentation.capture(explosion, amount));
                    return true;
                }
                catch(IllegalArgumentException incompatible) {
                    skipNativePresentation(incompatible);
                    return false;
                }
            }
        };
        attachedLevel.nativeAnimationListener = (owner, action) -> {
            if(nativeAuthority == null) return;
            try { nativeAuthority.publishNativeAnimationCue(NativeAnimationCue.capture(owner, action)); }
            catch(IllegalArgumentException invalid) { skipNativePresentation(invalid); }
        };
        attachedLevel.nativeDynamicListener = bomb -> {
            if(nativeAuthority == null) return;
            Long id = ensureNativeDynamicIdentity(bomb);
            if(id == null) return;
            try {
                nativeAuthority.publishNativeDynamicCue(NativeDynamicCue.captureFizzle(
                        id, nativeDynamicItemIds.containsKey(id)
                                ? nativeDynamicItemIds.get(id) : 0L, bomb));
            }
            catch(IllegalArgumentException invalid) {
                skipNativePresentation(invalid);
            }
        };
        attachedLevel.nativeSpellPresentationListener = (owner, spell, position, zap) -> {
            if(nativeAuthority == null) return;
            String sourceId = spellSourceId(owner);
            if(sourceId == null) return;
            try {
                nativeAuthority.publishNativeSpellPresentation(NativeSpellPresentation.capture(
                        sourceId, nativeSpellItemId(owner), spell, position, zap));
            }
            catch(IllegalArgumentException invalid) {
                skipNativePresentation(invalid);
            }
        };
        attachedLevel.nativeMeleePresentationListener = (owner, sword, kind, target, position, direction) -> {
            if(nativeAuthority == null) return;
            String sourceId = spellSourceId(owner);
            long itemId = weaponResolver == null ? 0L : weaponResolver.identity(sword);
            try {
                long targetObjectId = weaponResolver == null || target == null ? 0L
                        : weaponResolver.worldObjectIdentity(target);
                nativeAuthority.publishNativeMeleePresentation(NativeMeleePresentation.capture(
                        sourceId, itemId, kind, position, direction, targetObjectId));
            }
            catch(IllegalArgumentException invalid) {
                skipNativePresentation(invalid);
            }
        };
        attachedLevel.nativeRangedPresentationListener = (owner, bow, position) -> {
            if(nativeAuthority == null) return;
            String sourceId = spellSourceId(owner);
            long itemId = weaponResolver == null ? 0L : weaponResolver.identity(bow);
            try {
                nativeAuthority.publishNativeRangedPresentation(NativeRangedPresentation.capture(
                        sourceId, itemId, position));
            }
            catch(IllegalArgumentException invalid) {
                skipNativePresentation(invalid);
            }
        };
        lastLocalHealth = -1;
    }

    private String spellSourceId(Entity owner) {
        if(owner instanceof Monster) return monsterIds.get((Monster)owner);
        ParticipantId participant = participantId(owner);
        return participant == null ? null
                : AuthoritativeCombatEncounter.participantTargetId(participant);
    }

    private long nativeSpellItemId(Entity owner) {
        if(pendingNativeSpellItemId > 0L) return pendingNativeSpellItemId;
        if(owner != attachedPlayer || weaponResolver == null || attachedPlayer == null) return 0L;
        Item held = attachedPlayer.GetHeldItem();
        return held instanceof Weapon ? weaponResolver.identity((Weapon)held) : 0L;
    }

    private void attachToPlayer(Player player) {
        detachFromPlayer();
        attachedPlayer = player;
        attachedPlayer.multiplayerDamageSource = localParticipantId() == null ? null : localParticipantId().getValue();
        attachedPlayer.setWeaponAttackListener(this);
        attachedPlayer.setHealthAuthorityListener(this);
        attachedPlayer.setStatusEffectAuthority(nativeAuthority != null);
    }

    private void detachFromPlayer() {
        if(attachedPlayer != null) {
            attachedPlayer.clearWeaponAttackListener(this);
            attachedPlayer.clearHealthAuthorityListener(this);
            NativeStatusPresentation presentation = participantEffects.remove(attachedPlayer);
            if(presentation != null) presentation.clear(attachedPlayer);
            attachedPlayer.setStatusEffectAuthority(true);
        }
        attachedPlayer = null;
    }

    private void clearParticipantEffects() {
        for(Map.Entry<com.interrupt.dungeoneer.entities.Actor, NativeStatusPresentation> entry : participantEffects.entrySet())
            entry.getValue().clear(entry.getKey());
        participantEffects.clear();
    }

    private void detachFromLevel() {
        if(attachedLevel != null) clearNativeDynamicReplicas(attachedLevel);
        if(attachedLevel != null) attachedLevel.nativeAnimationListener = null;
        if(attachedLevel != null) attachedLevel.nativeDynamicListener = null;
        if(attachedLevel != null) attachedLevel.nativeSpellPresentationListener = null;
        if(attachedLevel != null) attachedLevel.nativeMeleePresentationListener = null;
        if(attachedLevel != null) attachedLevel.nativeRangedPresentationListener = null;
        if(attachedLevel != null) attachedLevel.nativeMonsterSpawnerListener = null;
        monsterSpawnerKeys.clear();
        lateMonstersRestoredLevel = null;
        clearParticipantEffects();
        if(attachedLevel != null) attachedLevel.nativeExplosionListener = null;
        if(attachedLevel != null) attachedLevel.nativeProjectileTargets = null;
        peer.drainNativeExplosions();
        peer.drainNativeAnimationCues();
        peer.drainNativeDynamicCues();
        peer.drainNativeSpellPresentations();
        peer.drainNativeMeleePresentations();
        peer.drainNativeRangedPresentations();
        for(RemoteAvatar avatar : attachedRemoteAvatars) {
            avatar.clearDamageAuthorityListener(this);
        }
        attachedRemoteAvatars.clear();
        participantActions.clear();
        for(Monster monster : monsters.values()) {
            monster.clearMultiplayerAttackListener(this);
            monster.clearMultiplayerDamageListener(this);
            monster.clearMultiplayerTarget();
            if(monster.isNetworkReplica()) monster.setNetworkReplica(false);
        }
        monsters.clear();
        monsterIds.clear();
        boundMonsterIds.clear();
        monsterDeathFrames.clear();
        retiredMonsters.clear();
        orphanedMonsterIds.clear();
        monstersAttachedLevel = null;
        monsterIndexCounter = 0;
        devSpawnThemes.clear();
        lastMonsterActions.clear();
        for(Spikes trap : traps.values()) {
            trap.clearMultiplayerTrapListener(this);
            if(trap.isNetworkReplica()) trap.setNetworkReplica(false);
        }
        traps.clear();
        trapIds.clear();
        for(Entity projectile : new ArrayList<Entity>(projectilePresentations.keySet())) {
            clearNativeProjectile(projectile);
        }
        projectilePresentations.clear();
        nativeDynamicIds.clear(); unpresentableDynamics.clear();
        nativeDynamicItemIds.clear();
        nextNativeDynamicId = 1L;
        authoritativePlayers.clear();
        attachedLevel = null;
    }

    private boolean hasRequestId() {
        return CombatRequest.isValidRequestId(nextRequestId);
    }

    private long takeNextRequestId() {
        long requestId = nextRequestId;
        nextRequestId = requestId == CombatRequest.MAX_REQUEST_ID
                ? CombatRequest.EXHAUSTED_REQUEST_ID : requestId + 1L;
        return requestId;
    }

    private boolean isUsableDirection(Vector3 direction) {
        if(direction == null || !isFinite(direction.x) || !isFinite(direction.y)
                || !isFinite(direction.z)) return false;
        float lengthSquared = direction.len2();
        return isFinite(lengthSquared) && lengthSquared >= 0.000001f
                && lengthSquared <= 3.01f;
    }

    private boolean hasLineOfSight(float startX, float startY, float endX, float endY) {
        if(Math.abs(startX - endX) + Math.abs(startY - endY) < 0.0001f) return true;
        return attachedLevel.canSee(startX, startY, endX, endY);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static CombatAction classify(Weapon weapon, DamageType damageType) {
        if(damageType == DamageType.HEALING) return CombatAction.BENEFICIAL_SPELL;
        if(weapon instanceof Wand) return CombatAction.SPELL;
        if(weapon instanceof Bow || weapon instanceof Gun) return CombatAction.PROJECTILE;
        if(weapon instanceof Sword) return CombatAction.MELEE;
        if(damageType != null && damageType != DamageType.PHYSICAL) return CombatAction.SPELL;
        return CombatAction.MELEE;
    }

    private static CombatAction combatAction(MultiplayerAttackKind kind) {
        if(kind == MultiplayerAttackKind.SPELL) return CombatAction.SPELL;
        if(kind == MultiplayerAttackKind.PROJECTILE) return CombatAction.PROJECTILE;
        return CombatAction.MELEE;
    }

    private static MultiplayerAttackKind attackKind(CombatAction action) {
        if(action == CombatAction.SPELL || action == CombatAction.BENEFICIAL_SPELL) {
            return MultiplayerAttackKind.SPELL;
        }
        if(action == CombatAction.PROJECTILE) return MultiplayerAttackKind.PROJECTILE;
        return MultiplayerAttackKind.MELEE;
    }

    private CombatAction monsterDamageAction(Monster monster, DamageType damageType) {
        if(damageType != null && damageType != DamageType.PHYSICAL) {
            return CombatAction.SPELL;
        }
        CombatAction action = lastMonsterActions.get(monster);
        return action == null ? CombatAction.MELEE : action;
    }

    private static final class NativeTrace {
        private final Monster monster;
        private final Corpse corpse;
        private final String monsterId;
        private final boolean blocked;
        private final float directionX;
        private final float directionY;
        private final float directionZ;
        private final float originX;
        private final float originY;
        private final float originZ;
        private final float impactX;
        private final float impactY;
        private final float impactZ;

        private NativeTrace(Monster monster, Corpse corpse, String monsterId, boolean blocked,
                float directionX, float directionY, float directionZ,
                float originX, float originY, float originZ,
                float impactX, float impactY, float impactZ) {
            this.monster = monster;
            this.corpse = corpse;
            this.monsterId = monsterId;
            this.blocked = blocked;
            this.directionX = directionX;
            this.directionY = directionY;
            this.directionZ = directionZ;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.impactX = impactX;
            this.impactY = impactY;
            this.impactZ = impactZ;
        }
    }

    private static final class MonsterCandidate {
        private final Monster monster;
        private final int levelOrder;
        private final String stableKey;

        private MonsterCandidate(Monster monster, int levelOrder) {
            this.monster = monster;
            this.levelOrder = levelOrder;
            // Group-placed monsters carry a per-peer random id prefix.
            stableKey = monster.getClass().getName() + "|"
                    + com.interrupt.dungeoneer.multiplayer.floor.SharedFloorIdentity.stableEntityId(monster) + "|"
                    + Float.toString(monster.x) + "|" + Float.toString(monster.y)
                    + "|" + Float.toString(monster.z);
        }
    }

    private static final class ProjectilePresentation {
        private final String sourceId;
        private final CombatAction action;
        private final float originX;
        private final float originY;
        private final float originZ;

        private final ProjectileVisual visual;

        private ProjectilePresentation(String sourceId, CombatAction action,
                float originX, float originY, float originZ, ProjectileVisual visual) {
            this.visual = visual;
            this.sourceId = sourceId;
            this.action = action;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }
    }

    private static final class TrapCandidate {
        private final Spikes trap;
        private final int levelOrder;
        private final String stableKey;

        private TrapCandidate(Spikes trap, int levelOrder) {
            this.trap = trap;
            this.levelOrder = levelOrder;
            stableKey = trap.getClass().getName() + "|"
                    + Float.toString(trap.x) + "|" + Float.toString(trap.y)
                    + "|" + Float.toString(trap.z);
        }
    }

}
