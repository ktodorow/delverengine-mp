package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.movement.MovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host-owned state and validation around original Delver Monsters in one encounter. */
public final class AuthoritativeCombatEncounter {
    /** Legacy first-Monster identity retained for protocol/test compatibility. */
    public static final String SHARED_MONSTER_ID = "shared-spider-1";
    public static final String MONSTER_ID_PREFIX = "monster:";
    public static final int PARTICIPANT_MAXIMUM_HEALTH = 8;
    public static final int MONSTER_MAXIMUM_HEALTH = 24;
    private static final long MONSTER_STATE_INTERVAL_TICKS = 3L;
    private static final int MAX_PENDING_NATIVE_REQUESTS = 64;
    private static final long HEARD_TARGET_TICKS = 180L;
    private static final float VISIBILITY_DISTANCE = 17f;
    private static final float ATTACK_ORIGIN_HEIGHT = 0.35f;
    private static final float ATTACK_TRACE_STEP = 0.1f;
    private static final float MONSTER_HIT_RADIUS = 0.4f;
    private static final float MONSTER_HIT_HEIGHT = 0.65f;
    private static final float PARTICIPANT_HIT_RADIUS = 0.35f;
    private static final float PARTICIPANT_HIT_HEIGHT = 0.75f;

    private final Map<ParticipantId, MutableCombatant> participants =
            new LinkedHashMap<ParticipantId, MutableCombatant>();
    private final Map<String, MutableCombatant> combatants =
            new LinkedHashMap<String, MutableCombatant>();
    private final Map<ParticipantId, Long> highestRequestIds =
            new LinkedHashMap<ParticipantId, Long>();
    private final Map<ParticipantId, MutableActionCadence> actionCadences =
            new LinkedHashMap<ParticipantId, MutableActionCadence>();
    private final Map<String, MutableCombatant> monsters =
            new LinkedHashMap<String, MutableCombatant>();
    private final Map<String, String> monsterTargetIds =
            new LinkedHashMap<String, String>();
    private final Map<String, ParticipantId> lastAttackers =
            new LinkedHashMap<String, ParticipantId>();
    private final Map<String, Long> lastMonsterStateTicks =
            new LinkedHashMap<String, Long>();
    private final MovementCollisionWorld world;
    private final List<CombatRequest> pendingNativeRequests =
            new ArrayList<CombatRequest>();
    private long snapshotSequence = 1L;
    private long presentationSequence;

    public AuthoritativeCombatEncounter(List<MovementEntityDescriptor> descriptors,
            MovementCollisionWorld world) {
        if(descriptors == null || descriptors.isEmpty() || descriptors.size() > 4) {
            throw new IllegalArgumentException("Combat encounter needs one to four Participants.");
        }
        if(world == null) throw new IllegalArgumentException("Combat world cannot be null.");
        this.world = world;
        for(MovementEntityDescriptor descriptor : descriptors) {
            if(descriptor == null || participants.containsKey(descriptor.getParticipantId())) {
                throw new IllegalArgumentException("Combat Participants must be unique.");
            }
            MutableCombatant participant = new MutableCombatant(
                    participantTargetId(descriptor.getParticipantId()), CombatantKind.PARTICIPANT,
                    PARTICIPANT_MAXIMUM_HEALTH);
            participants.put(descriptor.getParticipantId(), participant);
            combatants.put(participant.id, participant);
            actionCadences.put(descriptor.getParticipantId(), new MutableActionCadence());
        }
    }

    public static String participantTargetId(ParticipantId participantId) {
        if(participantId == null) throw new IllegalArgumentException("Participant ID cannot be null.");
        return "participant:" + participantId.getValue();
    }

    public static String monsterTargetId(int encounterIndex) {
        if(encounterIndex < 1 || encounterIndex > CombatSnapshot.MAX_MONSTERS) {
            throw new IllegalArgumentException("Monster encounter index is outside bounds.");
        }
        return MONSTER_ID_PREFIX + encounterIndex;
    }

