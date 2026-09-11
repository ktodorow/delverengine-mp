package com.interrupt.dungeoneer.multiplayer.combat;

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
import com.interrupt.dungeoneer.entities.Player;
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
    private final Map<Monster, String> monsterIds = new IdentityHashMap<Monster, String>();
    private final Set<String> boundMonsterIds = new LinkedHashSet<String>();
    private final Map<Monster, CombatAction> lastMonsterActions =
            new IdentityHashMap<Monster, CombatAction>();
    private final Map<String, Spikes> traps = new LinkedHashMap<String, Spikes>();
    private final Map<Spikes, String> trapIds = new IdentityHashMap<Spikes, String>();
    private final Map<com.interrupt.dungeoneer.entities.Actor, NativeStatusPresentation> participantEffects =
            new IdentityHashMap<com.interrupt.dungeoneer.entities.Actor, NativeStatusPresentation>();
    private long participantEffectGeneration;
    private CombatWeaponResolver weaponResolver;
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
        attachNativeTrapsIfPresent();
        attachNativeProjectiles();
        if(nativeAuthority == null) applyNativeDynamicStates(game.level);
        applySnapshot(peer.getCombatSnapshot());
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
            attachNativeProjectiles();
            synchronizeNativeDynamics();
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

    public boolean consumeNativeItem(ParticipantId participant, Item item) {
        return consumeNativeItem(participant, item, null);
    }

    public boolean consumeNativeItem(ParticipantId participant, Item item,
            com.badlogic.gdx.math.Vector3 direction) {
        if(nativeAuthority == null || !nativeAuthority.canApplyNativeParticipantEffect(participant)) return false;
        com.interrupt.dungeoneer.entities.Actor target = participant.equals(localParticipantId())
                ? attachedPlayer : remoteAvatar(AuthoritativeCombatEncounter.participantTargetId(participant));
        if(target == null) return false;
        if(item instanceof com.interrupt.dungeoneer.entities.items.Potion)
            ((com.interrupt.dungeoneer.entities.items.Potion)item).applyNativeEffect(target);
        else if(item instanceof com.interrupt.dungeoneer.entities.items.Food)
            ((com.interrupt.dungeoneer.entities.items.Food)item).applyNativeEffect(target);
        else if(item instanceof com.interrupt.dungeoneer.entities.items.Scroll) {
            if(direction == null) return false;
            com.interrupt.dungeoneer.entities.items.Scroll scroll =
                    (com.interrupt.dungeoneer.entities.items.Scroll)item;
            if(target instanceof RemoteAvatar && isPersonalPlayerSpell(scroll.spell)) return false;
            float previousX = target.x, previousY = target.y, previousZ = target.z;
            long previousSpellItemId = pendingNativeSpellItemId;
            pendingNativeSpellItemId = weaponResolver == null ? 0L
                    : weaponResolver.physicalIdentity(item);
            try { scroll.applyNativeEffect(target, direction); }
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

    private void synchronizeNativeParticipantHealth(com.interrupt.dungeoneer.entities.Actor actor,
            ParticipantId participant) {
        if(actor == null || participant == null || peer.getCombatSnapshot() == null) return;
        CombatantSnapshot state = peer.getCombatSnapshot().getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant));
        if(state != null && nativeAuthority.canApplyNativeParticipantEffect(participant))
            nativeAuthority.applyNativeParticipantDamage("native-effect", participant,
                    state.getHealth() - Math.max(0, actor.hp), actor.x, actor.y, actor.z);
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
        int accepted = target.applyNativeDamage(amount, damageType, instigator);
        nativeAuthority.applyNativeParticipantDamage(sourceId, targetId, accepted,
                target.x, target.y, target.z);
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
    public void onProjectileImpact(Entity projectile, Entity hit,
            float impactX, float impactY, float impactZ) {
        ProjectilePresentation presentation = projectilePresentations.get(projectile);
        if(nativeAuthority == null || presentation == null) return;
        Long dynamicId = ensureNativeDynamicIdentity(projectile);
        if(dynamicId != null) {
            try {
                nativeAuthority.publishNativeDynamicCue(NativeDynamicCue.captureImpact(
                        dynamicId, nativeDynamicItemIds.containsKey(dynamicId)
                                ? nativeDynamicItemIds.get(dynamicId) : 0L, projectile,
                        hit != null, impactX, impactY, impactZ));
            }
            catch(IllegalArgumentException invalid) {
                nativeAuthority.failNativePresentation(invalid.getMessage());
            }
        }
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
            nativeAuthority.failNativePresentation(invalid.getMessage());
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
            nativeAuthority.failNativePresentation(invalid.getMessage());
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
                    if(target instanceof Breakable) {
                        ((Breakable)target).playNetworkHitPresentation(presentation.x,
                                presentation.y, presentation.z, sword, attachedLevel);
                    }
                    else if(target instanceof Door) {
                        ((Door)target).playNetworkHitPresentation(presentation.x,
                                presentation.y, presentation.z, sword, attachedLevel);
                    }
                    else {
                        peer.failNativePresentation("Accepted Sword target "
                                + presentation.targetObjectId
                                + " is unavailable for native hit presentation.");
                        continue;
                    }
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
        }
    }

    private void synchronizeNativeDynamics(Array<Entity> entities, Set<Entity> seen) {
        for(Entity entity : entities) {
            if(entity == null || entity.nativePresentationReplica
                    || !NativeDynamicState.supports(entity)) continue;
            seen.add(entity);
            Long id = ensureNativeDynamicIdentity(entity);
            if(id == null) return;
            long itemId = nativeDynamicItemIds.containsKey(id) ? nativeDynamicItemIds.get(id) : 0L;
            publishNativeDynamic(id, itemId, entity, entity.isActive);
        }
    }

    private Long ensureNativeDynamicIdentity(Entity entity) {
        Long id = nativeDynamicIds.get(entity);
        if(id != null) return id;
        if(nativeDynamicIds.size() >= com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES
                || nextNativeDynamicId == Long.MAX_VALUE) {
            nativeAuthority.failNativePresentation("Native dynamic entity count exceeded.");
            return null;
        }
        id = nextNativeDynamicId++;
        nativeDynamicIds.put(entity, id);
        long itemId = weaponResolver == null ? 0L : weaponResolver.physicalIdentity(entity);
        nativeDynamicItemIds.put(id, itemId);
        return id;
    }

    private void publishNativeDynamic(long id, long itemId, Entity entity, boolean active) {
        try {
            nativeAuthority.synchronizeNativeDynamicState(
                    NativeDynamicState.capture(id, itemId, entity, active));
        }
        catch(IllegalArgumentException invalid) {
            nativeAuthority.failNativePresentation(invalid.getMessage());
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
            boolean newlyTracked = replica == null;
            if(replica == null && state.itemId > 0L) {
                if(weaponResolver == null) continue;
                replica = weaponResolver.physicalEntity(state.itemId);
                if(replica == null) continue;
            }
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
            nativeAuthority.failNativePresentation("Unsupported native projectile class: "
                    + projectile.getClass().getName());
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

    private Player authoritativePlayer(ParticipantId participantId) {
        Player player = authoritativePlayers.get(participantId);
        if(player == null) {
            player = new Player();
            authoritativePlayers.put(participantId, player);
        }
        if(weaponResolver != null) weaponResolver.synchronizeEquipment(participantId, player);
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
        if(!monsters.isEmpty() || attachedLevel == null) return;
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
        int count = Math.min(candidates.size(), CombatSnapshot.MAX_MONSTERS);
        for(int index = 0; index < count; index++) {
            Monster monster = candidates.get(index).monster;
            String monsterId = AuthoritativeCombatEncounter.monsterTargetId(index + 1);
            monsters.put(monsterId, monster);
            monsterIds.put(monster, monsterId);
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
            String trapId = "hazard:" + (index + 1);
            traps.put(trapId, trap);
            trapIds.put(trap, trapId);
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
        Entity stateEntity = nativeMonsterStateEntity(monster);
        nativeAuthority.bindNativeMonster(monsterId, nativeHealth(monster),
                nativeMaximumHealth(monster), stateEntity.x, stateEntity.y, stateEntity.z,
                nativeMonsterGibbed(monster));
        boundMonsterIds.add(monsterId);
    }

    private void synchronizeNativeMonsters() {
        for(Map.Entry<String, Monster> entry : monsters.entrySet()) {
            synchronizeNativeMonster(entry.getKey(), entry.getValue());
        }
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
        lastLocalHealth = health;
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
        attachedLevel = level;
        if(nativeAuthority != null) nativeAuthority.beginNativeWorld();
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
                    nativeAuthority.failNativePresentation(incompatible.getMessage());
                    return false;
                }
            }
        };
        attachedLevel.nativeAnimationListener = (owner, action) -> {
            if(nativeAuthority == null) return;
            try { nativeAuthority.publishNativeAnimationCue(NativeAnimationCue.capture(owner, action)); }
            catch(IllegalArgumentException invalid) { nativeAuthority.failNativePresentation(invalid.getMessage()); }
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
                nativeAuthority.failNativePresentation(invalid.getMessage());
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
                nativeAuthority.failNativePresentation(invalid.getMessage());
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
                nativeAuthority.failNativePresentation(invalid.getMessage());
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
                nativeAuthority.failNativePresentation(invalid.getMessage());
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
        clearParticipantEffects();
        if(attachedLevel != null) attachedLevel.nativeExplosionListener = null;
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
        nativeDynamicIds.clear();
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
            stableKey = monster.getClass().getName() + "|"
                    + (monster.id == null ? "" : monster.id) + "|"
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
