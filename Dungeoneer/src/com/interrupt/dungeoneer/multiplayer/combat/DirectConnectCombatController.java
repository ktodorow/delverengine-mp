package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.spells.Spell;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Corpse;
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
    private CombatWeaponResolver weaponResolver;
    private final Map<ParticipantId, Player> authoritativePlayers =
            new LinkedHashMap<ParticipantId, Player>();
    private final Map<Entity, ProjectilePresentation> projectilePresentations =
            new IdentityHashMap<Entity, ProjectilePresentation>();
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
        applySnapshot(peer.getCombatSnapshot());
    }

    /** Runs after movement reconciliation and native Level.tick. */
    public void update(Game game) {
        if(game == null || game.player == null || game.level == null) return;
        prepare(game);
        if(nativeAuthority != null) {
            resolveNativeCombatRequests();
            synchronizeNativeMonsters();
            attachNativeProjectiles();
        }
        applySnapshot(peer.getCombatSnapshot());
        replayPresentations(game.level, localCombatantId());
    }

    public void setWeaponResolver(CombatWeaponResolver resolver) { weaponResolver = resolver; }

    public void dispose() {
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
    public boolean onHealthIntent(Player player, int amount, DamageType damageType,
            Entity instigator) {
        ParticipantId participantId = localParticipantId();
        String targetId = participantId == null ? null
                : AuthoritativeCombatEncounter.participantTargetId(participantId);
        if(player == null || targetId == null || amount == 0) return false;
        ParticipantId participantSource = participantId(instigator);
        if(amount > 0 && participantSource != null
                && !participantSource.equals(participantId)) return true;
        Monster sourceMonster = nativeMonsterInstigator(instigator);
        Spikes sourceTrap = nativeTrapInstigator(instigator);
        if(nativeAuthority != null && sourceMonster != null) {
            nativeAuthority.applyNativeMonsterDamage(monsterIds.get(sourceMonster),
                    participantId, amount, monsterDamageAction(sourceMonster, damageType),
                    sourceMonster.x, sourceMonster.y,
                    sourceMonster.z + ATTACK_ORIGIN_HEIGHT, player.x, player.y,
                    player.z + ATTACK_ORIGIN_HEIGHT);
            return true;
        }
        if(nativeAuthority != null && sourceTrap != null && amount > 0) {
            applyNativeTrapDamage(sourceTrap, participantId, amount, player);
            return true;
        }

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
        Monster sourceMonster = nativeMonsterInstigator(instigator);
        Spikes sourceTrap = nativeTrapInstigator(instigator);
        if(nativeAuthority == null || avatar == null || damage <= 0) return;
        ParticipantId targetId = avatar.getDescriptor().getParticipantId();
        if(participantId(instigator) != null) return;
        if(sourceMonster != null) {
            nativeAuthority.applyNativeMonsterDamage(monsterIds.get(sourceMonster),
                    targetId, damage, monsterDamageAction(sourceMonster, damageType),
                    sourceMonster.x, sourceMonster.y,
                    sourceMonster.z + ATTACK_ORIGIN_HEIGHT, avatar.x, avatar.y,
                    avatar.z + ATTACK_ORIGIN_HEIGHT);
        }
        else if(sourceTrap != null) {
            applyNativeTrapDamage(sourceTrap, targetId, damage, avatar);
        }
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
            if(participantId.equals(localParticipantId())) continue;
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
            }
            else if(action == CombatAction.PROJECTILE && weapon instanceof Bow) {
                spawnNativeArrow(source, participantId, request, trace, (Bow)weapon);
            }
            else if(action == CombatAction.SPELL && weapon instanceof Wand) {
                spawnNativeSpell(source, participantId, trace, (Wand)weapon);
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
                return new NativeTrace(null, null, null,
                        directionX, directionY, directionZ,
                        source.x, source.y, originZ, previousX, previousY, previousZ);
            }
            Monster hit = intersectedMonster(x, y, z);
            if(hit != null) {
                return new NativeTrace(hit, null, monsterIds.get(hit),
                        directionX, directionY, directionZ,
                        source.x, source.y, originZ, hit.x, hit.y,
                        hit.z + hit.collision.z * 0.5f);
            }
            Corpse corpse = intersectedCorpse(x, y, z);
            if(corpse != null) {
                return new NativeTrace(null, corpse, monsterIdForCorpse(corpse),
                        directionX, directionY, directionZ,
                        source.x, source.y, originZ, corpse.x, corpse.y,
                        corpse.z + corpse.collision.z * 0.5f);
            }
            previousX = x;
            previousY = y;
            previousZ = z;
        }
        return new NativeTrace(null, null, null,
                directionX, directionY, directionZ,
                source.x, source.y, originZ, previousX, previousY, previousZ);
    }

    private void spawnNativeArrow(Entity source, ParticipantId participantId,
            CombatRequest request, NativeTrace trace, Bow bow) {
        Player stats = authoritativePlayer(participantId);
        float power = request.getAttackPower();
        float speed = power * (bow.range / 4f) * 0.5f;
        Vector3 position = new Vector3(source.x, source.y, source.z);
        Vector3 velocity = new Vector3(trace.directionX * speed,
                trace.directionY * speed, trace.directionZ * speed);
        Missile missile = new Missile(position, velocity, 73, source);
        missile.owner = source;
        missile.damage = bow.doAttackRoll(power, stats);
        missile.damageType = bow.getDamageType();
        missile.knockback = (bow.knockback + stats.getKnockbackStatBoost()) * power;
        missile.ignorePlayerCollision = true;
        missile.isActive = true;
        missile.isDynamic = true;
        missile.isOnFloor = false;
        attachedLevel.entities.add(missile);
        attachNativeProjectile(missile);
    }

    private void spawnNativeSpell(Entity source, ParticipantId participantId,
            NativeTrace trace, Wand wand) {
        // Preserve configured spell class, colour, appearance, spread, speed, and impact effects.
        Spell spell =
                (Spell)
                        KryoSerializer.copyObject(wand.spell);
        spell.baseDamage = wand.getBaseDamage();
        spell.randDamage = wand.getRandDamage();
        spell.damageType = wand.getDamageType();
        spell.doCast(source, new Vector3(trace.directionX, trace.directionZ, trace.directionY),
                new Vector3(source.x, source.y, source.z));
        attachNativeProjectiles();
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

    private void attachNativeProjectiles(Array<Entity> entities) {
        for(Entity entity : entities) attachNativeProjectile(entity);
    }

    private void attachNativeProjectile(Entity projectile) {
        if(nativeAuthority == null || projectile == null || !projectile.isActive
                || projectilePresentations.containsKey(projectile)
                || (!(projectile instanceof Projectile)
                        && !(projectile instanceof Missile))) return;

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
            }
            avatar.applyAuthoritativeHealth(combatant.getHealth(),
                    combatant.getMaximumHealth());
        }
        for(RemoteAvatar avatar : new ArrayList<RemoteAvatar>(attachedRemoteAvatars)) {
            if(visible.contains(avatar)) continue;
            avatar.clearDamageAuthorityListener(this);
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
        if(nativeAuthority == null || event.getPhase() == CombatPresentationPhase.DAMAGE) {
            return false;
        }
        CombatAction action = event.getAction();
        return action == CombatAction.PROJECTILE || action == CombatAction.SPELL
                || action == CombatAction.BENEFICIAL_SPELL;
    }

    private void attachToLevel(Level level) {
        detachFromLevel();
        attachedLevel = level;
        lastLocalHealth = -1;
    }

    private void attachToPlayer(Player player) {
        detachFromPlayer();
        attachedPlayer = player;
        attachedPlayer.setWeaponAttackListener(this);
        attachedPlayer.setHealthAuthorityListener(this);
    }

    private void detachFromPlayer() {
        if(attachedPlayer != null) {
            attachedPlayer.clearWeaponAttackListener(this);
            attachedPlayer.clearHealthAuthorityListener(this);
        }
        attachedPlayer = null;
    }

    private void detachFromLevel() {
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
        private final float directionX;
        private final float directionY;
        private final float directionZ;
        private final float originX;
        private final float originY;
        private final float originZ;
        private final float impactX;
        private final float impactY;
        private final float impactZ;

        private NativeTrace(Monster monster, Corpse corpse, String monsterId,
                float directionX, float directionY, float directionZ,
                float originX, float originY, float originZ,
                float impactX, float impactY, float impactZ) {
            this.monster = monster;
            this.corpse = corpse;
            this.monsterId = monsterId;
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