    public synchronized void updateParticipantPosition(ParticipantId participantId, float x, float y) {
        updateParticipantPosition(participantId, x, y, 0.5f);
    }

    public synchronized void updateParticipantPosition(ParticipantId participantId,
            float x, float y, float z) {
        MutableCombatant participant = participants.get(participantId);
        if(participant == null) return;
        participant.x = x;
        participant.y = y;
        participant.z = z;
    }

    public synchronized void setMonsterPosition(float x, float y, float z) {
        MutableCombatant monster = legacyMonster();
        monster.x = x;
        monster.y = y;
        monster.z = z;
    }

    public synchronized void bindNativeMonster(long hostTick, int health,
            int maximumHealth, float x, float y, float z, HostSessionOutput output) {
        bindNativeMonster(hostTick, SHARED_MONSTER_ID, health, maximumHealth,
                x, y, z, output);
    }

    public synchronized void bindNativeMonster(long hostTick, String monsterId, int health,
            int maximumHealth, float x, float y, float z, HostSessionOutput output) {
        bindNativeMonster(hostTick, monsterId, health, maximumHealth,
                x, y, z, false, output);
    }

    public synchronized void bindNativeMonster(long hostTick, String monsterId, int health,
            int maximumHealth, float x, float y, float z, boolean gibbed,
            HostSessionOutput output) {
        requireMonsterId(monsterId);
        requireNativeMonsterState(health, maximumHealth, x, y, z);
        requireCorpseState(health, gibbed);
        MutableCombatant monster = monsters.get(monsterId);
        if(monster == null) {
            if(monsters.size() >= CombatSnapshot.MAX_MONSTERS) return;
            monster = new MutableCombatant(monsterId, CombatantKind.MONSTER, maximumHealth);
            monsters.put(monsterId, monster);
            combatants.put(monsterId, monster);
            monsterTargetIds.put(monsterId, "");
        }
        monster.maximumHealth = maximumHealth;
        monster.health = health;
        monster.x = x;
        monster.y = y;
        monster.z = z;
        monster.gibbed = gibbed;
        publishMonsterState(hostTick, monsterId, output);
    }

    public synchronized void synchronizeNativeMonster(long hostTick, int health,
            int maximumHealth, float x, float y, float z, HostSessionOutput output) {
        synchronizeNativeMonster(hostTick, SHARED_MONSTER_ID, health, maximumHealth,
                x, y, z, output);
    }

    public synchronized void synchronizeNativeMonster(long hostTick, String monsterId,
            int health, int maximumHealth, float x, float y, float z,
            HostSessionOutput output) {
        synchronizeNativeMonster(hostTick, monsterId, health, maximumHealth,
                x, y, z, false, output);
    }

    public synchronized void synchronizeNativeMonster(long hostTick, String monsterId,
            int health, int maximumHealth, float x, float y, float z, boolean gibbed,
            HostSessionOutput output) {
        requireMonsterId(monsterId);
        requireNativeMonsterState(health, maximumHealth, x, y, z);
        requireCorpseState(health, gibbed);
        MutableCombatant monster = monsters.get(monsterId);
        if(monster == null) {
            bindNativeMonster(hostTick, monsterId, health, maximumHealth,
                    x, y, z, gibbed, output);
            return;
        }
        boolean healthChanged = monster.health != health
                || monster.maximumHealth != maximumHealth;
        boolean positionChanged = monster.x != x || monster.y != y || monster.z != z;
        boolean corpseStateChanged = monster.gibbed != gibbed;
        monster.maximumHealth = maximumHealth;
        monster.health = health;
        monster.x = x;
        monster.y = y;
        monster.z = z;
        monster.gibbed = gibbed;
        Long previousStateTick = lastMonsterStateTicks.get(monsterId);
        long lastStateTick = previousStateTick == null
                ? Long.MIN_VALUE / 2L : previousStateTick.longValue();
        if(healthChanged || corpseStateChanged || positionChanged
                && hostTick - lastStateTick >= MONSTER_STATE_INTERVAL_TICKS) {
            publishMonsterState(hostTick, monsterId, output);
        }
    }

    public synchronized List<CombatRequest> drainNativeCombatRequests() {
        List<CombatRequest> drained = new ArrayList<CombatRequest>(pendingNativeRequests);
        pendingNativeRequests.clear();
        return drained;
    }

    public synchronized void applyNativeMonsterDamage(long hostTick,
            ParticipantId targetId, int damage, CombatAction action,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, HostSessionOutput output) {
        applyNativeMonsterDamage(hostTick, SHARED_MONSTER_ID, targetId, damage, action,
                originX, originY, originZ, impactX, impactY, impactZ, output);
    }

    public synchronized void applyNativeMonsterDamage(long hostTick, String monsterId,
            ParticipantId targetId, int damage, CombatAction action,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, HostSessionOutput output) {
        if(targetId == null || damage <= 0 || action == null || !action.isHarmful()) return;
        MutableCombatant monster = monsters.get(monsterId);
        if(monster == null || !monster.isLiving()) return;
        MutableCombatant target = participants.get(targetId);
        if(target == null || !target.isCombatEligible()) return;
        boolean changed = target.damage(damage);
        publishPresentation(hostTick, monster.id, target.id, action,
                CombatPresentationPhase.DAMAGE,
                originX, originY, originZ, impactX, impactY, impactZ, changed, output);
        if(changed) publish(hostTick, output);
    }

    public synchronized void publishNativePresentation(long hostTick,
            String sourceId, String targetId, CombatAction action,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged,
            HostSessionOutput output) {
        publishPresentation(hostTick, sourceId, targetId, action,
                originX, originY, originZ, impactX, impactY, impactZ,
                stateChanged, output);
    }

    public synchronized void publishNativePresentation(long hostTick,
            String sourceId, String targetId, CombatAction action,
            CombatPresentationPhase phase,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged,
            HostSessionOutput output) {
        publishPresentation(hostTick, sourceId, targetId, action, phase,
                originX, originY, originZ, impactX, impactY, impactZ,
                stateChanged, output);
    }

    public synchronized void recordNativeMonsterAttacker(long hostTick,
            String monsterId, ParticipantId attackerId) {
        MutableCombatant monster = monsters.get(monsterId);
        MutableCombatant attacker = participants.get(attackerId);
        if(monster == null || !monster.isLiving()
                || attacker == null || !attacker.isCombatEligible()) return;
        lastAttackers.put(monsterId, attackerId);
        attacker.lastHeardTick = hostTick;
    }

    public synchronized void applyNativeEnvironmentalDamage(long hostTick,
            String sourceId, ParticipantId targetId, int damage,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ,
            HostSessionOutput output) {
        MutableCombatant target = participants.get(targetId);
        if(sourceId == null || sourceId.trim().isEmpty() || target == null
                || !target.isCombatEligible() || !target.isLiving() || damage <= 0) return;
        boolean changed = target.damage(damage);
        publishPresentation(hostTick, sourceId, target.id,
                CombatAction.ENVIRONMENTAL_HAZARD, CombatPresentationPhase.DAMAGE,
                originX, originY, originZ, impactX, impactY, impactZ,
                changed, output);
        if(changed) publish(hostTick, output);
    }

    public synchronized void setParticipantCombatEligible(ParticipantId participantId,
            boolean combatEligible) {
        if(participantId == null) return;
        MutableCombatant participant = participants.get(participantId);
        if(participant == null || participant.combatEligible == combatEligible) return;
        participant.combatEligible = combatEligible;
        if(!combatEligible) {
            participant.lastHeardTick = Long.MIN_VALUE / 2L;
            for(Map.Entry<String, ParticipantId> attacker
                    : new ArrayList<Map.Entry<String, ParticipantId>>(lastAttackers.entrySet())) {
                if(participantId.equals(attacker.getValue())) {
                    lastAttackers.remove(attacker.getKey());
                }
            }
        }
    }

    public synchronized long getNextRequestId(ParticipantId participantId) {
        Long highest = highestRequestIds.get(participantId);
        return highest == null ? 1L : highest.longValue() >= CombatRequest.MAX_REQUEST_ID
                ? CombatRequest.EXHAUSTED_REQUEST_ID : highest.longValue() + 1L;
    }

    public synchronized void apply(long hostTick, CombatRequest request, HostSessionOutput output) {
        MutableCombatant source = participants.get(request.getParticipantId());
        if(source == null || !source.isCombatEligible()) return;
        Long previous = highestRequestIds.get(request.getParticipantId());
        if(previous != null && request.getRequestId() <= previous.longValue()) return;
        highestRequestIds.put(request.getParticipantId(), request.getRequestId());
        CombatAction action = request.getAction();
        MutableActionCadence cadence = actionCadences.get(request.getParticipantId());
        if(cadence == null || !cadence.tryUse(request, hostTick)) return;

        if(request.isDirected()) {
            applyDirected(hostTick, source, request, output);
            return;
        }

        MutableCombatant target = combatants.get(request.getTargetId());
        if(target == null || target.kind == CombatantKind.PARTICIPANT
                && !target.isCombatEligible()) return;
        boolean present = target.isLiving();
        boolean changed = false;
        if(!action.isHarmful()) {
            if(target.kind == CombatantKind.PARTICIPANT && target.isLiving()
                    && (target == source || canReach(source, target, action.getMaximumRange()))) {
                changed = target.heal(action.getAmount());
            }
        }
        else if(action == CombatAction.SELF_DAMAGE || action == CombatAction.ENVIRONMENTAL_HAZARD) {
            if(target == source) changed = target.damage(action.getAmount());
        }
        else if(target.kind == CombatantKind.MONSTER && target.isLiving()
                && canReach(source, target, action.getMaximumRange())) {
            changed = target.damage(action.getAmount());
            source.lastHeardTick = hostTick;
            lastAttackers.put(target.id, request.getParticipantId());
        }
        if(present) publishPresentation(hostTick, source, target, action, changed, output);
        if(changed) publish(hostTick, output);
    }

    private void applyDirected(long hostTick, MutableCombatant source,
            CombatRequest request, HostSessionOutput output) {
        CombatAction action = request.getAction();
        if(!action.isDirected()) return;

        if(action.isHarmful()) {
            source.lastHeardTick = hostTick;
            if(pendingNativeRequests.size() < MAX_PENDING_NATIVE_REQUESTS) {
                pendingNativeRequests.add(request);
            }
            return;
        }

        TraceResult trace = traceAttack(source, request);
        MutableCombatant target = trace.target;
        boolean changed = false;
        if(target != null && target.isLiving()) {
            if(target.kind == CombatantKind.PARTICIPANT) {
                changed = target.heal(action.getAmount());
            }
        }
        publishPresentation(hostTick, source, target == null ? "" : target.id, action,
                source.z + ATTACK_ORIGIN_HEIGHT, trace.impactX, trace.impactY,
                trace.impactZ, changed, output);
        if(changed) publish(hostTick, output);
    }

    private TraceResult traceAttack(MutableCombatant source, CombatRequest request) {
        float aimLength = (float)Math.sqrt(request.getAimX() * request.getAimX()
                + request.getAimY() * request.getAimY()
                + request.getAimZ() * request.getAimZ());
        float directionX = request.getAimX() / aimLength;
        float directionY = request.getAimY() / aimLength;
        float directionZ = request.getAimZ() / aimLength;
        float originZ = source.z + ATTACK_ORIGIN_HEIGHT;
        float maximumRange = request.getAction().getMaximumRange();
        float previousX = source.x;
        float previousY = source.y;
        float previousZ = originZ;

        for(float distance = ATTACK_TRACE_STEP; distance <= maximumRange;
                distance += ATTACK_TRACE_STEP) {
            float x = source.x + directionX * distance;
            float y = source.y + directionY * distance;
            float z = originZ + directionZ * distance;
            float floorZ = world.getFloorZ(x, y, source.z);
            if(z <= floorZ) return new TraceResult(null, x, y, floorZ);
            MutableCombatant participant = intersectedParticipant(source, x, y, z);
            if(participant != null
                    && world.hasLineOfSight(source.x, source.y, participant.x, participant.y)) {
                return new TraceResult(participant, participant.x, participant.y, participant.z);
            }
            MutableCombatant monster = intersectedMonster(x, y, z);
            if(monster != null
                    && world.hasLineOfSight(source.x, source.y, monster.x, monster.y)) {
                return new TraceResult(monster, monster.x, monster.y, monster.z);
            }
            if(!world.hasLineOfSight(source.x, source.y, x, y)) {
                return new TraceResult(null, previousX, previousY,
                        Math.max(floorZ, previousZ));
            }
            previousX = x;
            previousY = y;
            previousZ = z;
        }
        return new TraceResult(null, previousX, previousY, previousZ);
    }

    private MutableCombatant intersectedParticipant(MutableCombatant source,
            float x, float y, float z) {
        for(MutableCombatant participant : participants.values()) {
            if(participant == source || !participant.isCombatEligible()) continue;
            float deltaX = x - participant.x;
            float deltaY = y - participant.y;
            if(deltaX * deltaX + deltaY * deltaY
                    <= PARTICIPANT_HIT_RADIUS * PARTICIPANT_HIT_RADIUS
                    && Math.abs(z - participant.z) <= PARTICIPANT_HIT_HEIGHT) {
                return participant;
            }
        }
        return null;
    }

    private MutableCombatant intersectedMonster(float x, float y, float z) {
        MutableCombatant nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for(MutableCombatant monster : monsters.values()) {
            if(!monster.isLiving()) continue;
            float deltaX = x - monster.x;
            float deltaY = y - monster.y;
            float distance = deltaX * deltaX + deltaY * deltaY;
            if(distance <= MONSTER_HIT_RADIUS * MONSTER_HIT_RADIUS
                    && Math.abs(z - monster.z) <= MONSTER_HIT_HEIGHT
                    && distance < nearestDistance) {
                nearest = monster;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public synchronized void tick(long hostTick, HostSessionOutput output) {
        tick(hostTick, 1f / 60f, output);
    }

    public synchronized void tick(long hostTick, float fixedDeltaSeconds,
            HostSessionOutput output) {
        boolean changed = false;
        for(MutableCombatant monster : monsters.values()) {
            String currentTarget = monsterTargetIds.get(monster.id);
            if(currentTarget == null) currentTarget = "";
            MutableCombatant target = monster.isLiving()
                    ? selectMonsterTarget(hostTick, monster) : null;
            String nextTarget = target == null ? "" : target.id;
            if(!nextTarget.equals(currentTarget)) {
                monsterTargetIds.put(monster.id, nextTarget);
                changed = true;
            }
        }
        if(changed) publish(hostTick, output);
    }

    public synchronized CombatSnapshot getSnapshot(long hostTick) {
        return snapshot(hostTick, snapshotSequence);
    }

    private MutableCombatant selectMonsterTarget(long hostTick, MutableCombatant monster) {
        ParticipantId lastAttacker = lastAttackers.get(monster.id);
        MutableCombatant attacker = lastAttacker == null ? null : participants.get(lastAttacker);
        if(isVisible(monster, attacker)) return attacker;

        List<MutableCombatant> visible = new ArrayList<MutableCombatant>();
        for(MutableCombatant participant : participants.values()) {
            if(!participant.isCombatEligible()) continue;
            if(isVisible(monster, participant)) visible.add(participant);
        }
        if(!visible.isEmpty()) return nearest(monster, visible);

        List<MutableCombatant> heard = new ArrayList<MutableCombatant>();
        for(MutableCombatant participant : participants.values()) {
            if(participant.isCombatEligible()
                    && hostTick - participant.lastHeardTick <= HEARD_TARGET_TICKS) {
                heard.add(participant);
            }
        }
        return heard.isEmpty() ? null : nearest(monster, heard);
    }

    private MutableCombatant nearest(final MutableCombatant monster,
            List<MutableCombatant> candidates) {
        candidates.sort(new Comparator<MutableCombatant>() {
            @Override
            public int compare(MutableCombatant left, MutableCombatant right) {
                int distance = Float.compare(distanceSquared(monster, left),
                        distanceSquared(monster, right));
                return distance != 0 ? distance : left.id.compareTo(right.id);
            }
        });
        return candidates.get(0);
    }

    private boolean isVisible(MutableCombatant monster, MutableCombatant participant) {
        return participant != null && participant.isCombatEligible()
                && distanceSquared(monster, participant)
                        <= VISIBILITY_DISTANCE * VISIBILITY_DISTANCE
                && world.hasLineOfSight(monster.x, monster.y, participant.x, participant.y);
    }

    private float distanceSquared(MutableCombatant monster, MutableCombatant participant) {
        float x = participant.x - monster.x;
        float y = participant.y - monster.y;
        return x * x + y * y;
    }

    private void publish(long hostTick, HostSessionOutput output) {
        snapshotSequence++;
        output.event(new CombatStateEvent(snapshot(hostTick, snapshotSequence)));
    }

    private void publishPresentation(long hostTick, MutableCombatant source,
            MutableCombatant target, CombatAction action, boolean stateChanged,
            HostSessionOutput output) {
        publishPresentation(hostTick, source, target.id, action,
                source.z, target.x, target.y, target.z, stateChanged, output);
    }

    private void publishPresentation(long hostTick, MutableCombatant source,
            String targetId, CombatAction action, float originZ, float impactX,
            float impactY, float impactZ, boolean stateChanged, HostSessionOutput output) {
        publishPresentation(hostTick, source.id, targetId, action, source.x, source.y,
                originZ, impactX, impactY, impactZ, stateChanged, output);
    }

    private void publishPresentation(long hostTick, String sourceId, String targetId,
            CombatAction action, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged,
            HostSessionOutput output) {
        publishPresentation(hostTick, sourceId, targetId, action,
                stateChanged && action != null && action.isHarmful()
                        ? CombatPresentationPhase.DAMAGE
                        : CombatPresentationPhase.ATTACK,
                originX, originY, originZ, impactX, impactY, impactZ,
                stateChanged, output);
    }

    private void publishPresentation(long hostTick, String sourceId, String targetId,
            CombatAction action, CombatPresentationPhase phase,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged,
            HostSessionOutput output) {
        if(output == null || sourceId == null || sourceId.trim().isEmpty()
                || targetId == null || action == null || phase == null) return;
        output.event(new CombatPresentationEvent(++presentationSequence, hostTick,
                sourceId, targetId, action, phase, originX, originY, originZ,
                impactX, impactY, impactZ, stateChanged));
    }

    private void publishMonsterState(long hostTick, String monsterId,
            HostSessionOutput output) {
        lastMonsterStateTicks.put(monsterId, hostTick);
        publish(hostTick, output);
    }

    private CombatSnapshot snapshot(long hostTick, long sequence) {
        List<CombatantSnapshot> state = new ArrayList<CombatantSnapshot>();
        for(MutableCombatant combatant : combatants.values()) {
            state.add(new CombatantSnapshot(combatant.id, combatant.kind,
                    combatant.health, combatant.maximumHealth));
        }
        List<MonsterSnapshot> monsterStates = new ArrayList<MonsterSnapshot>();
        for(MutableCombatant monster : monsters.values()) {
            String targetId = monsterTargetIds.get(monster.id);
            monsterStates.add(new MonsterSnapshot(monster.id,
                    targetId == null ? "" : targetId,
                    monster.x, monster.y, monster.z, monster.gibbed));
        }
        return new CombatSnapshot(sequence, hostTick, monsterStates, state);
    }

    private boolean isWithinRange(MutableCombatant source, MutableCombatant target,
            float maximumRange) {
        if(maximumRange <= 0f) return true;
        float x = source.x - target.x;
        float y = source.y - target.y;
        return x * x + y * y <= maximumRange * maximumRange;
    }

    private boolean canReach(MutableCombatant source, MutableCombatant target,
            float maximumRange) {
        return isWithinRange(source, target, maximumRange)
                && world.hasLineOfSight(source.x, source.y, target.x, target.y);
    }

    private static final class MutableCombatant {
        private final String id;
        private final CombatantKind kind;
        private int maximumHealth;
        private int health;
        private float x;
        private float y;
        private float z;
        private boolean gibbed;
        private boolean combatEligible = true;
        private long lastHeardTick = Long.MIN_VALUE / 2L;

        private MutableCombatant(String id, CombatantKind kind, int maximumHealth) {
            this.id = id;
            this.kind = kind;
            this.maximumHealth = maximumHealth;
            health = maximumHealth;
        }

        private boolean isLiving() { return health > 0; }

        private boolean isCombatEligible() { return combatEligible && isLiving(); }

        private boolean damage(int amount) {
            int next = Math.max(0, health - amount);
            if(next == health) return false;
            health = next;
            return true;
        }

        private boolean heal(int amount) {
            int next = Math.min(maximumHealth, health + amount);
            if(next == health) return false;
            health = next;
            return true;
        }
    }

    private MutableCombatant legacyMonster() {
        MutableCombatant monster = monsters.get(SHARED_MONSTER_ID);
        if(monster != null) return monster;
        monster = new MutableCombatant(SHARED_MONSTER_ID, CombatantKind.MONSTER,
                MONSTER_MAXIMUM_HEALTH);
        monster.x = 16.5f;
        monster.y = 16.5f;
        monster.z = 0.5f;
        monsters.put(monster.id, monster);
        combatants.put(monster.id, monster);
        monsterTargetIds.put(monster.id, "");
        return monster;
    }

    private static void requireMonsterId(String monsterId) {
        if(monsterId == null || monsterId.trim().isEmpty()) {
            throw new IllegalArgumentException("Monster network identity cannot be empty.");
        }
    }

    private static void requireNativeMonsterState(int health, int maximumHealth,
            float x, float y, float z) {
        if(maximumHealth < 1 || health < 0 || health > maximumHealth) {
            throw new IllegalArgumentException("Native Monster health is outside bounds.");
        }
        if(!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            throw new IllegalArgumentException("Native Monster position must be finite.");
        }
    }

    private static void requireCorpseState(int health, boolean gibbed) {
        if(gibbed && health > 0) {
            throw new IllegalArgumentException("Living native Monster cannot be gibbed.");
        }
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static final class MutableActionCadence {
        private long nextDirectedTick = Long.MIN_VALUE;
        private long nextTargetedBenefitTick = Long.MIN_VALUE;
        private long nextSelfDamageTick = Long.MIN_VALUE;
        private long nextHazardTick = Long.MIN_VALUE;

        private boolean tryUse(CombatRequest request, long hostTick) {
            CombatAction action = request.getAction();
            if(action == CombatAction.BENEFICIAL_SPELL && !request.isDirected()) {
                if(hostTick < nextTargetedBenefitTick) return false;
                nextTargetedBenefitTick = hostTick + action.getMinimumIntervalTicks();
                return true;
            }
            if(action.isDirected()) {
                if(hostTick < nextDirectedTick) return false;
                nextDirectedTick = hostTick + action.getMinimumIntervalTicks();
                return true;
            }
            if(action == CombatAction.SELF_DAMAGE) {
                if(hostTick < nextSelfDamageTick) return false;
                nextSelfDamageTick = hostTick + action.getMinimumIntervalTicks();
                return true;
            }
            if(action == CombatAction.ENVIRONMENTAL_HAZARD) {
                if(hostTick < nextHazardTick) return false;
                nextHazardTick = hostTick + action.getMinimumIntervalTicks();
                return true;
            }
            return false;
        }
    }

    private static final class TraceResult {
        private final MutableCombatant target;
        private final float impactX;
        private final float impactY;
        private final float impactZ;

        private TraceResult(MutableCombatant target, float impactX, float impactY, float impactZ) {
            this.target = target;
            this.impactX = impactX;
            this.impactY = impactY;
            this.impactZ = impactZ;
        }
    }
}
