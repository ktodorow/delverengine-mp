package com.interrupt.dungeoneer.multiplayer.network;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint;
import com.interrupt.dungeoneer.multiplayer.items.ItemActionResult;
import com.interrupt.dungeoneer.multiplayer.combat.NativeExplosionPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState;
import com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue;
import com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation;

import com.interrupt.dungeoneer.multiplayer.items.DoorFeedback;

import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;

import com.interrupt.dungeoneer.multiplayer.combat.ProjectileVisual;
import com.interrupt.dungeoneer.multiplayer.movement.MovementObstacle;
import com.interrupt.dungeoneer.multiplayer.movement.LevelMovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.multiplayer.lives.AuthoritativeLives;
import com.interrupt.dungeoneer.multiplayer.lives.DeathDropRules;
import com.interrupt.dungeoneer.multiplayer.combat.DeathCause;
import com.interrupt.dungeoneer.multiplayer.items.ItemKind;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSession;
import com.interrupt.dungeoneer.multiplayer.host.HostDisconnectOutcome;
import com.interrupt.dungeoneer.multiplayer.host.HostPersistedState;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionStorage;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionTransport;
import com.interrupt.dungeoneer.multiplayer.host.HostTransitionOutcome;
import com.interrupt.dungeoneer.multiplayer.combat.AuthoritativeCombatEncounter;
import com.interrupt.dungeoneer.multiplayer.combat.AuthoritativeEncounterSimulation;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationPhase;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationJournal;
import com.interrupt.dungeoneer.multiplayer.combat.CombatRequest;
import com.interrupt.dungeoneer.multiplayer.combat.CombatStateEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeCombatAuthority;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSave;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSaveStore;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster.ClaimOutcome;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster.ClaimStatus;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSlot;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotClaimRequest;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CampaignChallenge;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CombatActionRequestMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CombatPresentationMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CombatStateMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientHello;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.DiscoveryAnnouncement;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.DiscoveryProbe;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.EntityDespawn;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.EntitySpawn;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.MovementInputs;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.MovementSnapshotMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.PartyStatusMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.PartyChatDelivery;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.PartyChatSubmit;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.PauseRequestedMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.PauseRequestMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.PauseSessionStateMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ProtocolException;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.RejectCode;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerAccepted;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SessionReady;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SlotClaim;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SlotPending;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegister;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegistered;
import com.interrupt.dungeoneer.multiplayer.movement.AuthoritativeMovementSimulation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputCommand;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementReplicationState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSpawn;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.movement.RectangularMovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.multiplayer.participant.ReconnectGrace;
import com.interrupt.dungeoneer.multiplayer.communication.PartyChatMessage;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;
import com.interrupt.dungeoneer.multiplayer.communication.PauseRequest;
import com.interrupt.dungeoneer.multiplayer.communication.PauseSessionState;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host authority for lobby approval, live admission and persistent Campaign Slots. */
public final class DirectConnectHost implements DirectConnectPeer, NativeCombatAuthority,
        com.interrupt.dungeoneer.multiplayer.economy.EconomyHost {
    private static final float REVIVAL_REACH = 1.6f;
    /** Knockback or sliding beyond this breaks Revival even without movement input. */
    private static final float REVIVAL_DRIFT = 0.5f;
    private static final int SNAPSHOT_INTERVAL_TICKS =
            AuthoritativeHostSession.TICKS_PER_SECOND / 20;
    public static final long RECONNECT_GRACE_TICKS =
            AuthoritativeHostSession.TICKS_PER_SECOND * 30L;

    private final DirectConnectCompatibility compatibility;
    private final CampaignRoster roster;
    private final CampaignRosterStore rosterStore;
    private final CampaignSaveStore campaignSaveStore;
    private CampaignSave durableCampaign;
    private com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot partyProgression =
            com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.empty();
    private final boolean resumedCampaign;
    private volatile SharedFloorFingerprint savedFloorFingerprint;
    private boolean resumeNativeWorldPending;
    private byte[] restoredNativeFloor;
    private java.util.function.Supplier<byte[]> nativeFloorCapture;
    private CampaignSave lastCapturedCampaign;
    private Thread recoveryShutdownHook;
    private volatile MovementCollisionWorld movementWorld;
    private volatile com.interrupt.dungeoneer.multiplayer.floor.PartyTransition travel =
            com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.idle(1L, 1L);
    private final Map<String, float[]> partyPortals = new LinkedHashMap<>();
    private ParticipantId travelActivator;
    private String activeAreaKey;
    private String activeFloorId;
    private final Map<String, com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState> dormantFloors = new LinkedHashMap<>();
    private final MovementReplicationState movementReplication =
            new MovementReplicationState();
    private final EventLoopGroup acceptGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup networkGroup = new NioEventLoopGroup(2);
    private final SecureRandom random = new SecureRandom();
    /** One seed per Host session: every peer builds the announced floor from it. */
    private volatile long sharedFloorSeed;
    private SharedFloorFingerprint hostFloorFingerprint;
    private final long reconnectGraceTicks;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final CombatPresentationJournal combatPresentations =
            new CombatPresentationJournal(DirectConnectProtocol.MAX_COMBAT_PRESENTATION_EVENTS);
    private final HostSessionOutput nativeCombatOutput = new HostSessionOutput() {
        @Override
        public void event(HostSessionEvent event) {
            publishCombatEvent(event);
        }

        @Override
        public void disconnect(HostDisconnectOutcome outcome) { }

        @Override
        public void transition(HostTransitionOutcome outcome) { }

        @Override
        public void persist(HostPersistedState state) { }
    };
    private final String sessionId;
    private final Map<ParticipantId, com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge> personalKnowledge = new LinkedHashMap<>();
    private final Map<Channel, RemoteConnection> connections =
            new LinkedHashMap<Channel, RemoteConnection>();
    private final Map<LauncherIdentity, GraceParticipant> reconnectingParticipants =
            new LinkedHashMap<LauncherIdentity, GraceParticipant>();
    /** Approved slots whose protected live entity already returned safely after grace. */
    private final Map<LauncherIdentity, ReturnedParticipant> returnedParticipants =
            new LinkedHashMap<LauncherIdentity, ReturnedParticipant>();
    /** Late slots have no Active Floor body until a Fresh Return. */
    private final Set<LauncherIdentity> lateParticipants = new LinkedHashSet<LauncherIdentity>();
    private final Map<LauncherIdentity, Long> lateEntryGenerations = new LinkedHashMap<LauncherIdentity, Long>();
    private long nextAdmissionId;
    private static final long ADMISSION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60L);

    private boolean nativeWorldStable = true;
    private volatile long authoritativeHostTick;
    private long floorActivationTickFence = -1L;
    private final java.util.concurrent.atomic.AtomicReference<CombatSnapshot> publishedCombatSnapshot =
            new java.util.concurrent.atomic.AtomicReference<CombatSnapshot>();
    private final Set<LauncherIdentity> freshReturnsPending = new LinkedHashSet<LauncherIdentity>();

    private volatile DirectConnectStatus status;
    private volatile Channel tcpListener;
    private volatile Channel udpListener;
    private volatile int boundPort;
    private volatile boolean sessionStarted;
    private volatile NetworkEntityId localMovementEntityId;
    private volatile AuthoritativeMovementSimulation movementSimulation;
    private volatile AuthoritativeCombatEncounter combatEncounter;
    private volatile AuthoritativeHostSession movementSession;
    private volatile ScheduledFuture<?> movementTask;
    private volatile PartyStatusSnapshot partyStatus;
    private volatile int startingLives = AuthoritativeLives.DEFAULT_STARTING_LIVES;
    private volatile AuthoritativeLives lives;
    private long publishedLivesRevision;
    private volatile List<MovementEntityDescriptor> livesDescriptors =
            new ArrayList<MovementEntityDescriptor>();
    private final Map<ParticipantId, RevivalAnchor> revivalAnchors =
            new LinkedHashMap<ParticipantId, RevivalAnchor>();
    /** Combat health seen by latest Host tick; read under Host lock where combat state is off limits. */
    private final Map<ParticipantId, Integer> tickHealths =
            new LinkedHashMap<ParticipantId, Integer>();
    private volatile boolean partyWiped;
    private final Map<ParticipantId, DeathDrop> deathDrops =
            new LinkedHashMap<ParticipantId, DeathDrop>();
    /** Exhausted Participants' scattered items and the unpaused Host tick that removes them. */
    private final Map<Long, Integer> scatterOwners = new LinkedHashMap<>();
    private final Map<Long, Long> expiringDrops = new LinkedHashMap<Long, Long>();
    private final Random deathDropRandom = new Random();
    private long exhaustedDropTicks = DeathDropRules.EXHAUSTED_DROP_TICKS;
    private volatile PartyCommunicationState partyCommunication =
            PartyCommunicationState.initial();
    private final AuthoritativeItemWorld itemWorld = new AuthoritativeItemWorld();
    private final com.interrupt.dungeoneer.multiplayer.economy.AuthoritativeEconomy economy =
            new com.interrupt.dungeoneer.multiplayer.economy.AuthoritativeEconomy();
    private final Map<ParticipantId, Long> publishedProgressRevisions = new LinkedHashMap<ParticipantId, Long>();
    private final Map<String, Long> publishedShopRevisions = new LinkedHashMap<String, Long>();
    private long publishedShopGeneration = -1L;
    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> shopOpenings =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening>();
    private final List<ItemRequest> pendingItemRequests = new ArrayList<ItemRequest>();
    private final Map<Long, Long> publishedItemRevisions = new LinkedHashMap<Long, Long>();
    private final Map<String, ActorEffectsSnapshot> monsterEffects = new LinkedHashMap<String, ActorEffectsSnapshot>();
    private boolean effectsBoundLogged;
    private final Map<String, Long> effectPublishTicks = new LinkedHashMap<String, Long>();
    private final Map<String, ActorEffectsSnapshot> publishedMonsterEffects = new LinkedHashMap<String, ActorEffectsSnapshot>();
    private long effectSequence;
    private final Map<Long, NativeDynamicState> nativeDynamicStates = new LinkedHashMap<Long, NativeDynamicState>();
    private final Map<Long, NativeDynamicState> publishedNativeDynamicStates = new LinkedHashMap<Long, NativeDynamicState>();
    private final Map<Long, Long> nativeDynamicPublishTicks = new LinkedHashMap<Long, Long>();
    private long nativeDynamicSequence;

    private final Map<Long, DoorSnapshot> doorSnapshots = new LinkedHashMap<Long, DoorSnapshot>();
    private final Map<Long, com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot> moverSnapshots =
            new LinkedHashMap<Long, com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot>();
    private final Map<Long, BreakableSnapshot> breakableSnapshots =
            new LinkedHashMap<Long, BreakableSnapshot>();
    private long nextLifecycleSequence;
    private volatile com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot lobbySnapshot;
    private long nextLobbySequence;
    private boolean hostPlayerReady;
    private final Map<Integer, CampaignSlot> publishedLobbySlots = new LinkedHashMap<>();
    private long nextPartyStatusSequence;
    private long nextPartyChatSequence;
    private long nextPauseRequestSequence;
    private long nextPauseSessionSequence;

    private DirectConnectHost(DirectConnectCompatibility compatibility, CampaignRoster roster,
            CampaignRosterStore rosterStore, MovementCollisionWorld movementWorld,
            long reconnectGraceTicks) {
        if(compatibility == null) throw new IllegalArgumentException("Host compatibility cannot be null.");
        if(roster == null) throw new IllegalArgumentException("Campaign Roster cannot be null.");
        if(rosterStore == null) throw new IllegalArgumentException("Campaign Roster store cannot be null.");
        if(movementWorld == null) throw new IllegalArgumentException("Movement collision world cannot be null.");
        if(reconnectGraceTicks < 1L) {
            throw new IllegalArgumentException("Reconnect grace must contain at least one Host tick.");
        }
        this.compatibility = compatibility;
        this.roster = roster;
        startingLives = roster.getStartingLives();
        this.rosterStore = rosterStore;
        campaignSaveStore = rosterStore.campaignSaves();
        if(campaignSaveStore.isTerminal(roster.getCampaignId())) {
            throw new IllegalStateException("Campaign Archive is read-only and cannot resume active play.");
        }
        if(campaignSaveStore.needsRecovery(roster.getCampaignId())) {
            throw new IllegalStateException("Unclean Host shutdown: recover Campaign in Campaign Library first.");
        }
        CampaignSave saved = campaignSaveStore.exists(roster.getCampaignId())
                ? campaignSaveStore.load(roster.getCampaignId(), compatibility) : null;
        if(saved != null) {
            saved.verifyRoster(roster);
            if(saved.getOutcome() != CampaignSave.Outcome.ACTIVE) {
                throw new IllegalStateException("Campaign " + roster.getCampaignId()
                        + " is " + saved.getOutcome().name().toLowerCase()
                        + " and cannot resume active play.");
            }
        }
        durableCampaign = saved;
        resumedCampaign = saved != null;
        restoredNativeFloor = saved == null ? null : saved.getNativeFloor();
        resumeNativeWorldPending = saved != null;
        savedFloorFingerprint = saved == null ? null : saved.getFloorFingerprint();
        sharedFloorSeed = saved == null ? nextNonZeroLong(random) : saved.getFloorSeed();
        if(saved != null) {
            startingLives = saved.getStartingLives();
            scatterOwners.putAll(saved.getScatterOwners());
            nativeWorldGeneration = saved.getNativeWorldGeneration();
            ownedFloorId = compatibility.usesOpenSourceTestContent()
                    ? null : saved.getFloorId();
            itemWorld.restore(saved.getPhysicalItems());
            itemWorld.restorePartyKeys(saved.getPartyKeys(), saved.getKeyRevision());
            partyProgression = saved.getPartyProgression();
            for(DoorSnapshot door : saved.getDoors()) doorSnapshots.put(door.entityId, door);
            for(BreakableSnapshot breakable : saved.getBreakables()) {
                breakableSnapshots.put(breakable.entityId, breakable);
            }
            for(ActorEffectsSnapshot effects : saved.getActorEffects()) {
                monsterEffects.put(effects.monsterId, effects);
                effectSequence = Math.max(effectSequence, effects.sequence);
            }
            // Rebuilt floor lacks late Monsters: Host recreates them, clients get them replayed.
            nativeMonsterSpawns.addAll(saved.getMonsterSpawns());
            potionMapping = saved.getPotionMapping();
            restoredMonsterSpawns.addAll(saved.getMonsterSpawns());
            consumedMonsterSpawners.addAll(saved.getConsumedMonsterSpawners());
            for(CampaignSave.ParticipantState participant : saved.getParticipants()) {
                if(participant.getMovement() == null && participant.getParty().getRemainingLives() == 0) {
                    LauncherIdentity identity = roster.getSlot(participant.getCampaignSlot()).getLauncherIdentity();
                    lateParticipants.add(identity);
                    lateEntryGenerations.put(identity, nativeWorldGeneration);
                }
                personalKnowledge.put(new ParticipantId("campaign-slot-" + participant.getCampaignSlot()),
                        participant.getPersonalKnowledge());
                if(participant.getProgress() != null) {
                    economy.registerParticipant(participant.getProgress());
                }
            }
            // Roster reservation may survive interruption before next world checkpoint.
            for(CampaignSlot slot : roster.getSlots()) {
                if(saved.getParticipant(slot.getNumber()) != null) continue;
                lateParticipants.add(slot.getLauncherIdentity());
                lateEntryGenerations.put(slot.getLauncherIdentity(), nativeWorldGeneration);
            }
        }
        activeAreaKey = saved == null ? sharedFloorId() : saved.getActiveAreaKey();
        activeFloorId = saved == null ? null : saved.getFloorId();
        if(saved != null) {
            for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor : saved.getDormantFloors())
                dormantFloors.put(floor.getAreaKey(), floor);
            itemWorld.reserveEntityIds(nextCampaignItemId(saved.getPhysicalItems(), saved.getDormantFloors()));
            if(saved.hasNativeFloor()) {
                com.interrupt.dungeoneer.game.Level nativeLevel = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(saved.getNativeFloor());
                if(nativeLevel.multiplayerNativeRecipe != null) partyDestination = new com.interrupt.dungeoneer.multiplayer.floor.PartyDestination(
                        nativeWorldGeneration, activeAreaKey, activeFloorId, sharedFloorSeed,
                        nativeLevel.multiplayerNativeRecipe, nativeLevel.multiplayerArrival);
            }
        }
        // Native resume collision must use played tiles, including terrain changed by triggers.
        this.movementWorld = saved != null && saved.hasNativeFloor()
                && movementWorld instanceof LevelMovementCollisionWorld
                ? new LevelMovementCollisionWorld(com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave
                        .restore(saved.getNativeFloor())) : movementWorld;
        this.reconnectGraceTicks = reconnectGraceTicks;
        sessionId = newSessionId(random);
        status = status(DirectConnectPhase.STARTING,
                "Opening authoritative Campaign lobby listeners.", null, null);
    }

    public static DirectConnectHost start(int port, DirectConnectCompatibility compatibility,
            CampaignRoster roster, CampaignRosterStore rosterStore) {
        return start(port, compatibility, roster, rosterStore,
                new RectangularMovementCollisionWorld(32f, 32f, 16.5f, 16.5f, 0.5f));
    }

    public static DirectConnectHost start(int port, DirectConnectCompatibility compatibility,
            CampaignRoster roster, CampaignRosterStore rosterStore,
            MovementCollisionWorld movementWorld) {
        if(port < 0 || port > 65535) {
            throw new IllegalArgumentException("Host port must be between 0 and 65535.");
        }
        return start(port, compatibility, roster, rosterStore, movementWorld,
                RECONNECT_GRACE_TICKS);
    }

    static DirectConnectHost startForTest(int port, DirectConnectCompatibility compatibility,
            CampaignRoster roster, CampaignRosterStore rosterStore,
            MovementCollisionWorld movementWorld, long reconnectGraceTicks) {
        return start(port, compatibility, roster, rosterStore, movementWorld,
                reconnectGraceTicks);
    }

    private static DirectConnectHost start(int port, DirectConnectCompatibility compatibility,
            CampaignRoster roster, CampaignRosterStore rosterStore,
            MovementCollisionWorld movementWorld, long reconnectGraceTicks) {
        if(port < 0 || port > 65535) {
            throw new IllegalArgumentException("Host port must be between 0 and 65535.");
        }
        DirectConnectHost host = new DirectConnectHost(compatibility, roster, rosterStore,
                movementWorld, reconnectGraceTicks);
        try {
            host.bind(port);
            return host;
        }
        catch(RuntimeException ex) {
            host.closeResources();
            throw ex;
        }
    }

    private void bind(int requestedPort) {
        ServerBootstrap tcp = new ServerBootstrap();
        tcp.group(acceptGroup, networkGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        DirectConnectWire.configureTcp(channel.pipeline());
                        channel.pipeline().addLast("directConnectHost", new HostTcpHandler());
                    }
                });

        Bootstrap udp = new Bootstrap();
        udp.group(networkGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext context,
                            DatagramPacket packet) {
                        handleUdp(packet);
                    }
                });

        if(requestedPort == 0) {
            Throwable lastFailure = null;
            for(int attempt = 0; attempt < 16; attempt++) {
                ChannelFuture tcpBind = tcp.bind(new InetSocketAddress(0)).awaitUninterruptibly();
                requireSuccess(tcpBind, "Could not bind Direct Connect TCP listener");
                Channel candidateTcp = tcpBind.channel();
                int candidatePort = ((InetSocketAddress)candidateTcp.localAddress()).getPort();
                ChannelFuture udpBind = udp.bind(new InetSocketAddress(candidatePort));
                udpBind.awaitUninterruptibly();
                if(udpBind.isSuccess()) {
                    tcpListener = candidateTcp;
                    udpListener = udpBind.channel();
                    boundPort = candidatePort;
                    break;
                }
                lastFailure = udpBind.cause();
                closeChannel(udpBind.channel());
                closeChannel(candidateTcp);
            }
            if(tcpListener == null || udpListener == null) {
                throw new IllegalStateException(
                        "Could not reserve one Direct Connect TCP and UDP port after 16 attempts.",
                        lastFailure);
            }
        }
        else {
            ChannelFuture tcpBind = tcp.bind(new InetSocketAddress(requestedPort)).awaitUninterruptibly();
            requireSuccess(tcpBind, "Could not bind Direct Connect TCP listener");
            tcpListener = tcpBind.channel();
            boundPort = ((InetSocketAddress)tcpListener.localAddress()).getPort();

            ChannelFuture udpBind = udp.bind(new InetSocketAddress(boundPort)).awaitUninterruptibly();
            requireSuccess(udpBind, "Could not bind Direct Connect UDP listener");
            udpListener = udpBind.channel();
        }
        status = status(DirectConnectPhase.LISTENING,
                "Campaign " + roster.getCampaignId() + " has " + roster.getSlots().size()
                        + "/" + roster.getCapacity() + " claimed slots. Waiting on TCP and UDP port "
                        + boundPort + ".", null, null);
        publishLobby();
    }

    /** Refreshes consent invalidated by accepted external roster edits before returning public view. */
    @Override public synchronized com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot getLobbySnapshot() {
        refreshLobbyConsent();
        return lobbySnapshot;
    }

    /** Caller holds Host monitor, including Ready and Start action boundaries. */
    private void refreshLobbyConsent() {
        if(!sessionStarted && !closing.get() && lobbySnapshot != null) {
            for(int number = 1; number <= roster.getCapacity(); number++) {
                if(publishedLobbySlots.get(number) != roster.getSlot(number)) { publishLobby(); break; }
            }
        }
    }

    /** Called under Host monitor; queue every report on TCP loop to retain cross-thread order. */
    private synchronized void publishLobby() {
        if(closing.get()) return;
        List<com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot.Slot> slots = new ArrayList<>();
        for(int number = 1; number <= roster.getCapacity(); number++) {
            CampaignSlot slot = roster.getSlot(number);
            RemoteConnection connection = slot == null ? null : findConnection(slot.getLauncherIdentity());
            CampaignSlot previous = publishedLobbySlots.put(number, slot);
            if(previous != null && previous != slot) {
                if(number == 1) hostPlayerReady = false;
                else if(connection != null) {
                    connection.playerReady = false;
                    connection.minimumReadySequence = nextLobbySequence + 1L;
                }
            }
            boolean connected = number == 1 || connection != null && connection.slot != null && !connection.kicked;
            boolean authenticated = number == 1 || connected && connection.udpAddress != null;
            boolean synchronizedState = number == 1 || authenticated && connection.lobbySynchronized;
            slots.add(new com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot.Slot(number,
                    slot == null ? null : slot.getPresentation(), connected, authenticated, synchronizedState,
                    synchronizedState && (number == 1 ? hostPlayerReady : connection.playerReady)));
        }
        lobbySnapshot = new com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot(++nextLobbySequence,
                roster.getCampaignName(), roster.getCapacity(), startingLives, 1, slots);
        DirectConnectWire.LobbySnapshotMessage message = new DirectConnectWire.LobbySnapshotMessage(sessionId, lobbySnapshot);
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot == null || connection.kicked || !connection.channel.isActive()) continue;
            connection.lastLobbySequence = nextLobbySequence;
            if(connection.udpAddress != null && connection.lobbyAuthenticationSequence == 0L)
                connection.lobbyAuthenticationSequence = nextLobbySequence;
            connection.channel.eventLoop().execute(() -> {
                if(connection.channel.isActive()) connection.channel.writeAndFlush(message);
            });
        }
    }

    private synchronized void lobbyReceived(ChannelHandlerContext context, DirectConnectWire.LobbyReceived receipt) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null || connection.kicked || connection.slot == null || connection.udpAddress == null
                || !sessionId.equals(receipt.sessionId) || connection.lobbyAuthenticationSequence == 0L
                || receipt.sequence < connection.lobbyAuthenticationSequence
                || receipt.sequence > connection.lastLobbySequence || connection.lobbySynchronized) return;
        connection.lobbySynchronized = true;
        publishLobby();
    }

    private static void requireSuccess(ChannelFuture future, String message) {
        if(future.isSuccess()) return;
        Throwable cause = future.cause();
        throw new IllegalStateException(message + (cause == null ? "." : ": "
                + safeMessage(cause)), cause);
    }

    private synchronized boolean acceptHello(ChannelHandlerContext context, ClientHello hello) {
        if(hello.protocolVersion != DirectConnectProtocol.VERSION) {
            reject(context, RejectCode.PROTOCOL_MISMATCH,
                    "Protocol mismatch: Host requires " + DirectConnectProtocol.VERSION
                            + " but client sent " + hello.protocolVersion + ".");
            return false;
        }

        DirectConnectCompatibility clientCompatibility;
        LauncherIdentity launcherIdentity;
        try {
            clientCompatibility = new DirectConnectCompatibility(hello.buildId,
                    hello.contentFormat, hello.contentSha256);
            launcherIdentity = new LauncherIdentity(hello.launcherIdentity);
        }
        catch(IllegalArgumentException ex) {
            reject(context, RejectCode.MALFORMED_HANDSHAKE,
                    "Malformed handshake: " + safeMessage(ex));
            return false;
        }

        if(!compatibility.getBuildId().equals(clientCompatibility.getBuildId())) {
            reject(context, RejectCode.BUILD_MISMATCH,
                    "Build mismatch: Host requires " + compatibility.getBuildId()
                            + " but client sent " + clientCompatibility.getBuildId() + ".");
            return false;
        }
        if(!compatibility.getContentIdentity().equals(clientCompatibility.getContentIdentity())) {
            reject(context, RejectCode.CONTENT_MISMATCH,
                    "Content mismatch: normalized content identity differs from Host. "
                            + "Launch both Host and Client with the same -PownedCopy archive; "
                            + "do not mix owned and open-source test content.");
            return false;
        }
        if(findConnection(launcherIdentity) != null) {
            reject(context, RejectCode.IDENTITY_IN_USE,
                    "Launcher Identity is already connected to this Private Session.");
            return false;
        }
        GraceParticipant reconnecting = reconnectingParticipants.get(launcherIdentity);
        ReturnedParticipant returned = returnedParticipants.get(launcherIdentity);
        // Cold subset resume has no expired live connection, but retains same private slot contract.
        if(sessionStarted && reconnecting == null && returned == null && durableCampaign != null
                && !lateParticipants.contains(launcherIdentity)) {
            CampaignSlot savedSlot = roster.findSlot(launcherIdentity);
            if(savedSlot != null && savedSlot.getNumber() != 1
                    && durableCampaign.getParticipant(savedSlot.getNumber()) != null) {
                MovementEntityDescriptor savedDescriptor = new MovementEntityDescriptor(
                        ++nextLifecycleSequence, entityId(savedSlot), participantId(savedSlot),
                        savedSlot.getNumber(), savedSlot.getPresentation().getNickname(),
                        savedSlot.getPresentation().getAvatarId());
                returned = new ReturnedParticipant(savedSlot, savedDescriptor, true);
                returnedParticipants.put(launcherIdentity, returned);
            }
        }
        if(sessionStarted && (partyWiped || partyProgression.victory)) {
            reject(context, RejectCode.NOT_IN_LOBBY,
                    "Terminal Campaign cannot admit a Participant.");
            return false;
        }
        if(!sessionStarted && connections.size() >= roster.getCapacity() - 1) {
            reject(context, RejectCode.SESSION_FULL,
                    "Session lobby already has its maximum remote connections.");
            return false;
        }

        RemoteConnection connection = new RemoteConnection(context.channel(),
                (InetSocketAddress)context.channel().remoteAddress(), launcherIdentity);
        connection.reconnectGrace = reconnecting;
        connection.returnedParticipant = returned;
        connection.lateParticipant = sessionStarted && reconnecting == null && returned == null;
        connections.put(context.channel(), connection);
        context.writeAndFlush(new CampaignChallenge(sessionId, roster.getCampaignId(),
                roster.getCapacity()));
        if(!sessionStarted) status = status(DirectConnectPhase.HANDSHAKING,
                "Compatible Launcher Identity " + launcherIdentity.getFingerprint()
                        + " is selecting its Campaign Slot.",
                launcherIdentity.getFingerprint(), null);
        return true;
    }

    private synchronized void claimSlot(ChannelHandlerContext context, SlotClaim claim) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null || connection.kicked || !sessionId.equals(claim.sessionId)) {
            reject(context, RejectCode.MALFORMED_HANDSHAKE,
                    "Malformed handshake: Campaign Slot claim is outside current lobby state.");
            return;
        }

        SlotClaimRequest request;
        try {
            request = new SlotClaimRequest(connection.launcherIdentity,
                    new SlotPresentation(claim.nickname, claim.avatarId), claim.requestedSlot,
                    claim.reconnectToken);
        }
        catch(IllegalArgumentException ex) {
            reject(context, RejectCode.MALFORMED_HANDSHAKE,
                    "Malformed Campaign Slot claim: " + safeMessage(ex));
            return;
        }
        if(connection.request != null) {
            SlotClaimRequest previous = connection.request;
            if(previous.getRequestedSlot() != request.getRequestedSlot()
                    || !previous.getPresentation().getNickname().equals(request.getPresentation().getNickname())
                    || !previous.getPresentation().getAvatarId().equals(request.getPresentation().getAvatarId())
                    || !java.util.Objects.equals(previous.getReconnectToken(), request.getReconnectToken())) {
                reject(context, RejectCode.MALFORMED_HANDSHAKE,
                        "Campaign Slot claim changed during admission.");
            }
            else if(connection.slot != null) {
                context.writeAndFlush(new ServerAccepted(sessionId, connection.udpToken,
                        roster.getCampaignId(), connection.slot.getNumber(), connection.slot.getReconnectToken()));
            }
            else {
                context.writeAndFlush(new SlotPending(sessionId, "Campaign Slot awaits Host approval."));
            }
            return;
        }
        connection.request = request;

        ClaimOutcome outcome;
        if(connection.lateParticipant) {
            CampaignSlot existing = roster.findSlot(connection.launcherIdentity);
            if(existing != null && !lateParticipants.contains(connection.launcherIdentity)) {
                reject(context, RejectCode.RECONNECT_DENIED,
                        "Existing Campaign Slot must use its reconnect contract.");
                return;
            }
            int preference = request.getRequestedSlot();
            if(existing == null && (preference > roster.getCapacity()
                    || preference != 0 && roster.getSlot(preference) != null)) preference = 0;
            SlotClaimRequest lateClaim = new SlotClaimRequest(connection.launcherIdentity,
                    request.getPresentation(), preference, request.getReconnectToken());
            outcome = existing == null ? roster.approve(lateClaim, random) : roster.submit(lateClaim);
        }
        else if(sessionStarted) {
            GraceParticipant reconnecting = connection.reconnectGrace;
            ReturnedParticipant returned = connection.returnedParticipant;
            CampaignSlot reconnectSlot = reconnecting == null
                    ? returned == null ? null : returned.slot : reconnecting.slot;
            if(reconnectSlot == null || request.getRequestedSlot() != 0
                    && request.getRequestedSlot() != reconnectSlot.getNumber()) {
                reject(context, RejectCode.NOT_IN_LOBBY,
                        "Active Floor reconnect must claim its original Campaign Slot.");
                return;
            }
            SlotClaimRequest reclaim = new SlotClaimRequest(connection.launcherIdentity,
                    reconnectSlot.getPresentation(), reconnectSlot.getNumber(),
                    request.getReconnectToken());
            outcome = roster.submit(reclaim);
        }
        else {
            CampaignSlot existing = roster.findSlot(connection.launcherIdentity);
            // Setup preferences are provisional; authenticated return keeps campaign presentation.
            SlotClaimRequest pregame = existing == null ? request : new SlotClaimRequest(
                    connection.launcherIdentity, existing.getPresentation(), request.getRequestedSlot(),
                    request.getReconnectToken());
            outcome = roster.approve(pregame, random);
        }
        if(outcome.getStatus() == ClaimStatus.ADMITTED) {
            if((!sessionStarted || connection.lateParticipant)
                    && !persistRoster(context.channel())) return;
            if(connection.lateParticipant) lateParticipants.add(connection.launcherIdentity);
            admit(connection, outcome.getSlot());
        }
        else if(outcome.getStatus() == ClaimStatus.NEEDS_APPROVAL
                || outcome.getStatus() == ClaimStatus.RELINK_REQUIRED) {
            context.writeAndFlush(new SlotPending(sessionId, outcome.getReason()));
            status = status(DirectConnectPhase.AWAITING_APPROVAL,
                    request.getPresentation().getNickname() + " (Identity "
                            + request.getLauncherIdentity().getFingerprint()
                            + ") awaits Host approval.",
                    request.getLauncherIdentity().getFingerprint(), null);
        }
        else {
            rejectClaim(context.channel(), outcome);
        }
    }

    public synchronized boolean approve(String launcherIdentity) {
        if(sessionStarted) return false;
        RemoteConnection connection = findPending(launcherIdentity);
        if(connection == null) return false;
        ClaimOutcome outcome = roster.approve(connection.request, random);
        if(outcome.getStatus() != ClaimStatus.ADMITTED) {
            rejectClaim(connection.channel, outcome);
            return false;
        }
        if(!persistRoster(connection.channel)) return false;
        admit(connection, outcome.getSlot());
        return true;
    }

    /**
     * Host-only recovery action. Replaces a trusted Participant's lost private identity,
     * rotates its reconnect credential, and admits only that pending lobby connection.
     */
    public synchronized boolean relinkTrustedParticipant(String launcherIdentity) {
        if(sessionStarted) return false;
        RemoteConnection connection = findPending(launcherIdentity);
        if(connection == null || connection.request.getRequestedSlot() < 2) return false;
        ClaimOutcome outcome = roster.relink(connection.request, random);
        if(outcome.getStatus() != ClaimStatus.ADMITTED) {
            rejectClaim(connection.channel, outcome);
            return false;
        }
        if(!persistRoster(connection.channel)) return false;
        admit(connection, outcome.getSlot());
        return true;
    }

    public synchronized boolean decline(String launcherIdentity) {
        RemoteConnection connection = findPending(launcherIdentity);
        if(connection == null) return false;
        reject(connection.channel, RejectCode.APPROVAL_DECLINED,
                "Host declined this new Campaign Slot claim.");
        status = status(DirectConnectPhase.LISTENING,
                "Host declined " + connection.request.getPresentation().getNickname()
                        + "; Campaign Slot ownership was unchanged.",
                connection.launcherIdentity.getFingerprint(), null);
        return true;
    }

    /** Removes current connection without deleting persistent Campaign Slot or progression. */
    public synchronized boolean kick(String launcherIdentity) {
        if(launcherIdentity == null) return false;
        RemoteConnection connection = findConnectionValue(launcherIdentity);
        if(connection == null) return false;
        removeConnection(connection,
                "Host removed connection. Campaign Slot and progression remain safe.",
                "Host kicked a Participant; persistent Campaign Slot was preserved.");
        return true;
    }

    private void removeConnection(RemoteConnection connection, String participantReason,
            String hostMessage) {
        connection.kicked = true;
        if(sessionStarted && connection.movementDescriptor != null) {
            returnParticipantToCampaignSlot(connection.movementDescriptor,
                    "Host removed " + connection.movementDescriptor.getNickname() + " from Active Floor.");
        }
        connection.channel.writeAndFlush(new ServerDisconnect(boundedReason(participantReason)))
                .addListener(ChannelFutureListener.CLOSE);
        status = status(sessionStarted ? DirectConnectPhase.READY : DirectConnectPhase.LISTENING,
                boundedReason(hostMessage), connection.launcherIdentity.getFingerprint(),
                sessionStarted ? sharedFloorId() : null);
    }

    @Override public long getSharedFloorSeed() { return sharedFloorSeed; }

    /** Host's own floor build; verifies clients that reported before Host finished building. */
    @Override public synchronized void recordSharedFloorFingerprint(SharedFloorFingerprint fingerprint) {
        if(fingerprint == null || hostFloorFingerprint != null) return;
        if(savedFloorFingerprint != null && !savedFloorFingerprint.equals(fingerprint)) {
            failNativePresentation("Saved Campaign floor differs from rebuilt Host floor: "
                    + savedFloorFingerprint.describeDifferences(fingerprint));
            return;
        }
        hostFloorFingerprint = fingerprint;
        persistCampaign();
        for(RemoteConnection connection : new ArrayList<RemoteConnection>(connections.values())) {
            verifySharedFloor(connection);
        }
    }

    private synchronized void sharedFloorReported(ChannelHandlerContext context,
            DirectConnectWire.SharedFloorFingerprintMessage message) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null || connection.slot == null || connection.udpAddress == null
                || !sessionId.equals(message.sessionId) || connection.reconnectGrace != null) return;
        connection.floorFingerprint = message.fingerprint;
        verifySharedFloor(connection);
    }

    /** A client whose native floor differs would see and hit objects Host does not have. */
    private void verifySharedFloor(RemoteConnection connection) {
        SharedFloorFingerprint local = connection.floorFingerprint;
        if(hostFloorFingerprint == null || local == null || connection.kicked
                || connection.slot == null) return;
        if(hostFloorFingerprint.equals(local)) return;
        String differences = hostFloorFingerprint.describeDifferences(local);
        String nickname = connection.slot.getPresentation().getNickname();
        if(com.badlogic.gdx.Gdx.app != null) com.badlogic.gdx.Gdx.app.error("DelverMultiplayer",
                "Shared floor of " + nickname
                + " differs from Host (Host/client): " + differences);
        removeConnection(connection,
                "Shared floor build differs from Host (Host/you: " + differences
                        + "). Session stopped so worlds cannot diverge; Campaign Slot is safe.",
                "Removed " + nickname + ": shared floor build differs (Host/client: "
                        + differences + ").");
    }

    public synchronized List<PendingSlotClaim> getPendingClaims() {
        List<PendingSlotClaim> pending = new ArrayList<PendingSlotClaim>();
        for(RemoteConnection connection : connections.values()) {
            if(connection.request != null && connection.slot == null) {
                pending.add(new PendingSlotClaim(connection.request));
            }
        }
        return Collections.unmodifiableList(pending);
    }

    public synchronized int getConnectedParticipantCount() {
        int connected = 1;
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot != null && connection.channel.isActive()) connected++;
        }
        return connected;
    }

    public synchronized int getUdpReadyParticipantCount() {
        int ready = 1;
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot != null && connection.udpAddress != null
                    && connection.channel.isActive()) ready++;
        }
        return ready;
    }

    @Override public synchronized boolean canSetPlayerReady() {
        return !sessionStarted && !closing.get() && lobbySnapshot != null
                && status.getPhase() != DirectConnectPhase.FAILED;
    }

    @Override public synchronized void setPlayerReady(boolean ready) {
        if(!canSetPlayerReady()) throw new IllegalStateException("Player Ready requires an open pregame lobby.");
        refreshLobbyConsent();
        if(hostPlayerReady == ready) return;
        hostPlayerReady = ready;
        publishLobby();
    }

    private synchronized void playerReady(ChannelHandlerContext context, DirectConnectWire.PlayerReady request) {
        refreshLobbyConsent();
        RemoteConnection connection = connections.get(context.channel());
        if(sessionStarted || closing.get() || connection == null || connection.kicked || connection.slot == null
                || !connection.channel.isActive() || connection.udpAddress == null || !connection.lobbySynchronized
                || !sessionId.equals(request.sessionId) || request.slot != connection.slot.getNumber()
                || request.connectionToken != connection.udpToken
                || request.sequence < connection.minimumReadySequence
                || request.sequence < connection.lobbyAuthenticationSequence || request.sequence > connection.lastLobbySequence) return;
        if(connection.playerReady == request.ready) return;
        connection.playerReady = request.ready;
        publishLobby();
    }

    public synchronized boolean canStartSession() {
        refreshLobbyConsent();
        if(sessionStarted || closing.get() || status.getPhase() == DirectConnectPhase.FAILED || !hostPlayerReady) return false;
        int connected = 1;
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot == null || connection.kicked || !connection.channel.isActive()) continue;
            connected++;
            if(connection.udpAddress == null || !connection.lobbySynchronized || !connection.playerReady) return false;
        }
        return connected >= 2 || durableCampaign != null;
    }

    /** True when this Active Floor was rebuilt from a durable Campaign Save. */
    public boolean isResumedCampaign() { return resumedCampaign; }

    public synchronized void startSession() {
        if(sessionStarted) return;
        if(!canStartSession())
            throw new IllegalStateException("Every connected Participant must finish synchronization and become Ready before Host starts.");
        int participantCount = getUdpReadyParticipantCount();
        if(participantCount < 2 && durableCampaign == null) {
            throw new IllegalStateException("At least one approved remote Participant must finish TCP and UDP lobby setup.");
        }

        for(RemoteConnection connection : new ArrayList<RemoteConnection>(connections.values())) {
            if(connection.slot == null || connection.udpAddress == null) {
                reject(connection.channel, RejectCode.NOT_IN_LOBBY,
                        "Host started play before this connection completed approval and UDP lobby setup.");
            }
        }
        List<MovementEntityDescriptor> descriptors = createMovementDescriptors();
        movementSimulation = new AuthoritativeMovementSimulation(movementWorld, descriptors);
        combatEncounter = new AuthoritativeCombatEncounter(descriptors, movementWorld);
        List<ParticipantId> livesParticipants = new ArrayList<ParticipantId>();
        for(MovementEntityDescriptor descriptor : descriptors) {
            livesParticipants.add(descriptor.getParticipantId());
        }
        lives = new AuthoritativeLives(startingLives, livesParticipants);
        restoreParticipants(descriptors);
        if(durableCampaign != null) for(Map.Entry<Long, Long> timer : durableCampaign.getDropTimers().entrySet())
            expiringDrops.put(timer.getKey(), currentHostTick() + timer.getValue());
        publishedLivesRevision = lives.getRevision();
        livesDescriptors = descriptors;
        for(MovementEntityDescriptor descriptor : descriptors) {
            MovementEntityState state = movementSimulation.getState(descriptor.getParticipantId());
            if(state != null) {
                combatEncounter.updateParticipantPosition(descriptor.getParticipantId(),
                        state.getX(), state.getY());
            }
        }
        MovementSpawn encounterSpawn = findEncounterSpawn();
        combatEncounter.setMonsterPosition(encounterSpawn.getX(), encounterSpawn.getY(),
                encounterSpawn.getZ());
        movementSession = new AuthoritativeHostSession(new AuthoritativeEncounterSimulation(
                movementSimulation, combatEncounter, descriptors),
                new MovementTransport(), new HostSessionStorage() {
                    @Override
                    public void persist(long hostTick, HostPersistedState state) { }
                });
        campaignSaveStore.beginSession(roster.getCampaignId());
        sessionStarted = true;
        recoveryShutdownHook = new Thread(() -> {
            // Native graphs cannot be touched from JVM shutdown thread. Reuse last detached capture.
            synchronized(DirectConnectHost.this) {
                if(!closing.get() && lastCapturedCampaign != null
                        && !campaignSaveStore.isTerminal(roster.getCampaignId())) {
                    try { campaignSaveStore.snapshot(lastCapturedCampaign); }
                    catch(RuntimeException ignored) { /* Existing valid files and dirty marker survive. */ }
                }
            }
        }, "delver-campaign-recovery");
        Runtime.getRuntime().addShutdownHook(recoveryShutdownHook);

        for(MovementEntityDescriptor descriptor : descriptors) {
            movementReplication.applySpawn(descriptor);
            if(descriptor.getCampaignSlot() == 1) {
                localMovementEntityId = descriptor.getEntityId();
            }
        }
        partyStatus = createPartyStatus(descriptors);
        publishedCombatSnapshot.set(combatEncounter.getSnapshot(currentHostTick()));
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot != null && connection.udpAddress != null
                    && connection.channel.isActive()) {
                for(MovementEntityDescriptor descriptor : descriptors) {
                    if(descriptor.getCampaignSlot() == connection.slot.getNumber()) {
                        connection.movementDescriptor = descriptor;
                    }
                }
                if(connection.movementDescriptor != null) {
                    sendCurrentSessionState(connection, connection.movementDescriptor,
                            connection.movementDescriptor.getNickname() + " entered the Active Floor.");
                }
            }
        }
        startMovementTicks();
        status = status(DirectConnectPhase.READY,
                "Host started " + sharedFloorName() + " with " + participantCount
                        + " Participants.", null, sharedFloorId());
        for(MovementEntityDescriptor descriptor : descriptors) {
            if(descriptor.getCampaignSlot() != 1) {
                publishPartyNotice(descriptor, descriptor.getNickname() + " joined", null);
            }
        }
        if(durableCampaign == null) persistCampaign();
    }

    private MovementSpawn findEncounterSpawn() {
        MovementSpawn playerSpawn = movementWorld.getSpawn(1);
        float rotation = playerSpawn.getRotation();
        float forwardX = (float)Math.sin(rotation);
        float forwardY = (float)Math.cos(rotation);
        float rightX = (float)Math.cos(rotation);
        float rightY = (float)-Math.sin(rotation);
        float[][] directions = {
                { forwardX, forwardY },
                { rightX, rightY },
                { -rightX, -rightY },
                { -forwardX, -forwardY }
        };
        float[] distances = { 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f, 2.5f, 3f };
        for(float distance : distances) {
            for(float[] direction : directions) {
                float x = playerSpawn.getX() + direction[0] * distance;
                float y = playerSpawn.getY() + direction[1] * distance;
                float z = movementWorld.getFloorZ(x, y, playerSpawn.getZ());
                if(movementWorld.canOccupy(x, y, z)) {
                    return new MovementSpawn(x, y, z, rotation);
                }
            }
        }
        throw new IllegalStateException("Shared floor has no valid encounter spawn.");
    }

    private List<MovementEntityDescriptor> createMovementDescriptors() {
        List<CampaignSlot> activeSlots = new ArrayList<CampaignSlot>();
        CampaignSlot hostSlot = roster.getSlot(1);
        if(hostSlot == null) throw new IllegalStateException("Host Campaign Slot is missing.");
        activeSlots.add(hostSlot);
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot != null && connection.udpAddress != null
                    && connection.channel.isActive()) activeSlots.add(connection.slot);
        }
        Collections.sort(activeSlots, new java.util.Comparator<CampaignSlot>() {
            @Override
            public int compare(CampaignSlot first, CampaignSlot second) {
                return first.getNumber() - second.getNumber();
            }
        });

        List<MovementEntityDescriptor> descriptors =
                new ArrayList<MovementEntityDescriptor>();
        for(CampaignSlot slot : activeSlots) {
            if(lateParticipants.contains(slot.getLauncherIdentity())) continue;
            long lifecycle = ++nextLifecycleSequence;
            CampaignSave.ParticipantState saved = durableCampaign == null ? null
                    : durableCampaign.getParticipant(slot.getNumber());
            if(saved != null && saved.getMovement() != null) {
                lifecycle = saved.getMovement().getLifecycleSequence();
                nextLifecycleSequence = Math.max(nextLifecycleSequence, lifecycle);
            }
            descriptors.add(new MovementEntityDescriptor(lifecycle,
                    entityId(slot), participantId(slot), slot.getNumber(),
                    slot.getPresentation().getNickname(),
                    slot.getPresentation().getAvatarId()));
        }
        return descriptors;
    }

    private void restoreParticipants(List<MovementEntityDescriptor> descriptors) {
        CampaignSave saved = durableCampaign;
        if(saved == null) return;
        if(saved.getCombat() != null) combatEncounter.restoreSnapshot(saved.getCombat());
        for(MovementEntityDescriptor descriptor : descriptors) {
            CampaignSave.ParticipantState participant =
                    saved.getParticipant(descriptor.getCampaignSlot());
            if(participant == null) {
                throw new IllegalStateException("Campaign Save is missing active Campaign Slot "
                        + descriptor.getCampaignSlot() + ".");
            }
            if(participant.getMovement() != null) {
                movementSimulation.restoreState(descriptor.getParticipantId(),
                        participant.getMovement());
            }
            PartyMemberStatus party = participant.getParty();
            AuthoritativeLives.Condition condition = AuthoritativeLives.Condition.STANDING;
            // A Spectator who had left before the save is recorded as disconnected; its Lives
            // still say it has none, and it returns as a Spectator.
            if(party.getRemainingLives() == 0) {
                condition = AuthoritativeLives.Condition.EXHAUSTED;
            }
            else if(party.getState() == PartyMemberState.DOWNED) {
                condition = AuthoritativeLives.Condition.DOWNED;
            }
            CampaignSlot reviverSlot = party.getReviverSlot() == 0 ? null
                    : roster.getSlot(party.getReviverSlot());
            ParticipantId reviver = reviverSlot == null ? null : participantId(reviverSlot);
            boolean reviverActive = false;
            if(reviver != null) {
                for(MovementEntityDescriptor candidate : descriptors) {
                    if(reviver.equals(candidate.getParticipantId())) reviverActive = true;
                }
            }
            int revivalTicks = reviverActive ? party.getRevivalTicks() : 0;
            if(!reviverActive) reviver = null;
            boolean downed = condition == AuthoritativeLives.Condition.DOWNED;
            if(!downed) {
                revivalTicks = 0;
                reviver = null;
            }
            lives.restore(descriptor.getParticipantId(), party.getRemainingLives(), condition,
                    downed ? party.getBleedoutTicks() : 0, revivalTicks, reviver,
                    Math.max(0, startingLives - party.getRemainingLives()));
        }
    }

    private PartyStatusSnapshot createPartyStatus(
            List<MovementEntityDescriptor> descriptors) {
        List<PartyMemberStatus> members = new ArrayList<PartyMemberStatus>();
        for(CampaignSlot slot : roster.getSlots()) {
            MovementEntityDescriptor descriptor = null;
            for(MovementEntityDescriptor candidate : descriptors) {
                if(candidate.getCampaignSlot() == slot.getNumber()) {
                    descriptor = candidate;
                    break;
                }
            }
            CampaignSave.ParticipantState saved = durableCampaign == null ? null
                    : durableCampaign.getParticipant(slot.getNumber());
            if(saved == null) {
                members.add(lateParticipants.contains(slot.getLauncherIdentity())
                        ? new PartyMemberStatus(slot.getNumber(), null, slot.getPresentation().getNickname(),
                                slot.getPresentation().getAvatarId(), 0, PartyMemberStatus.DEFAULT_HEALTH, 0,
                                PartyMemberState.DISCONNECTED)
                        : PartyMemberStatus.initial(slot, descriptor, startingLives));
                continue;
            }
            PartyMemberStatus previous = saved.getParty();
            members.add(new PartyMemberStatus(slot.getNumber(),
                    descriptor == null ? null : descriptor.getEntityId(),
                    slot.getPresentation().getNickname(), slot.getPresentation().getAvatarId(),
                    previous.getHealth(), previous.getMaximumHealth(),
                    previous.getRemainingLives(), descriptor == null
                            ? PartyMemberState.DISCONNECTED : PartyMemberState.CONNECTED));
        }
        return new PartyStatusSnapshot(++nextPartyStatusSequence, projectLives(members));
    }

    private void startMovementTicks() {
        long tickNanos = TimeUnit.SECONDS.toNanos(1L)
                / AuthoritativeHostSession.TICKS_PER_SECOND;
        movementTask = networkGroup.next().scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    AuthoritativeHostSession session = movementSession;
                    if(session != null && sessionStarted && !closing.get()) {
                        updatePendingAdmissions(System.nanoTime());
                        if(session.advanceOneTick(() -> !isSessionPaused())) {
                            advanceLives(session.getHostTick());
                            expireReconnectGrace(session.getHostTick());
                            advanceAdmissions(session.getHostTick());
                            advancePartyTravel();
                        }
                    }
                }
                catch(RuntimeException failure) {
                    status = status(DirectConnectPhase.FAILED,
                            "Authoritative movement tick failed: " + safeMessage(failure),
                            null, sharedFloorId());
                    throw failure;
                }
            }
        }, tickNanos, tickNanos, TimeUnit.NANOSECONDS);
    }

    private synchronized void partyChat(ChannelHandlerContext context,
            PartyChatSubmit submitted) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null || !sessionId.equals(submitted.sessionId)
                || !sessionStarted || connection.slot == null) {
            reject(context, RejectCode.MALFORMED_HANDSHAKE, "Party chat is outside current session.");
            return;
        }
        if(connection.movementDescriptor == null && !connection.spectatorReady) return;
        publishPartyChat(connection.slot.getNumber(), connection.slot.getPresentation().getNickname(), submitted.text);
    }

    private synchronized void pauseRequested(ChannelHandlerContext context,
            PauseRequestMessage submitted) {
        RemoteConnection connection = activeConnection(context, submitted.sessionId,
                "Pause request");
        if(connection == null) return;
        publishPauseRequest(connection.movementDescriptor);
    }

    private synchronized void itemAction(ChannelHandlerContext context,
            DirectConnectWire.ItemRequestMessage message) {
        RemoteConnection connection = activeConnection(context, message.sessionId, "Item action");
        if(connection == null || connection.reconnectGrace != null) return;
        if(message.worldGeneration != nativeWorldGeneration) {
            publishItemActionResult(connection.movementDescriptor.getParticipantId(),
                    new ItemActionResult(message.requestId, message.entityId, false));
            return;
        }
        if(isSessionPaused()) {
            publishItemActionResult(connection.movementDescriptor.getParticipantId(),
                    new ItemActionResult(message.requestId, message.entityId, false)); return;
        }
        enqueueItemRequest(new ItemRequest(connection.movementDescriptor.getParticipantId(),
                message.requestId, message.worldGeneration, message.action, message.entityId,
                message.condition, message.quantity,
                message.hasAim, message.aimX, message.aimY, message.aimZ));
    }

    private void enqueueItemRequest(ItemRequest request) {
        if(pendingItemRequests.size() < 256) pendingItemRequests.add(request);
        else publishItemActionResult(request.getParticipantId(), new ItemActionResult(
                request.requestId, request.entityId, false));
    }

    /** Render-thread bridge drains bounded intents; it alone touches native floor objects. */
    public synchronized List<ItemRequest> drainItemRequests() {
        List<ItemRequest> requests = new ArrayList<ItemRequest>(pendingItemRequests);
        pendingItemRequests.clear();
        return requests;
    }

    public AuthoritativeItemWorld getItemWorld() { return itemWorld; }

    @Override
    public List<PhysicalItemState> getPhysicalItems() { return itemWorld.snapshot(); }

    @Override
    public long getNextItemRequestId() {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        return descriptor == null ? 1L : itemWorld.nextRequestId(descriptor.getParticipantId());
    }

    @Override
    public synchronized void submitItemAction(long requestId, ItemAction action, long entityId) {
        submitItemAction(requestId, action, entityId, 0, 0);
    }

    @Override
    public synchronized void submitItemAction(long requestId, ItemAction action, long entityId,
            int condition, int quantity) {
        submitItemAction(requestId, action, entityId, condition, quantity,
                false, 0f, 0f, 0f);
    }

    @Override
    public synchronized void submitItemAction(long requestId, ItemAction action, long entityId,
            int condition, int quantity, boolean hasAim, float aimX, float aimY, float aimZ) {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        if(descriptor == null || !sessionStarted) return;
        if(isSessionPaused()) {
            publishItemActionResult(descriptor.getParticipantId(), new ItemActionResult(requestId, entityId, false)); return;
        }
        enqueueItemRequest(new ItemRequest(descriptor.getParticipantId(), requestId,
                nativeWorldGeneration, action, entityId, condition, quantity,
                hasAim, aimX, aimY, aimZ));
    }

    @Override
    public synchronized List<DoorSnapshot> getDoorSnapshots() {
        return new ArrayList<DoorSnapshot>(doorSnapshots.values());
    }

    @Override
    public synchronized List<BreakableSnapshot> getBreakableSnapshots() {
        return new ArrayList<BreakableSnapshot>(breakableSnapshots.values());
    }

    public void setWorldObstacles(java.util.List<MovementObstacle> obstacles) {
        if(movementWorld instanceof LevelMovementCollisionWorld) {
            ((LevelMovementCollisionWorld)movementWorld)
                    .setWorldObstacles(obstacles);
        }
    }

    public void setActorObstacles(java.util.List<MovementObstacle> monsters) {
        if(movementWorld instanceof LevelMovementCollisionWorld) {
            ((LevelMovementCollisionWorld)movementWorld).setActorObstacles(monsters);
        }
    }

    /** Host movement copies water, pits and friction from the initialized live Active Floor. */
    public void adoptNativeTileRules(com.interrupt.dungeoneer.game.Level live) {
        if(!(movementWorld instanceof LevelMovementCollisionWorld)) return;
        try { ((LevelMovementCollisionWorld)movementWorld).adoptTileRules(live); }
        catch(IllegalArgumentException mismatch) {
            if(com.badlogic.gdx.Gdx.app != null) com.badlogic.gdx.Gdx.app.error("DelverMultiplayer",
                    mismatch.getMessage());
        }
    }

    @Override public synchronized void failNativePresentation(String reason) {
        String diagnostic = boundedReason("Native presentation compatibility failure: " + reason);
        setSessionPaused(true);
        broadcast(new ServerDisconnect(diagnostic));
        status = status(DirectConnectPhase.FAILED, diagnostic, null, null);
    }

    private long nativeWorldGeneration = 1;
    @Override public synchronized long getNativeWorldGeneration() { return nativeWorldGeneration; }
    @Override public synchronized void beginNativeWorld() {
        nativeWorldStable = false;
        if(resumeNativeWorldPending) {
            resumeNativeWorldPending = false;
            return;
        }
        nativeWorldGeneration++;
        for(RemoteConnection connection : connections.values()) {
            if(!connection.lateParticipant || connection.spectatorReady) continue;
            connection.admissionId = 0L;
            connection.admissionStage = 0;
            connection.admissionChanges.clear();
            connection.channel.writeAndFlush(new DirectConnectWire.NativeWorldGenerationMessage(sessionId, nativeWorldGeneration));
        }
        monsterEffects.clear(); publishedMonsterEffects.clear(); effectPublishTicks.clear();
        nativeDynamicStates.clear(); publishedNativeDynamicStates.clear(); nativeDynamicPublishTicks.clear();
        nativeMonsterSpawns.clear();
        nativeDecals.clear();
        restoredMonsterSpawns.clear();
        consumedMonsterSpawners.clear();
        doorSnapshots.clear();
        moverSnapshots.clear();
        breakableSnapshots.clear();
        broadcast(new DirectConnectWire.NativeWorldGenerationMessage(sessionId, nativeWorldGeneration));
        if(movementSimulation != null && movementSession != null) {
            MovementSnapshot baseline = (MovementSnapshot)movementSimulation.snapshot(
                    Math.max(1L, movementSession.getHostTick()));
            movementReplication.applyFloorSnapshot(baseline);
            broadcast(new MovementSnapshotMessage(sessionId, baseline, nativeWorldGeneration));
        }
    }

    private long nativeMonsterSpawnSequence;
    private final List<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> nativeMonsterSpawns =
            new ArrayList<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>();
    @Override public synchronized void retireNativeDynamicState(long id) {
        NativeDynamicState last = nativeDynamicStates.get(id);
        if(last != null) synchronizeNativeDynamicState(last.inactive());
    }

    @Override public void retireNativeMonster(String monsterId) {
        if(monsterId == null) return;
        // Never hold the encounter and Host monitors together.
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) encounter.retireMonster(monsterId);
        synchronized(this) {
            for(java.util.Iterator<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> spawns =
                    nativeMonsterSpawns.iterator(); spawns.hasNext();) {
                if(monsterId.equals(spawns.next().monsterId)) spawns.remove();
            }
            monsterEffects.remove(monsterId);
            publishedMonsterEffects.remove(monsterId);
            effectPublishTicks.remove(monsterId);
        }
    }

    @Override public synchronized void publishNativeMonsterSpawn(
            com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn spawn) {
        if(spawn == null) throw new IllegalArgumentException("Native monster spawn is required.");
        if(nativeMonsterSpawns.size() >= DirectConnectProtocol.MAX_MONSTERS) return;
        nativeMonsterSpawns.add(spawn);
        broadcast(new DirectConnectWire.NativeMonsterSpawnMessage(sessionId,
                ++nativeMonsterSpawnSequence, spawn, nativeWorldGeneration));
    }

    public static final int MAX_NATIVE_DECALS = 512;
    /** Marks on the current floor, newest last, replayed to clients that join later. */
    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState> nativeDecals =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState>();

    @Override public synchronized void recordNativeDecal(
            com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState decal, boolean announce) {
        if(decal == null) throw new IllegalArgumentException("Native decal is required.");
        if(nativeDecals.size() >= MAX_NATIVE_DECALS) nativeDecals.removeFirst();
        nativeDecals.addLast(decal);
        if(announce) broadcast(new DirectConnectWire.NativeDecalMessage(sessionId,
                nativeWorldGeneration, decal));
    }

    private long triggerPresentationSequence;

    /** Shared native positional sound; full state baselines never replay this one-shot cue. */
    public synchronized void publishTriggerSound(long objectId) {
        broadcast(new DirectConnectWire.TriggerPresentationMessage(sessionId,
                new com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation(objectId, "",
                        ++triggerPresentationSequence, nativeWorldGeneration, true)));
    }

    /** Screen-only part of a trigger a client used, shown on that client's screen alone. */
    public synchronized void deliverTriggerPresentation(ParticipantId participant, long objectId,
            String value) {
        com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation presentation;
        try {
            presentation = new com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation(
                    objectId, value, ++triggerPresentationSequence, nativeWorldGeneration, false);
        }
        catch(IllegalArgumentException invalid) {
            // Presentation only: the chain already ran on Host, so a bad value is not fatal.
            if(com.badlogic.gdx.Gdx.app != null) com.badlogic.gdx.Gdx.app.error("DelverMultiplayer",
                    "Trigger presentation dropped: " + invalid.getMessage());
            return;
        }
        for(RemoteConnection connection : connections.values()) {
            if(connection.movementDescriptor != null && connection.reconnectGrace == null
                    && connection.channel.isActive()
                    && connection.movementDescriptor.getParticipantId().equals(participant)) {
                connection.channel.writeAndFlush(
                        new DirectConnectWire.TriggerPresentationMessage(sessionId, presentation));
                return;
            }
        }
    }

    /** Saved late Monsters of the resumed world; cleared once play leaves that world. */
    private final List<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> restoredMonsterSpawns =
            new ArrayList<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>();
    private final Set<String> consumedMonsterSpawners = new LinkedHashSet<String>();

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>
            getRestoredNativeMonsterSpawns() {
        return new ArrayList<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>(
                restoredMonsterSpawns);
    }

    @Override public synchronized boolean isNativeMonsterSpawnerConsumed(String spawnerKey) {
        return consumedMonsterSpawners.contains(spawnerKey);
    }

    @Override public synchronized void consumeNativeMonsterSpawner(String spawnerKey) {
        if(spawnerKey == null || spawnerKey.isEmpty()) return;
        if(consumedMonsterSpawners.size() >= CampaignSave.MAX_CONSUMED_MONSTER_SPAWNERS) return;
        consumedMonsterSpawners.add(spawnerKey);
    }

    /**
     * Dev menu: stand one Campaign Slot back up (optionally with one more Life) at its Respawn
     * Point with 50 percent health. Never holds the Host monitor together with the encounter.
     */
    public boolean devRespawn(ParticipantId participant, boolean grantLife) {
        boolean restore;
        synchronized(this) {
            AuthoritativeLives current = lives;
            AuthoritativeMovementSimulation simulation = movementSimulation;
            MovementEntityDescriptor descriptor = livesDescriptor(participant);
            if(current == null || simulation == null || descriptor == null || partyWiped) return false;
            restore = current.devStand(participant, grantLife);
            if(restore) {
                revivalAnchors.remove(participant);
                deathDrops.remove(participant);
                MovementSpawn respawnPoint = movementWorld.getSpawn(descriptor.getCampaignSlot());
                simulation.setNativePosition(participant, respawnPoint.getX(),
                        respawnPoint.getY(), respawnPoint.getZ());
                if(!isInReconnectGrace(descriptor.getCampaignSlot())) simulation.resumeParticipant(participant);
            }
        }
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(restore && encounter != null) {
            encounter.restoreParticipant(currentHostTick(), participant,
                    AuthoritativeLives.RESPAWN_HEALTH_PERCENT, nativeCombatOutput);
        }
        synchronized(this) { publishLivesIfChanged(); }
        return true;
    }

    /** Dev menu: gold for one Campaign Slot through the #17 ledger so every peer converges. */
    public synchronized boolean devGrantGold(ParticipantId participant, int amount) {
        if(participant == null || amount < 1) return false;
        Map<ParticipantId, Integer> shares = economy.divideGold(participant, amount,
                Collections.singletonList(participant));
        if(shares == null) return false;
        publishEconomy();
        return true;
    }

    private long nativeAnimationCueSequence;
    @Override public synchronized void publishNativeAnimationCue(com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationCue cue) {
        broadcast(new DirectConnectWire.NativeAnimationCueMessage(sessionId, ++nativeAnimationCueSequence, cue, nativeWorldGeneration));
    }

    private long nativeExplosionSequence;
    @Override public synchronized void publishNativeExplosion(NativeExplosionPresentation presentation) {
        broadcast(new DirectConnectWire.NativeExplosionMessage(sessionId, ++nativeExplosionSequence, presentation, nativeWorldGeneration));
    }

    private long nativeDynamicCueSequence;
    @Override public synchronized void publishNativeDynamicCue(NativeDynamicCue cue) {
        if(cue == null) throw new IllegalArgumentException("Native dynamic cue is required.");
        broadcast(new DirectConnectWire.NativeDynamicCueMessage(sessionId,
                ++nativeDynamicCueSequence, cue, nativeWorldGeneration));
    }

    private long nativeSpellPresentationSequence;
    @Override public synchronized void publishNativeSpellPresentation(
            NativeSpellPresentation presentation) {
        if(presentation == null) {
            throw new IllegalArgumentException("Native spell presentation is required.");
        }
        broadcast(new DirectConnectWire.NativeSpellPresentationMessage(sessionId,
                ++nativeSpellPresentationSequence, presentation, nativeWorldGeneration));
    }

    private long nativeMeleePresentationSequence;
    @Override public synchronized void publishNativeMeleePresentation(
            NativeMeleePresentation presentation) {
        if(presentation == null) {
            throw new IllegalArgumentException("Native melee presentation is required.");
        }
        broadcast(new DirectConnectWire.NativeMeleePresentationMessage(sessionId,
                ++nativeMeleePresentationSequence, presentation, nativeWorldGeneration));
    }

    private long nativeRangedPresentationSequence;
    @Override public synchronized void publishNativeRangedPresentation(
            NativeRangedPresentation presentation) {
        if(presentation == null) {
            throw new IllegalArgumentException("Native ranged presentation is required.");
        }
        broadcast(new DirectConnectWire.NativeRangedPresentationMessage(sessionId,
                ++nativeRangedPresentationSequence, presentation, nativeWorldGeneration));
    }

    @Override public synchronized List<NativeDynamicState> getNativeDynamicStates() {
        return new ArrayList<NativeDynamicState>(nativeDynamicStates.values());
    }

    @Override public synchronized void synchronizeNativeDynamicState(NativeDynamicState state) {
        if(state == null) throw new IllegalArgumentException("Native dynamic state is required.");
        if(state.active) {
            if(!nativeDynamicStates.containsKey(state.id)
                    && nativeDynamicStates.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES)
                throw new IllegalArgumentException("Native dynamic entity count exceeded.");
            nativeDynamicStates.put(state.id, state);
        }
        else nativeDynamicStates.remove(state.id);
        NativeDynamicState published = publishedNativeDynamicStates.get(state.id);
        if(state.sameState(published)) return;
        long tick = currentHostTick();
        Long last = nativeDynamicPublishTicks.get(state.id);
        if(state.active && published != null && last != null && tick <= last) return;
        publishNativeDynamicState(state, tick);
    }

    @Override public synchronized void applyNativeParticipantImpulse(ParticipantId participant,
            float x, float y, float z) {
        if(movementSimulation != null) movementSimulation.applyNativeImpulse(participant, x, y, z);
    }

    @Override public void setNativeParticipantWalkSpeed(ParticipantId participant, float walkSpeed) {
        AuthoritativeMovementSimulation simulation = movementSimulation;
        if(simulation != null) simulation.setNativeWalkSpeed(participant, walkSpeed);
    }

    @Override public synchronized void setNativeParticipantPosition(ParticipantId participant,
            float x, float y, float z) {
        if(movementSimulation != null) movementSimulation.setNativePosition(participant, x, y, z);
    }

    @Override public void setNativeParticipantMaximumHealth(ParticipantId participant,
            int maximumHealth, boolean restoreFull) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) encounter.setParticipantMaximumHealth(currentHostTick(), participant,
                maximumHealth, restoreFull, nativeCombatOutput);
    }

    public com.interrupt.dungeoneer.multiplayer.economy.AuthoritativeEconomy getEconomy() { return economy; }

    @Override public List<com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress> getParticipantProgress() {
        return economy.progressSnapshot();
    }

    @Override public List<com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState> getShopEntries() {
        return economy.shopSnapshot();
    }

    /** Render thread publishes changed personal progress and shop entries once per revision. */
    public synchronized void publishEconomy() {
        for(com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress progress : economy.progressSnapshot()) {
            Long previous = publishedProgressRevisions.get(progress.participantId);
            if(previous != null && previous == progress.revision) continue;
            publishedProgressRevisions.put(progress.participantId, progress.revision);
            broadcast(new DirectConnectWire.ParticipantProgressMessage(sessionId, progress));
        }
        if(publishedShopGeneration != economy.getGeneration()) {
            publishedShopGeneration = economy.getGeneration();
            publishedShopRevisions.clear();
        }
        for(com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState entry : economy.shopSnapshot()) {
            String key = entry.shopId + ":" + entry.entryId;
            Long previous = publishedShopRevisions.get(key);
            if(previous != null && previous == entry.revision) continue;
            publishedShopRevisions.put(key, entry.revision);
            broadcast(new DirectConnectWire.ShopEntryStateMessage(sessionId, entry));
        }
    }

    /** Dialogue and shop overlay are activator-scoped; other Participants receive no opening. */
    public synchronized void publishShopOpening(ParticipantId participant,
            com.interrupt.dungeoneer.multiplayer.economy.ShopOpening opening) {
        MovementEntityDescriptor local = hostMovementDescriptor();
        if(local != null && local.getParticipantId().equals(participant)) {
            if(shopOpenings.size() >= 16) shopOpenings.removeFirst();
            shopOpenings.addLast(opening);
            return;
        }
        for(RemoteConnection connection : connections.values()) {
            if(connection.movementDescriptor != null && connection.reconnectGrace == null
                    && connection.channel.isActive()
                    && connection.movementDescriptor.getParticipantId().equals(participant)) {
                connection.channel.writeAndFlush(new DirectConnectWire.ShopOpeningMessage(sessionId, opening));
                return;
            }
        }
    }

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> drainShopOpenings() {
        List<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> result =
                new ArrayList<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening>(shopOpenings);
        shopOpenings.clear();
        return result;
    }

    private void publishNativeDynamicState(NativeDynamicState state, long tick) {
        nativeDynamicPublishTicks.put(state.id, tick);
        if(state.active) publishedNativeDynamicStates.put(state.id, state);
        else {
            publishedNativeDynamicStates.remove(state.id);
            nativeDynamicPublishTicks.remove(state.id);
        }
        broadcast(new DirectConnectWire.NativeDynamicStateMessage(sessionId,
                ++nativeDynamicSequence, state, nativeWorldGeneration));
    }

    public synchronized void publishItemActionResult(ParticipantId participant, ItemActionResult result) {
        MovementEntityDescriptor local = hostMovementDescriptor();
        if(local != null && local.getParticipantId().equals(participant)) {
            if(itemActionResults.size() >= 128) { failNativePresentation("Item action response queue exceeded."); return; }
            itemActionResults.addLast(result); return;
        }
        for(RemoteConnection connection : connections.values()) {
            if(connection.movementDescriptor != null && connection.reconnectGrace == null
                    && connection.channel.isActive()
                    && connection.movementDescriptor.getParticipantId().equals(participant)) {
                connection.channel.writeAndFlush(new DirectConnectWire.ItemActionResultMessage(sessionId, result)); return;
            }
        }
    }

    public synchronized void publishDoorFeedback(ParticipantId participant, DoorFeedback feedback) {
        MovementEntityDescriptor local = hostMovementDescriptor();
        if(local != null && local.getParticipantId().equals(participant)) {
            queueDoorFeedback(feedback);
            return;
        }
        for(RemoteConnection connection : connections.values()) {
            if(connection.movementDescriptor != null && connection.reconnectGrace == null
                    && connection.channel.isActive()
                    && connection.movementDescriptor.getParticipantId().equals(participant)) {
                connection.channel.writeAndFlush(new DirectConnectWire.DoorFeedbackMessage(sessionId, feedback));
                return;
            }
        }
    }

    public synchronized void publishDoor(DoorSnapshot state) {
        doorSnapshots.put(state.entityId, state);
        broadcast(new DirectConnectWire.DoorStateMessage(sessionId, state));
    }

    public synchronized void publishMover(com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot state) {
        moverSnapshots.put(state.entityId, state);
        broadcast(new DirectConnectWire.MoverStateMessage(sessionId, state));
    }

    @Override
    public synchronized List<com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot> getMoverSnapshots() {
        return new ArrayList<com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot>(moverSnapshots.values());
    }

    public synchronized void publishBreakable(BreakableSnapshot state) {
        breakableSnapshots.put(state.entityId, state);
        broadcast(new DirectConnectWire.BreakableStateMessage(sessionId, state));
    }

    private long publishedKeyRevision = -1L;

    private final java.util.ArrayDeque<DoorFeedback> doorFeedback = new java.util.ArrayDeque<DoorFeedback>();
    @Override public synchronized List<DoorFeedback> drainDoorFeedback() {
        List<DoorFeedback> result = new ArrayList<DoorFeedback>(doorFeedback);
        doorFeedback.clear();
        return result;
    }
    private void queueDoorFeedback(DoorFeedback feedback) {
        if(doorFeedback.size() == 64) doorFeedback.removeFirst();
        doorFeedback.addLast(feedback);
    }

    private final java.util.ArrayDeque<ItemActionResult> itemActionResults = new java.util.ArrayDeque<ItemActionResult>();
    @Override public synchronized List<ItemActionResult> drainItemActionResults() {
        List<ItemActionResult> results = new ArrayList<ItemActionResult>(itemActionResults);
        itemActionResults.clear(); return results;
    }

    @Override public synchronized List<ActorEffectsSnapshot> getActorEffects() {
        return new ArrayList<ActorEffectsSnapshot>(monsterEffects.values());
    }

    @Override public synchronized void synchronizeNativeActorEffects(ActorEffectsSnapshot state) {
        if(state.monsterId.startsWith("participant:") && movementSimulation != null) {
            float speed = 1f;
            for(NativeStatusEffectState effect : state.effects) speed *= effect.speed;
            movementSimulation.setNativeSpeedModifier(new ParticipantId(
                    state.monsterId.substring("participant:".length())), speed);
            movementSimulation.setNativeTimeScale(new ParticipantId(
                    state.monsterId.substring("participant:".length())), state.actorTimeScale);
            movementSimulation.setNativeFlight(new ParticipantId(
                    state.monsterId.substring("participant:".length())), state.floating, state.flightSpeed);
        }
        ActorEffectsSnapshot previous = monsterEffects.get(state.monsterId);
        if(previous == null && monsterEffects.size() >= DirectConnectProtocol.MAX_MONSTERS + 4) {
            // Presentation only: clients miss this actor's status visuals, play goes on.
            if(!effectsBoundLogged && com.badlogic.gdx.Gdx.app != null) {
                com.badlogic.gdx.Gdx.app.error("DelverMultiplayer", "Native actor effects table is full; "
                        + state.monsterId + " is not replicated.");
            }
            effectsBoundLogged = true;
            return;
        }
        ActorEffectsSnapshot accepted = state.sameState(previous) ? previous
                : state.withSequence(++effectSequence);
        monsterEffects.put(state.monsterId, accepted);
        ActorEffectsSnapshot published = publishedMonsterEffects.get(state.monsterId);
        if(accepted.sameState(published)) return;
        long tick = currentHostTick();
        Long last = effectPublishTicks.get(state.monsterId);
        // Coalesce only decreasing timers. Refresh/configuration changes are immediately visible.
        boolean countdownOnly = published != null && published.invisible == accepted.invisible
                && published.floating == accepted.floating && published.flightSpeed == accepted.flightSpeed
                && published.actorTimeScale == accepted.actorTimeScale
                && published.worldTimeScale == accepted.worldTimeScale
                && published.effects.size() == accepted.effects.size()
                && (published.animation == null ? accepted.animation == null
                        : accepted.animation != null && published.animation.kind == accepted.animation.kind
                        && published.animation.instanceId == accepted.animation.instanceId
                        && published.animation.playing == accepted.animation.playing);
        if(countdownOnly) {
            for(int i = 0; i < accepted.effects.size(); i++) {
                NativeStatusEffectState a = accepted.effects.get(i), b = published.effects.get(i);
                if(a.instanceId != b.instanceId || a.kind != b.kind || a.remaining > b.remaining
                        || a.pulses != b.pulses || a.speed != b.speed
                        || a.particles != b.particles || !a.shader.equals(b.shader)) {
                    countdownOnly = false; break;
                }
            }
        }
        if(!countdownOnly || last == null || tick - last >= 3) publishMonsterEffects(accepted, tick);
    }

    private void publishMonsterEffects(ActorEffectsSnapshot state, long tick) {
        effectPublishTicks.put(state.monsterId, tick);
        publishedMonsterEffects.put(state.monsterId, state);
        broadcast(new DirectConnectWire.MonsterEffectsMessage(sessionId, state, true, nativeWorldGeneration));
    }

    @Override public int getPartyKeys() { return itemWorld.getPartyKeys(); }

    public synchronized com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot getPartyProgression() {
        return partyProgression;
    }

    /** Render thread hands over detached native facts; repeated frames do not advance revision. */
    @Override public synchronized com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge getPersonalKnowledge() {
        return getPersonalKnowledge(new ParticipantId("campaign-slot-" + localMovementEntityId.getValue()));
    }

    public synchronized com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge getPersonalKnowledge(ParticipantId participant) {
        com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge value = personalKnowledge.get(participant);
        return value == null ? com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge.empty() : value;
    }

    private com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping potionMapping = com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping.empty();

    @Override public synchronized com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping getPotionMapping() { return potionMapping; }

    @Override public synchronized void publishPotionMapping(com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping next) {
        if(!next.effects.entrySet().containsAll(potionMapping.effects.entrySet())) throw new IllegalArgumentException("Campaign potion effects cannot change.");
        if(next.effects.equals(potionMapping.effects)) return;
        potionMapping = next;
        broadcast(new DirectConnectWire.PotionMappingMessage(sessionId, next));
    }

    public synchronized void publishPersonalKnowledge(ParticipantId participant, com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge next) {
        if(next.revision <= getPersonalKnowledge(participant).revision) return;
        personalKnowledge.put(participant, next);
        for(RemoteConnection connection : connections.values()) {
            if(connection.movementDescriptor != null && participant.equals(connection.movementDescriptor.getParticipantId()))
                connection.channel.writeAndFlush(new DirectConnectWire.PersonalKnowledgeMessage(sessionId,
                        participant, nativeWorldGeneration, next));
        }
    }

    public synchronized void publishPartyProgression(com.interrupt.dungeoneer.game.Progression nativeState) {
        com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot next =
                com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(
                        nativeState, partyProgression.revision + 1L);
        if(!next.sameFacts(partyProgression)) {
            partyProgression = next;
            broadcast(new DirectConnectWire.PartyProgressionMessage(sessionId, next));
        }
    }

    public synchronized void publishPhysicalItems() {
        long keyRevision = itemWorld.getKeyRevision();
        if(publishedKeyRevision != keyRevision) {
            publishedKeyRevision = keyRevision;
            broadcast(new DirectConnectWire.PartyKeysMessage(sessionId, keyRevision, itemWorld.getPartyKeys()));
        }
        for(PhysicalItemState item : itemWorld.snapshot()) {
            Long previous = publishedItemRevisions.get(item.entityId);
            if(previous != null && previous == item.revision) continue;
            publishedItemRevisions.put(item.entityId, item.revision);
            broadcast(new DirectConnectWire.ItemStateMessage(sessionId, item));
        }
    }

    private synchronized void combatAction(ChannelHandlerContext context,
            CombatActionRequestMessage request) {
        if(request.generation != nativeWorldGeneration) return;
        RemoteConnection connection = activeConnection(context, request.sessionId, "Combat action");
        if(connection == null || isSessionPaused()) return;
        AuthoritativeHostSession session = movementSession;
        if(session != null) {
            CombatRequest command = request.directed
                    ? new CombatRequest(connection.movementDescriptor.getParticipantId(),
                            request.requestId, request.action,
                            request.aimX, request.aimY, request.aimZ,
                            request.attackPower, request.weaponEntityId)
                    : new CombatRequest(connection.movementDescriptor.getParticipantId(),
                            request.requestId, request.action, request.targetId);
            submitSessionCommand(session, command);
        }
    }

    private RemoteConnection activeConnection(ChannelHandlerContext context, String messageSessionId,
            String action) {
        RemoteConnection connection = connections.get(context.channel());
        if(sessionStarted && connection != null && connection.lateParticipant
                && connection.movementDescriptor == null && sessionId.equals(messageSessionId)) return null;
        if(!sessionStarted || connection == null || connection.movementDescriptor == null
                || !sessionId.equals(messageSessionId)) {
            reject(context, RejectCode.MALFORMED_HANDSHAKE,
                    action + " is outside current Active Floor session.");
            return null;
        }
        return connection;
    }

    private void publishPartyChat(MovementEntityDescriptor descriptor, String text) {
        if(descriptor == null || !sessionStarted) return;
        publishPartyChat(descriptor.getCampaignSlot(), descriptor.getNickname(), text);
    }

    private void publishPartyChat(int slot, String nickname, String text) {
        PartyChatMessage chat = new PartyChatMessage(++nextPartyChatSequence, slot, nickname, text);
        partyCommunication = partyCommunication.withChat(chat);
        broadcast(new PartyChatDelivery(sessionId, chat));
    }

    private void publishPartyNotice(MovementEntityDescriptor descriptor, String text,
            RemoteConnection excluded) {
        if(descriptor == null || !sessionStarted) return;
        PartyChatMessage notice = PartyChatMessage.system(++nextPartyChatSequence,
                descriptor.getCampaignSlot(), descriptor.getNickname(), text);
        partyCommunication = partyCommunication.withChat(notice);
        PartyChatDelivery delivery = new PartyChatDelivery(sessionId, notice);
        for(RemoteConnection connection : connections.values()) {
            if(connection != excluded && connection.channel.isActive()
                    && connection.movementDescriptor != null) {
                connection.channel.writeAndFlush(delivery);
            }
        }
    }

    private void publishPauseRequest(MovementEntityDescriptor descriptor) {
        if(descriptor == null || !sessionStarted) return;
        PauseRequest request = new PauseRequest(++nextPauseRequestSequence,
                descriptor.getCampaignSlot(), descriptor.getNickname());
        partyCommunication = partyCommunication.withPauseRequest(request);
        broadcast(new PauseRequestedMessage(sessionId, request));
    }

    private void broadcast(Message message) {
        for(RemoteConnection connection : connections.values()) deliverCurrentState(connection, message);
    }

    /** Baseline mutations are bounded and ordered; after catch-up fence TCP remains live. */
    private void deliverCurrentState(RemoteConnection connection, Message message) {
        if(!connection.channel.isActive()) return;
        if(connection.movementDescriptor != null || connection.spectatorReady) {
            enqueueCurrentState(connection, message);
            return;
        }
        if(!connection.lateParticipant || connection.admissionId == 0L) return;
        if(message instanceof CombatPresentationMessage
                || message instanceof DirectConnectWire.NativeAnimationCueMessage
                || message instanceof DirectConnectWire.NativeExplosionMessage
                || message instanceof DirectConnectWire.NativeDynamicCueMessage
                || message instanceof DirectConnectWire.NativeSpellPresentationMessage
                || message instanceof DirectConnectWire.NativeMeleePresentationMessage
                || message instanceof DirectConnectWire.NativeRangedPresentationMessage
                || message instanceof DirectConnectWire.TriggerPresentationMessage) return;
        if(message instanceof DirectConnectWire.MonsterEffectsMessage) {
            DirectConnectWire.MonsterEffectsMessage effects = (DirectConnectWire.MonsterEffectsMessage)message;
            message = new DirectConnectWire.MonsterEffectsMessage(sessionId, effects.state, false, effects.generation);
        }
        if(message instanceof PartyStatusMessage) {
            message = new PartyStatusMessage(sessionId, withLateSpectator(connection,
                    ((PartyStatusMessage)message).snapshot));
        }
        if(connection.admissionStage == DirectConnectWire.AdmissionSync.BASELINE_END
                || connection.admissionStage == DirectConnectWire.AdmissionSync.ACK_BASELINE) {
            if(connection.admissionChanges.size() >= MAX_ADMISSION_CHANGES) {
                rejectPendingAdmission(connection, RejectCode.ADMISSION_TIMEOUT,
                        "Admission catch-up exceeded bound; reconnect to retry.");
                return;
            }
            connection.admissionChanges.addLast(message);
        }
        else enqueueCurrentState(connection, message);
    }

    private void enqueueCurrentState(RemoteConnection connection, Message message) {
        // Even on TCP's event loop, queue behind baseline writes submitted by UDP return.
        // An immediate tick write can otherwise overtake its already-queued entity spawns.
        connection.channel.eventLoop().execute(() -> {
            if(connection.channel.isActive()) connection.channel.writeAndFlush(message);
        });
    }

    private void broadcastCombatState(CombatSnapshot snapshot) {
        for(RemoteConnection connection : connections.values()) {
            if(connection.reconnectGrace == null) {
                deliverCurrentState(connection, new CombatStateMessage(sessionId, snapshot));
            }
        }
    }

    private void synchronizePartyHealth(CombatSnapshot combat) {
        PartyStatusSnapshot current = partyStatus;
        if(current == null || combat == null) return;
        List<PartyMemberStatus> updated =
                new ArrayList<PartyMemberStatus>(current.getMembers());
        boolean changed = false;
        for(int index = 0; index < updated.size(); index++) {
            PartyMemberStatus member = updated.get(index);
            CombatantSnapshot authoritative = combat.getCombatant(
                    AuthoritativeCombatEncounter.participantTargetId(
                            new ParticipantId("campaign-slot-"
                                    + member.getCampaignSlot())));
            if(authoritative == null || (member.getHealth() == authoritative.getHealth()
                    && member.getMaximumHealth() == authoritative.getMaximumHealth())) continue;
            updated.set(index, member.withHealth(authoritative.getHealth(),
                    authoritative.getMaximumHealth()));
            changed = true;
        }
        if(!changed) return;
        partyStatus = new PartyStatusSnapshot(++nextPartyStatusSequence, projectLives(updated));
        broadcastPartyStatus();
    }

    private void broadcastPartyStatus() {
        for(RemoteConnection connection : connections.values()) {
            if(connection.reconnectGrace == null && connection.returnedParticipant == null) {
                deliverCurrentState(connection, new PartyStatusMessage(sessionId, partyStatus));
            }
        }
    }

    private void broadcastCombatPresentation(CombatPresentationEvent event) {
        for(RemoteConnection connection : connections.values()) {
            if(connection.channel.isActive() && (connection.movementDescriptor != null
                    || connection.spectatorReady)
                    && connection.reconnectGrace == null
                    && connection.returnedParticipant == null) {
                connection.channel.writeAndFlush(
                        new CombatPresentationMessage(sessionId, event));
            }
        }
    }

    private boolean persistRoster(Channel channel) {
        try {
            rosterStore.save(roster);
            return true;
        }
        catch(RuntimeException ex) {
            status = status(DirectConnectPhase.FAILED,
                    "Host could not persist Campaign Roster; admission was stopped.", null, null);
            reject(channel, RejectCode.MALFORMED_HANDSHAKE,
                    "Host could not persist Campaign Slot safely.");
            return false;
        }
    }

    private void admit(RemoteConnection connection, CampaignSlot slot) {
        connection.slot = slot;
        if(lateParticipants.contains(connection.launcherIdentity)) connection.lateParticipant = true;
        if(connection.lateParticipant) connection.admissionStartedNanos = System.nanoTime();
        if(connection.reconnectGrace != null) {
            connection.movementDescriptor = connection.reconnectGrace.descriptor;
        }
        connection.udpToken = nextNonZeroLong(random);
        connection.channel.writeAndFlush(new ServerAccepted(sessionId, connection.udpToken,
                roster.getCampaignId(), slot.getNumber(), slot.getReconnectToken()));
        DirectConnectStatus previous = status;
        status = status(sessionStarted ? DirectConnectPhase.READY
                        : DirectConnectPhase.REGISTERING_UDP,
                slot.getPresentation().getNickname() + " owns Campaign Slot "
                        + slot.getNumber() + ". Waiting for authenticated UDP registration.",
                sessionStarted && previous != null ? previous.getRemoteParticipantId()
                        : connection.launcherIdentity.getFingerprint(),
                sessionStarted ? sharedFloorId() : null);
        publishLobby();
    }

    private synchronized void handleUdp(DatagramPacket packet) {
        if(closing.get()) return;
        Message decoded;
        try {
            decoded = DirectConnectWire.decodeDatagram(packet.content());
        }
        catch(ProtocolException ignored) {
            return;
        }
        if(decoded instanceof DiscoveryProbe) {
            respondToDiscovery((DiscoveryProbe)decoded, packet.sender());
            return;
        }
        if(decoded instanceof UdpRegister) {
            UdpRegister register = (UdpRegister)decoded;
            if(!sessionId.equals(register.sessionId)) return;

            RemoteConnection connection = findConnection(register.udpToken);
            if(connection == null || !sameAddress(connection.tcpAddress, packet.sender())) return;
            if(sessionStarted && connection.reconnectGrace == null
                    && connection.returnedParticipant == null && !connection.lateParticipant) return;
            boolean firstRegistration = connection.udpAddress == null;
            connection.udpAddress = packet.sender();
            try {
                ByteBuf response = DirectConnectWire.encodeDatagram(udpListener.alloc(),
                        new UdpRegistered(sessionId, connection.udpToken));
                udpListener.writeAndFlush(new DatagramPacket(response, connection.udpAddress));
                if(firstRegistration) publishLobby();
                if(connection.reconnectGrace != null) completeReconnect(connection);
                else if(connection.returnedParticipant != null) {
                    completeReturnedParticipant(connection);
                }
                else if(connection.lateParticipant) { /* Stable Host tick begins admission. */ }
                else {
                    status = status(DirectConnectPhase.LOBBY,
                            "Campaign Slot " + connection.slot.getNumber()
                                    + " is TCP/UDP ready. Host may approve more claims or start play.",
                            connection.launcherIdentity.getFingerprint(), null);
                }
            }
            catch(ProtocolException ex) {
                failConnection(connection, "Could not encode UDP registration response: "
                        + safeMessage(ex));
            }
            return;
        }

        if(decoded instanceof MovementInputs && sessionStarted) {
			if(isSessionPaused()) return;
            MovementInputs inputs = (MovementInputs)decoded;
            if(!sessionId.equals(inputs.sessionId) || inputs.generation != nativeWorldGeneration) return;
            RemoteConnection connection = findConnection(inputs.udpToken);
            if(connection == null || connection.movementDescriptor == null
                    || !packet.sender().equals(connection.udpAddress)) return;
            AuthoritativeHostSession session = movementSession;
            if(session == null) return;
            for(MovementInputFrame input : inputs.inputs) {
                submitSessionCommand(session, new MovementInputCommand(
                        connection.movementDescriptor.getParticipantId(), input));
            }
        }
    }

    private void respondToDiscovery(DiscoveryProbe probe, InetSocketAddress recipient) {
        try {
            DiscoveryAnnouncement announcement = new DiscoveryAnnouncement(probe.nonce,
                    DirectConnectProtocol.VERSION, compatibility.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256(),
                    sessionId, roster.getCampaignId(), boundPort, roster.getCapacity(),
                    roster.getSlots().size(), !sessionStarted);
            ByteBuf response = DirectConnectWire.encodeDatagram(udpListener.alloc(),
                    announcement);
            udpListener.writeAndFlush(new DatagramPacket(response, recipient));
        }
        catch(ProtocolException ignored) {
            // Host metadata is validated at construction; malformed probes get no reply.
        }
    }

    private synchronized void clientDisconnected(ChannelHandlerContext context, String reason) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null) {
            context.close();
            return;
        }
        status = status(sessionStarted ? DirectConnectPhase.READY
                        : DirectConnectPhase.DISCONNECTED,
                "Participant disconnected cleanly: " + boundedReason(reason),
                connection.launcherIdentity.getFingerprint(),
                sessionStarted ? sharedFloorId() : null);
        context.close();
    }

    private synchronized void channelClosed(Channel channel) {
        RemoteConnection connection = connections.remove(channel);
        if(connection == null || closing.get()) return;
        publishLobby();
        if(sessionStarted && connection.lateParticipant && connection.movementDescriptor == null) {
            connection.admissionChanges.clear();
            if(connection.spectatorReady) markPartyMemberDisconnected(connection.slot.getNumber());
            status = status(DirectConnectPhase.READY, "Spectator disconnected; slot retained for retry.",
                    connection.launcherIdentity.getFingerprint(), sharedFloorId());
            return;
        }
        if(sessionStarted && connection.movementDescriptor != null) {
            if(connection.kicked) return;
            GraceParticipant existing = reconnectingParticipants.get(connection.launcherIdentity);
            if(existing == null) beginReconnectGrace(connection);
            return;
        }
        if(status.getPhase() != DirectConnectPhase.DISCONNECTED) {
            status = status(DirectConnectPhase.DISCONNECTED,
                    "Participant TCP connection closed; persistent Campaign Slot was preserved.",
                    connection.launcherIdentity.getFingerprint(), null);
        }
    }

    private void markPartyMemberDisconnected(int campaignSlot) {
        replacePartyMember(campaignSlot, PartyMemberStateChange.DISCONNECTED, null);
    }

    private void markPartyMemberReconnecting(MovementEntityDescriptor descriptor) {
        replacePartyMember(descriptor.getCampaignSlot(), PartyMemberStateChange.RECONNECTING,
                descriptor.getEntityId());
    }

    private void markPartyMemberConnected(MovementEntityDescriptor descriptor) {
        replacePartyMember(descriptor.getCampaignSlot(), PartyMemberStateChange.CONNECTED,
                descriptor.getEntityId());
    }

    private void replacePartyMember(int campaignSlot, PartyMemberStateChange change,
            NetworkEntityId entityId) {
        PartyStatusSnapshot current = partyStatus;
        if(current == null) return;
        List<PartyMemberStatus> updated =
                new ArrayList<PartyMemberStatus>(current.getMembers());
        for(int index = 0; index < updated.size(); index++) {
            PartyMemberStatus member = updated.get(index);
            if(member.getCampaignSlot() == campaignSlot) {
                if(change == PartyMemberStateChange.DISCONNECTED) {
                    updated.set(index, member.disconnected());
                }
                else if(change == PartyMemberStateChange.RECONNECTING) {
                    updated.set(index, member.reconnecting());
                }
                else {
                    updated.set(index, member.connected(entityId));
                }
                break;
            }
        }
        partyStatus = new PartyStatusSnapshot(++nextPartyStatusSequence, projectLives(updated));
        broadcastPartyStatus();
    }

    /** Lives own incapacitation; presence only decides between connected, grace and absent. */
    private List<PartyMemberStatus> projectLives(List<PartyMemberStatus> members) {
        AuthoritativeLives current = lives;
        if(current == null) return members;
        List<PartyMemberStatus> projected = new ArrayList<PartyMemberStatus>(members.size());
        for(PartyMemberStatus member : members) {
            ParticipantId participant = new ParticipantId("campaign-slot-" + member.getCampaignSlot());
            AuthoritativeLives.Condition condition = current.getCondition(participant);
            if(condition == null) {
                projected.add(member);
                continue;
            }
            PartyMemberState state;
            int bleedoutTicks = 0, revivalTicks = 0, reviverSlot = 0;
            if(member.getEntityId() == null) state = PartyMemberState.DISCONNECTED;
            else if(condition == AuthoritativeLives.Condition.DOWNED) {
                state = PartyMemberState.DOWNED;
                bleedoutTicks = current.getBleedoutTicks(participant);
                ParticipantId reviver = current.getReviver(participant);
                if(reviver != null) {
                    revivalTicks = current.getRevivalTicks(participant);
                    reviverSlot = campaignSlot(reviver);
                }
            }
            else if(condition == AuthoritativeLives.Condition.EXHAUSTED) {
                state = PartyMemberState.SPECTATING;
            }
            else state = isInReconnectGrace(member.getCampaignSlot())
                    ? PartyMemberState.RECONNECTING : PartyMemberState.CONNECTED;
            projected.add(member.withIncapacitation(state, current.getRemainingLives(participant),
                    bleedoutTicks, revivalTicks, reviverSlot));
        }
        return projected;
    }

    private int campaignSlot(ParticipantId participant) {
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            if(descriptor.getParticipantId().equals(participant)) return descriptor.getCampaignSlot();
        }
        return 0;
    }

    private boolean isInReconnectGrace(int campaignSlot) {
        for(GraceParticipant grace : reconnectingParticipants.values()) {
            if(grace.descriptor.getCampaignSlot() == campaignSlot) return true;
        }
        return false;
    }

    private void publishLivesIfChanged() {
        AuthoritativeLives current = lives;
        PartyStatusSnapshot published = partyStatus;
        if(current == null || published == null
                || current.getRevision() == publishedLivesRevision) return;
        publishedLivesRevision = current.getRevision();
        partyStatus = new PartyStatusSnapshot(++nextPartyStatusSequence,
                projectLives(published.getMembers()));
        broadcastPartyStatus();
    }

    /**
     * One unpaused Host tick of Downing, Revival interruption, bleedout and respawn.
     * Native combat publishes while holding the encounter lock and then takes the Host lock,
     * whereas reconnect paths hold the Host lock first; this path therefore never holds both.
     */
    private void advanceLives(long hostTick) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter == null) return;
        Map<ParticipantId, Integer> healths = new LinkedHashMap<ParticipantId, Integer>();
        Map<ParticipantId, DeathCause> causes = new LinkedHashMap<ParticipantId, DeathCause>();
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            healths.put(descriptor.getParticipantId(),
                    encounter.getParticipantHealth(descriptor.getParticipantId()));
            causes.put(descriptor.getParticipantId(),
                    encounter.getLastDamageCause(descriptor.getParticipantId()));
        }
        List<ParticipantId> downed = new ArrayList<ParticipantId>();
        Map<ParticipantId, Integer> restored = new LinkedHashMap<ParticipantId, Integer>();
        List<LifeLoss> losses = new ArrayList<LifeLoss>();
        synchronized(this) {
            resolveLives(hostTick, healths, causes, downed, restored, losses);
        }
        for(ParticipantId participant : downed) encounter.discardParticipantCombatHistory(participant);
        for(Map.Entry<ParticipantId, Integer> restore : restored.entrySet()) {
            encounter.restoreParticipant(hostTick, restore.getKey(), restore.getValue(),
                    nativeCombatOutput);
        }
        for(LifeLoss loss : losses) applyLifeLoss(hostTick, loss);
        synchronized(this) {
            expireDeathDrops(hostTick);
            publishLivesIfChanged();
            AuthoritativeLives current = lives;
            if(current != null && !partyWiped && current.isPartyWiped(presentLivesParticipants())) {
                declarePartyWipe();
            }
        }
    }

    /** Slots in play: connected, or frozen inside their reconnect grace; not ones that left. */
    private List<ParticipantId> presentLivesParticipants() {
        List<ParticipantId> present = new ArrayList<ParticipantId>();
        PartyStatusSnapshot published = partyStatus;
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            PartyMemberStatus member = published == null ? null
                    : published.getMember(descriptor.getCampaignSlot());
            if(member != null && member.getEntityId() != null) present.add(descriptor.getParticipantId());
        }
        return present;
    }

    /**
     * One consumed Life: backpack and hotbar scatter where the Participant fell, the cause of
     * death destroys or damages part of it, and a balanced share of gold is forfeited. Nothing
     * is announced; the Participant discovers what is gone. Item world and economy take their
     * own locks, never together with the Host monitor.
     */
    private void applyLifeLoss(long hostTick, LifeLoss loss) {
        List<PhysicalItemState> carried = new ArrayList<PhysicalItemState>();
        long wielded = itemWorld.getWielded(loss.participant);
        for(PhysicalItemState item : itemWorld.inventory(loss.participant)) {
            if(item.consumed) continue;
            if(!item.equipmentSlot.isEmpty() || item.entityId == wielded) {
                // Worn and held gear stays through a death unless it was already broken.
                // Dead weight lost this way does not soften the gold penalty.
                if(item.properties.condition == 0) {
                    itemWorld.forfeit(item.entityId, true, loss.x, loss.y, loss.z, 0);
                }
                continue;
            }
            carried.add(item);
        }
        List<ItemKind> kinds = new ArrayList<ItemKind>(carried.size());
        List<Integer> conditions = new ArrayList<Integer>(carried.size());
        for(PhysicalItemState item : carried) {
            kinds.add(itemWorld.getKind(item.entityId));
            conditions.add(item.properties.condition);
        }
        List<DeathDropRules.Verdict> verdicts;
        synchronized(deathDropRandom) {
            verdicts = DeathDropRules.judge(loss.cause, kinds, conditions, deathDropRandom);
        }
        int destroyed = 0;
        float radius = DeathDropRules.scatterRadius(loss.cause);
        for(int index = 0; index < carried.size(); index++) {
            PhysicalItemState item = carried.get(index);
            DeathDropRules.Verdict verdict = verdicts.get(index);
            if(verdict.fate == DeathDropRules.Fate.DESTROYED) {
                if(itemWorld.forfeit(item.entityId, true, loss.x, loss.y, loss.z,
                        item.properties.condition)) destroyed++;
                continue;
            }
            float[] at = scatterPosition(loss.x, loss.y, loss.z, radius);
            if(!itemWorld.forfeit(item.entityId, false, at[0], at[1], at[2], verdict.condition)) continue;
            synchronized(this) {
                scatterOwners.put(item.entityId, Integer.parseInt(loss.participant.getValue().substring("campaign-slot-".length())));
                if(loss.exhausted) expiringDrops.put(item.entityId, hostTick + exhaustedDropTicks);
            }
        }
        com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress progress =
                economy.get(loss.participant);
        if(progress != null) {
            economy.forfeitGold(loss.participant,
                    DeathDropRules.goldLoss(progress.gold, loss.previousLifeLosses, destroyed));
        }
        publishPhysicalItems();
        publishEconomy();
    }

    /** A free spot within the scatter radius of the fall, else the fall position itself. */
    private float[] scatterPosition(float x, float y, float z, float radius) {
        for(int attempt = 0; attempt < 8; attempt++) {
            float offsetX, offsetY;
            synchronized(deathDropRandom) {
                offsetX = (deathDropRandom.nextFloat() * 2f - 1f) * radius;
                offsetY = (deathDropRandom.nextFloat() * 2f - 1f) * radius;
            }
            float candidateX = x + offsetX, candidateY = y + offsetY;
            if(movementWorld.canOccupy(candidateX, candidateY, z)
                    && movementWorld.hasLineOfSight(x, y, candidateX, candidateY)) {
                return new float[] { candidateX, candidateY, z };
            }
        }
        return new float[] { x, y, z };
    }

    /** Hidden six-minute timer on an exhausted Participant's scatter; pickup cancels per item. */
    private void expireDeathDrops(long hostTick) {
        if(expiringDrops.isEmpty()) return;
        boolean changed = false;
        for(Map.Entry<Long, Long> entry : new ArrayList<Map.Entry<Long, Long>>(expiringDrops.entrySet())) {
            PhysicalItemState item = itemWorld.get(entry.getKey());
            if(item == null || item.consumed || item.owner != null) {
                expiringDrops.remove(entry.getKey());
                continue;
            }
            if(hostTick < entry.getValue()) continue;
            itemWorld.destroyWorldItem(entry.getKey());
            expiringDrops.remove(entry.getKey());
            changed = true;
        }
        if(changed) publishPhysicalItems();
    }

    /** Items of an exhausted Participant still waiting on their hidden removal timer. */
    public synchronized int getExpiringDropCount() {
        return expiringDrops.size();
    }

    /** Test seam: shortens the hidden six-minute timer before campaign play begins. */
    public synchronized void setExhaustedDropTicks(long ticks) {
        if(ticks < 1L) throw new IllegalArgumentException("Exhausted drop timer needs one tick.");
        if(sessionStarted) throw new IllegalStateException("Exhausted drop timer locks once campaign play begins.");
        exhaustedDropTicks = ticks;
    }

    /** Terminal state: Host keeps serving chat and status, but simulation never advances again. */
    private void declarePartyWipe() {
        partyWiped = true;
        campaignSaveStore.markTerminal(roster.getCampaignId(), CampaignSave.Outcome.DEFEATED);
        ScheduledFuture<?> task = movementTask;
        if(task != null) task.cancel(false);
        broadcast(new DirectConnectWire.PartyWipeMessage(sessionId));
        status = status(DirectConnectPhase.READY,
                "Party Wipe: no Campaign Slot can return. The campaign is defeated.",
                null, sharedFloorId());
        // Native floor capture belongs to render thread; headless sessions can archive here.
        if(nativeFloorCapture == null) persistCampaign();
    }

    @Override
    public boolean isPartyWiped() { return partyWiped; }

    private void resolveLives(long hostTick, Map<ParticipantId, Integer> healths,
            Map<ParticipantId, DeathCause> causes, List<ParticipantId> downed,
            Map<ParticipantId, Integer> restored, List<LifeLoss> losses) {
        AuthoritativeLives current = lives;
        AuthoritativeMovementSimulation simulation = movementSimulation;
        if(current == null || simulation == null) return;
        for(Map.Entry<ParticipantId, RevivalAnchor> entry : revivalAnchors.entrySet()) {
            Integer before = tickHealths.get(entry.getKey());
            if(entry.getValue().health == RevivalAnchor.UNOBSERVED_HEALTH && before != null) {
                entry.getValue().health = before.intValue();
            }
        }
        tickHealths.clear();
        tickHealths.putAll(healths);
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            ParticipantId participant = descriptor.getParticipantId();
            Integer health = healths.get(participant);
            if(health == null || health.intValue() != 0 || !current.down(participant)) continue;
            downed.add(participant);
            revivalAnchors.remove(participant);
            deathDrops.remove(participant);
            MovementEntityState state = simulation.getState(participant);
            if(state != null) {
                deathDrops.put(participant, new DeathDrop(state.getX(), state.getY(), state.getZ(),
                        causes.get(participant)));
            }
            simulation.freezeParticipant(participant);
            status = status(DirectConnectPhase.READY, descriptor.getNickname() + " is Downed.",
                    participant.getValue(), sharedFloorId());
        }
        for(Map.Entry<ParticipantId, RevivalAnchor> entry
                : new ArrayList<Map.Entry<ParticipantId, RevivalAnchor>>(revivalAnchors.entrySet())) {
            if(revivalContinues(entry.getKey(), entry.getValue(), current, healths, simulation)) continue;
            revivalAnchors.remove(entry.getKey());
            current.cancelRevival(entry.getKey());
        }
        current.collapseBleedouts(presentLivesParticipants());
        for(AuthoritativeLives.Outcome outcome : current.tick()) {
            ParticipantId participant = outcome.participant;
            MovementEntityDescriptor descriptor = livesDescriptor(participant);
            if(outcome.kind == AuthoritativeLives.OutcomeKind.REVIVED) {
                revivalAnchors.remove(outcome.reviver);
                deathDrops.remove(participant);
                restored.put(participant, AuthoritativeLives.REVIVAL_HEALTH_PERCENT);
            }
            else {
                DeathDrop drop = deathDrops.remove(participant);
                MovementEntityState state = drop == null ? simulation.getState(participant) : null;
                if(drop == null && state != null) {
                    drop = new DeathDrop(state.getX(), state.getY(), state.getZ(), DeathCause.COMBAT);
                }
                if(drop != null) {
                    losses.add(new LifeLoss(participant, drop,
                            outcome.kind == AuthoritativeLives.OutcomeKind.EXHAUSTED,
                            Math.max(0, current.getLifeLosses(participant) - 1)));
                }
            }
            if(outcome.kind == AuthoritativeLives.OutcomeKind.EXHAUSTED) continue;
            if(outcome.kind == AuthoritativeLives.OutcomeKind.RESPAWNED) {
                if(descriptor != null) {
                    MovementSpawn respawnPoint = movementWorld.getSpawn(descriptor.getCampaignSlot());
                    simulation.setNativePosition(participant, respawnPoint.getX(),
                            respawnPoint.getY(), respawnPoint.getZ());
                }
                restored.put(participant, AuthoritativeLives.RESPAWN_HEALTH_PERCENT);
            }
            if(descriptor != null && !isInReconnectGrace(descriptor.getCampaignSlot())) {
                simulation.resumeParticipant(participant);
            }
        }
    }

    private boolean revivalContinues(ParticipantId reviver, RevivalAnchor anchor,
            AuthoritativeLives current, Map<ParticipantId, Integer> healths,
            AuthoritativeMovementSimulation simulation) {
        MovementEntityDescriptor descriptor = livesDescriptor(reviver);
        if(descriptor == null || !anchor.target.equals(current.getRevivalTarget(reviver))
                || isInReconnectGrace(descriptor.getCampaignSlot())) return false;
        Integer observed = healths.get(reviver);
        if(observed == null) return false;
        int health = observed.intValue();
        if(anchor.health != RevivalAnchor.UNOBSERVED_HEALTH && health < anchor.health) return false;
        anchor.health = health;
        if(simulation.isRequestingMovement(reviver)) return false;
        MovementEntityState state = simulation.getState(reviver);
        if(state == null) return false;
        float x = state.getX() - anchor.x, y = state.getY() - anchor.y;
        return x * x + y * y <= REVIVAL_DRIFT * REVIVAL_DRIFT
                && canReachDowned(state, simulation.getState(anchor.target));
    }

    private boolean canReachDowned(MovementEntityState reviver, MovementEntityState downed) {
        if(reviver == null || downed == null) return false;
        float x = reviver.getX() - downed.getX(), y = reviver.getY() - downed.getY();
        return x * x + y * y <= REVIVAL_REACH * REVIVAL_REACH
                && Math.abs(reviver.getZ() - downed.getZ()) <= 1f
                && movementWorld.hasLineOfSight(reviver.getX(), reviver.getY(),
                        downed.getX(), downed.getY());
    }

    private MovementEntityDescriptor livesDescriptor(ParticipantId participant) {
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            if(descriptor.getParticipantId().equals(participant)) return descriptor;
        }
        return null;
    }

    private void reviveIntent(ParticipantId reviver, int targetSlot, boolean active) {
        AuthoritativeLives current = lives;
        AuthoritativeMovementSimulation simulation = movementSimulation;
        if(current == null || simulation == null || isSessionPaused()) return;
        if(!active) {
            revivalAnchors.remove(reviver);
            current.cancelRevival(reviver);
            publishLivesIfChanged();
            return;
        }
        MovementEntityDescriptor source = livesDescriptor(reviver);
        MovementEntityDescriptor target = null;
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            if(descriptor.getCampaignSlot() == targetSlot) target = descriptor;
        }
        MovementEntityState state = simulation.getState(reviver);
        if(source == null || target == null || state == null
                || isInReconnectGrace(source.getCampaignSlot())
                || simulation.isRequestingMovement(reviver)
                || !canReachDowned(state, simulation.getState(target.getParticipantId()))
                || !current.beginRevival(reviver, target.getParticipantId())) return;
        RevivalAnchor anchor = revivalAnchors.get(reviver);
        if(anchor == null || !anchor.target.equals(target.getParticipantId())) {
            revivalAnchors.put(reviver, new RevivalAnchor(target.getParticipantId(),
                    state.getX(), state.getY(), tickHealths.containsKey(reviver)
                            ? tickHealths.get(reviver).intValue()
                            : RevivalAnchor.UNOBSERVED_HEALTH));
        }
        publishLivesIfChanged();
    }

    private synchronized void reviveIntent(ChannelHandlerContext context,
            DirectConnectWire.ReviveIntentMessage message) {
        if(message.generation != nativeWorldGeneration) return;
        RemoteConnection connection = activeConnection(context, message.sessionId, "Revival intent");
        if(connection == null || connection.reconnectGrace != null) return;
        reviveIntent(connection.movementDescriptor.getParticipantId(),
                message.targetSlot, message.active);
    }

    @Override
    public synchronized void submitReviveIntent(int targetSlot, boolean active) {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        if(descriptor == null || targetSlot < 1 || targetSlot > 4) return;
        reviveIntent(descriptor.getParticipantId(), targetSlot, active);
    }

    /** Campaign-wide starting Lives; locked once campaign play begins. */
    public synchronized void setStartingLives(int startingLives) {
        AuthoritativeLives.requireStartingLives(startingLives);
        if(sessionStarted || durableCampaign != null) {
            throw new IllegalStateException("Starting Lives lock once campaign play begins.");
        }
        this.startingLives = startingLives;
        publishLobby();
    }

    public int getStartingLives() {
        return startingLives;
    }

    public boolean isStartingLivesLocked() {
        return sessionStarted || durableCampaign != null;
    }

    private void beginReconnectGrace(RemoteConnection connection) {
        MovementEntityDescriptor descriptor = connection.movementDescriptor;
        AuthoritativeHostSession session = movementSession;
        long hostTick = session == null ? 0L : session.getHostTick();
        ReconnectGrace grace = new ReconnectGrace(descriptor.getCampaignSlot(),
                descriptor.getEntityId(), hostTick, hostTick + reconnectGraceTicks);
        reconnectingParticipants.put(connection.launcherIdentity,
                new GraceParticipant(connection.slot, descriptor, grace));
        AuthoritativeMovementSimulation simulation = movementSimulation;
        if(simulation != null) simulation.freezeParticipant(descriptor.getParticipantId());
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.setParticipantCombatEligible(descriptor.getParticipantId(), false);
        }
        markPartyMemberReconnecting(descriptor);
        publishPartyNotice(descriptor, descriptor.getNickname() + " left", connection);
        status = status(DirectConnectPhase.READY,
                descriptor.getNickname() + " disconnected; frozen and invulnerable for "
                        + reconnectGraceTicks + " unpaused Host ticks.",
                descriptor.getParticipantId().getValue(), sharedFloorId());
    }

    private synchronized void completeReconnect(RemoteConnection connection) {
        GraceParticipant grace = connection.reconnectGrace;
        if(grace == null || reconnectingParticipants.remove(connection.launcherIdentity) != grace) {
            failConnection(connection, "Reconnect grace expired before UDP authentication completed.");
            return;
        }
        AuthoritativeMovementSimulation simulation = movementSimulation;
        AuthoritativeLives currentLives = lives;
        if(simulation != null && (currentLives == null
                || currentLives.isStanding(grace.descriptor.getParticipantId()))) {
            simulation.resumeParticipant(grace.descriptor.getParticipantId());
        }
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.setParticipantCombatEligible(grace.descriptor.getParticipantId(), true);
        }
        markPartyMemberConnected(grace.descriptor);
        connection.reconnectGrace = null;
        publishPartyNotice(grace.descriptor, grace.descriptor.getNickname() + " joined",
                connection);
        sendCurrentSessionState(connection, grace.descriptor,
                grace.descriptor.getNickname() + " reclaimed Campaign Slot "
                        + grace.slot.getNumber() + " during reconnect grace.");
    }

    private synchronized void completeReturnedParticipant(RemoteConnection connection) {
        ReturnedParticipant returned = connection.returnedParticipant;
        if(returned == null
                || returnedParticipants.remove(connection.launcherIdentity) != returned) {
            failConnection(connection,
                    "Campaign Slot reconnect was superseded before UDP authentication completed.");
            return;
        }

        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(
                ++nextLifecycleSequence, returned.descriptor.getEntityId(),
                returned.descriptor.getParticipantId(), returned.slot.getNumber(),
                returned.slot.getPresentation().getNickname(),
                returned.slot.getPresentation().getAvatarId());
        AuthoritativeMovementSimulation simulation = movementSimulation;
        if(simulation == null) {
            failConnection(connection, "Active Floor movement state is unavailable.");
            return;
        }
        simulation.addParticipant(descriptor);
        AuthoritativeLives currentLives = lives;
        if(returned.coldResume) {
            CampaignSave.ParticipantState saved = durableCampaign.getParticipant(returned.slot.getNumber());
            PartyMemberStatus party = saved.getParty();
            currentLives.addParticipant(descriptor.getParticipantId(), startingLives);
            AuthoritativeLives.Condition condition = party.getRemainingLives() == 0
                    ? AuthoritativeLives.Condition.EXHAUSTED
                    : party.getHealth() == 0 ? AuthoritativeLives.Condition.DOWNED
                    : AuthoritativeLives.Condition.STANDING;
            currentLives.restore(descriptor.getParticipantId(), party.getRemainingLives(), condition,
                    condition == AuthoritativeLives.Condition.DOWNED ? party.getBleedoutTicks() : 0,
                    0, null, Math.max(0, startingLives - party.getRemainingLives()));
            combatEncounter.addSavedParticipant(descriptor, party.getHealth(), party.getMaximumHealth());
            List<MovementEntityDescriptor> updated = new ArrayList<MovementEntityDescriptor>(livesDescriptors);
            updated.add(descriptor);
            livesDescriptors = updated;
        }
        boolean standing = currentLives == null
                || currentLives.isStanding(descriptor.getParticipantId());
        if(!standing) simulation.freezeParticipant(descriptor.getParticipantId());

        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            MovementEntityState state = simulation.getState(descriptor.getParticipantId());
            if(state != null) {
                encounter.updateParticipantPosition(descriptor.getParticipantId(),
                        state.getX(), state.getY(), state.getZ());
            }
            encounter.setParticipantCombatEligible(descriptor.getParticipantId(), standing);
        }

        connection.movementDescriptor = descriptor;
        if(!movementReplication.applySpawn(descriptor)) {
            simulation.removeParticipant(descriptor.getParticipantId());
            failConnection(connection, "Campaign Slot entity lifecycle could not be restored.");
            return;
        }
        EntitySpawn spawn = new EntitySpawn(sessionId, descriptor);
        for(RemoteConnection remaining : connections.values()) {
            if(remaining != connection && remaining.reconnectGrace == null) deliverCurrentState(remaining, spawn);
        }
        markPartyMemberConnected(descriptor);
        publishPartyNotice(descriptor, descriptor.getNickname() + " joined", connection);
        sendCurrentSessionState(connection, descriptor,
                descriptor.getNickname() + " reclaimed Campaign Slot "
                        + returned.slot.getNumber() + " after reconnect grace.");
        connection.returnedParticipant = null;
    }

    /** #27 calls only after first-arrival destination authority is installed and stable. */
    public boolean freshReturnLateParticipant(int slotNumber, long generation,
            boolean firstArrival, com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress fresh,
            Runnable grantStarterKit) {
        CampaignSlot slot;
        ParticipantId participant;
        MovementSpawn spawn;
        MovementEntityDescriptor descriptor;
        synchronized(this) {
            if(!sessionStarted || !firstArrival || !nativeWorldStable || generation != nativeWorldGeneration
                    || partyWiped || partyProgression.victory || fresh == null || grantStarterKit == null) return false;
            slot = roster.getSlot(slotNumber);
            PartyMemberStatus member = partyMember(slotNumber);
            if(slot == null || member == null || member.getEntityId() != null
                    || member.getState() != PartyMemberState.SPECTATING
                    || !lateParticipants.contains(slot.getLauncherIdentity())
                    || !lateEntryGenerations.containsKey(slot.getLauncherIdentity())
                    || generation <= lateEntryGenerations.get(slot.getLauncherIdentity())) return false;
            participant = participantId(slot);
            if(!participant.equals(fresh.participantId) || fresh.gold != 0 || fresh.experience != 0
                    || fresh.level != 1 || fresh.pendingStatChoices != 0) {
                throw new IllegalArgumentException("Fresh Return requires a new native character.");
            }
            spawn = findLateSpawn(slotNumber);
            descriptor = new MovementEntityDescriptor(++nextLifecycleSequence,
                    entityId(slot), participant, slotNumber, slot.getPresentation().getNickname(),
                    slot.getPresentation().getAvatarId());
            if(!freshReturnsPending.add(slot.getLauncherIdentity())) return false;
            itemWorld.registerParticipant(participant, fresh.inventorySize);
            try { grantStarterKit.run(); }
            catch(RuntimeException failure) {
                itemWorld.discardOwnedItems(participant);
                freshReturnsPending.remove(slot.getLauncherIdentity());
                throw failure;
            }
        }
        // Stage combat inertly without holding Host monitor, matching encounter->Host event order.
        combatEncounter.addParticipant(descriptor, fresh.maximumHealth, false);
        combatEncounter.updateParticipantPosition(participant, spawn.getX(), spawn.getY(), spawn.getZ());
        boolean committed = false;
        synchronized(this) {
            PartyMemberStatus current = partyMember(slotNumber);
            if(generation == nativeWorldGeneration && nativeWorldStable && current != null
                    && current.getState() == PartyMemberState.SPECTATING && !partyWiped && !partyProgression.victory) {
                movementSimulation.addParticipant(descriptor);
                movementSimulation.setNativePosition(participant, spawn.getX(), spawn.getY(), spawn.getZ());
                lives.addParticipant(participant, startingLives);
                List<MovementEntityDescriptor> updated = new ArrayList<MovementEntityDescriptor>(livesDescriptors);
                updated.add(descriptor);
                livesDescriptors = updated;
                economy.registerParticipant(fresh);
                lateParticipants.remove(slot.getLauncherIdentity());
                lateEntryGenerations.remove(slot.getLauncherIdentity());
                for(RemoteConnection connection : connections.values()) {
                    if(connection.slot == null || connection.slot.getNumber() != slotNumber) continue;
                    connection.movementDescriptor = descriptor;
                    connection.lateParticipant = false;
                    connection.spectatorReady = false;
                }
                movementReplication.applySpawn(descriptor);
                broadcast(new EntitySpawn(sessionId, descriptor));
                List<PartyMemberStatus> members = new ArrayList<PartyMemberStatus>(partyStatus.getMembers());
                for(int i = 0; i < members.size(); i++) if(members.get(i).getCampaignSlot() == slotNumber) {
                    members.set(i, new PartyMemberStatus(slotNumber, descriptor.getEntityId(), descriptor.getNickname(),
                            descriptor.getAvatarId(), fresh.maximumHealth, fresh.maximumHealth,
                            startingLives, PartyMemberState.CONNECTED));
                }
                partyStatus = new PartyStatusSnapshot(++nextPartyStatusSequence, members);
                broadcastPartyStatus();
                publishEconomy();
                publishPhysicalItems();
                committed = true;
            }
            else itemWorld.discardOwnedItems(participant);
            freshReturnsPending.remove(slot.getLauncherIdentity());
        }
        if(!committed) { combatEncounter.removeUnactivatedParticipant(participant); return false; }
        combatEncounter.setParticipantCombatEligible(participant, true);
        CombatSnapshot combat = combatEncounter.getSnapshot(currentHostTick());
        synchronized(this) { publishedCombatSnapshot.set(combat); broadcastCombatState(combat); }
        return true;
    }

    private MovementSpawn findLateSpawn(int slotNumber) {
        MovementSpawn point = movementWorld.getSpawn(slotNumber);
        for(int ring = 0; ring <= 8; ring++) {
            for(int direction = 0; direction < (ring == 0 ? 1 : 16); direction++) {
                double angle = direction * Math.PI / 8;
                float x = point.getX() + (float)Math.cos(angle) * ring * 0.45f;
                float y = point.getY() + (float)Math.sin(angle) * ring * 0.45f;
                float z = movementWorld.getFloorZ(x, y, point.getZ());
                if(!movementWorld.canOccupy(x, y, z)) continue;
                boolean occupied = false;
                for(MovementEntityState other : movementSimulation.currentStates()) {
                    float dx = x - other.getX(), dy = y - other.getY();
                    if(dx * dx + dy * dy < 0.4f * 0.4f && Math.abs(z - other.getZ()) < 0.75f) {
                        occupied = true; break;
                    }
                }
                if(!occupied) return new MovementSpawn(x, y, z, point.getRotation());
            }
        }
        throw new IllegalStateException("Destination Respawn Point has no safe Late Participant placement.");
    }

    /** Wall-clock bound still expires during Party pause or native loading. */
    public synchronized void updatePendingAdmissions(long nowNanos) {
        for(RemoteConnection connection : new ArrayList<RemoteConnection>(connections.values())) {
            if(!connection.lateParticipant || connection.spectatorReady || connection.slot == null
                    || connection.kicked) continue;
            if(partyWiped || partyProgression.victory) {
                rejectPendingAdmission(connection, RejectCode.NOT_IN_LOBBY, "Campaign ended before admission.");
            }
            else if(nowNanos - connection.admissionStartedNanos >= ADMISSION_TIMEOUT_NANOS) {
                rejectPendingAdmission(connection, RejectCode.ADMISSION_TIMEOUT,
                        "Admission timed out; reconnect with same identity to retry this slot.");
            }
        }
    }

    private void rejectPendingAdmission(RemoteConnection connection, RejectCode code, String reason) {
        connection.kicked = true;
        connection.admissionId = 0L;
        connection.admissionChanges.clear();
        reject(connection.channel, code, reason);
    }
    /** Native loading / Party Transition must hold admission until destination is reconstructed. */
    public synchronized void awaitNativeWorld() { nativeWorldStable = false; }

    public synchronized void completeNativeWorld(long generation) {
        if(generation == nativeWorldGeneration
                && travel.phase != com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING)
            nativeWorldStable = true;
    }


    private PartyStatusSnapshot withLateSpectator(RemoteConnection connection,
            PartyStatusSnapshot current) {
        List<PartyMemberStatus> members = new ArrayList<PartyMemberStatus>(current.getMembers());
        CampaignSlot slot = connection.slot;
        PartyMemberStatus spectator = new PartyMemberStatus(slot.getNumber(), null,
                slot.getPresentation().getNickname(), slot.getPresentation().getAvatarId(),
                0, PartyMemberStatus.DEFAULT_HEALTH, 0, PartyMemberState.SPECTATING);
        boolean replaced = false;
        for(int i = 0; i < members.size(); i++) {
            if(members.get(i).getCampaignSlot() != slot.getNumber()) continue;
            members.set(i, spectator);
            replaced = true;
            break;
        }
        if(!replaced) members.add(spectator);
        return new PartyStatusSnapshot(++nextPartyStatusSequence, members);
    }

    private void beginLateAdmission(RemoteConnection connection) {
        connection.admissionChanges.clear();
        connection.admissionId = ++nextAdmissionId;
        connection.admissionGeneration = nativeWorldGeneration;
        connection.admissionTick = currentHostTick();
        connection.admissionStage = DirectConnectWire.AdmissionSync.BASELINE_END;
        connection.channel.write(admissionFence(connection, DirectConnectWire.AdmissionSync.BEGIN));
        sendCurrentSessionState(connection, null, "Late Participant synchronizes current Active Floor.");
        connection.channel.writeAndFlush(admissionFence(connection,
                DirectConnectWire.AdmissionSync.BASELINE_END));
    }

    private DirectConnectWire.AdmissionSync admissionFence(RemoteConnection connection, int stage) {
        return new DirectConnectWire.AdmissionSync(sessionId, connection.admissionId,
                connection.admissionGeneration, connection.admissionTick, stage);
    }

    private synchronized void admissionAcknowledged(ChannelHandlerContext context,
            DirectConnectWire.AdmissionSync acknowledgement) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null || connection.admissionId == 0L || connection.spectatorReady
                || !sessionId.equals(acknowledgement.sessionId)
                || connection.admissionId != acknowledgement.admissionId
                || connection.admissionGeneration != acknowledgement.generation
                || connection.admissionGeneration != nativeWorldGeneration
                || connection.admissionTick != acknowledgement.hostTick) return;
        if(connection.admissionStage == DirectConnectWire.AdmissionSync.BASELINE_END
                && acknowledgement.stage == DirectConnectWire.AdmissionSync.ACK_BASELINE) {
            connection.admissionStage = DirectConnectWire.AdmissionSync.ACK_BASELINE;
        }
        else if(connection.admissionStage == DirectConnectWire.AdmissionSync.CATCH_UP_END
                && acknowledgement.stage == DirectConnectWire.AdmissionSync.ACK_CATCH_UP) {
            connection.admissionStage = DirectConnectWire.AdmissionSync.ACK_CATCH_UP;
        }
    }

    private synchronized void advanceAdmissions(long hostTick) {
        if(!nativeWorldStable || partyWiped || partyProgression.victory) return;
        for(RemoteConnection connection : connections.values()) {
            if(!connection.lateParticipant || connection.spectatorReady
                    || connection.udpAddress == null || connection.kicked || !connection.channel.isActive()) continue;
            if(connection.admissionId == 0L) {
                beginLateAdmission(connection);
                continue;
            }
            if(connection.admissionStage == DirectConnectWire.AdmissionSync.ACK_BASELINE) {
                connection.admissionTick = hostTick;
                connection.admissionStage = DirectConnectWire.AdmissionSync.CATCH_UP_END;
                while(!connection.admissionChanges.isEmpty()) {
                    connection.channel.write(connection.admissionChanges.removeFirst());
                }
                connection.channel.writeAndFlush(admissionFence(connection,
                        DirectConnectWire.AdmissionSync.CATCH_UP_END));
            }
            else if(connection.admissionStage == DirectConnectWire.AdmissionSync.ACK_CATCH_UP) {
                partyStatus = withLateSpectator(connection, partyStatus);
                if(!lateEntryGenerations.containsKey(connection.launcherIdentity)) {
                    lateEntryGenerations.put(connection.launcherIdentity, nativeWorldGeneration);
                }
                connection.admissionTick = hostTick;
                connection.spectatorReady = true;
                broadcastPartyStatus();
                connection.channel.writeAndFlush(admissionFence(connection,
                        DirectConnectWire.AdmissionSync.ACTIVATED));
            }
        }
    }

    private void sendCurrentSessionState(RemoteConnection connection,
            MovementEntityDescriptor descriptor, String reconnectMessage) {
        ParticipantId participant = participantId(connection.slot);
        connection.channel.write(new DirectConnectWire.NativeWorldGenerationMessage(sessionId, nativeWorldGeneration));
        for(MovementEntityDescriptor present : movementReplication.getEntities()) {
            connection.channel.write(new EntitySpawn(sessionId, present));
        }
        if(movementSimulation != null && currentHostTick() > 0L) connection.channel.write(new MovementSnapshotMessage(sessionId,
                (MovementSnapshot)movementSimulation.snapshot(currentHostTick()), nativeWorldGeneration));
        connection.channel.write(new PartyStatusMessage(sessionId,
                connection.lateParticipant && !connection.spectatorReady
                        ? withLateSpectator(connection, partyStatus) : partyStatus));
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            connection.channel.write(new CombatStateMessage(sessionId, publishedCombatSnapshot.get()));
        }
        connection.channel.write(new DirectConnectWire.NativeWorldGenerationMessage(sessionId, nativeWorldGeneration));
        if(partyDestination != null) connection.channel.write(new DirectConnectWire.TravelDestination(sessionId, partyDestination));
        for(com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn spawn : nativeMonsterSpawns) {
            connection.channel.write(new DirectConnectWire.NativeMonsterSpawnMessage(sessionId,
                    ++nativeMonsterSpawnSequence, spawn, nativeWorldGeneration));
        }
        for(com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState decal : nativeDecals) {
            connection.channel.write(new DirectConnectWire.NativeDecalMessage(sessionId,
                    nativeWorldGeneration, decal));
        }
        if(partyWiped) connection.channel.write(new DirectConnectWire.PartyWipeMessage(sessionId));
        for(ActorEffectsSnapshot effects : getActorEffects()) {
            connection.channel.write(new DirectConnectWire.MonsterEffectsMessage(sessionId, effects, false, nativeWorldGeneration));
        }
        for(NativeDynamicState state : getNativeDynamicStates()) {
            connection.channel.write(new DirectConnectWire.NativeDynamicStateMessage(sessionId,
                    ++nativeDynamicSequence, state, nativeWorldGeneration));
        }
        connection.channel.write(new DirectConnectWire.PotionMappingMessage(sessionId, potionMapping));
        connection.channel.write(new DirectConnectWire.PartyProgressionMessage(sessionId, partyProgression));
        connection.channel.write(new DirectConnectWire.PersonalKnowledgeMessage(sessionId,
                participant, nativeWorldGeneration, getPersonalKnowledge(participant)));
        connection.channel.write(new DirectConnectWire.PartyKeysMessage(sessionId,
                itemWorld.getKeyRevision(), itemWorld.getPartyKeys()));
        for(com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress progress : economy.progressSnapshot()) {
            connection.channel.write(new DirectConnectWire.ParticipantProgressMessage(sessionId, progress));
        }
        for(com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState entry : economy.shopSnapshot()) {
            connection.channel.write(new DirectConnectWire.ShopEntryStateMessage(sessionId, entry));
        }
        for(DoorSnapshot door : doorSnapshots.values()) {
            connection.channel.write(new DirectConnectWire.DoorStateMessage(sessionId, door));
        }
        for(com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot mover : moverSnapshots.values()) {
            connection.channel.write(new DirectConnectWire.MoverStateMessage(sessionId, mover));
        }
        for(BreakableSnapshot breakable : breakableSnapshots.values()) {
            connection.channel.write(
                    new DirectConnectWire.BreakableStateMessage(sessionId, breakable));
        }
        for(PhysicalItemState item : itemWorld.snapshot()) {
            connection.channel.write(new DirectConnectWire.ItemStateMessage(sessionId, item));
        }
        for(PartyChatMessage message : partyCommunication.getChatHistory()) {
            connection.channel.write(new PartyChatDelivery(sessionId, message));
        }
        connection.channel.writeAndFlush(new SessionReady(sessionId,
                getConnectedParticipantCount(), encounter == null || descriptor == null ? 1L
                        : encounter.getNextRequestId(participant),
                itemWorld.nextRequestId(participant), sharedFloorId(),
                sharedFloorSeed));
        status = status(DirectConnectPhase.READY,
                reconnectMessage, participant.getValue(),
                sharedFloorId());
    }

    private synchronized void expireReconnectGrace(long hostTick) {
        List<LauncherIdentity> expired = new ArrayList<LauncherIdentity>();
        for(Map.Entry<LauncherIdentity, GraceParticipant> entry
                : reconnectingParticipants.entrySet()) {
            if(entry.getValue().grace.getRemainingUnpausedTicks(hostTick) == 0L) {
                expired.add(entry.getKey());
            }
        }
        for(LauncherIdentity identity : expired) {
            GraceParticipant grace = reconnectingParticipants.remove(identity);
            if(grace == null) continue;
            disconnectExpiredReconnect(grace);
            returnedParticipants.put(identity,
                    new ReturnedParticipant(grace.slot, grace.descriptor));
            returnParticipantToCampaignSlot(grace.descriptor,
                    grace.descriptor.getNickname()
                            + " reconnect grace expired; Campaign Slot returned safely.");
        }
    }

    private void disconnectExpiredReconnect(GraceParticipant grace) {
        for(RemoteConnection connection : connections.values()) {
            if(connection.reconnectGrace != grace) continue;
            connection.reconnectGrace = null;
            connection.movementDescriptor = null;
            connection.kicked = true;
            connection.channel.writeAndFlush(new ServerDisconnect(
                    "Reconnect grace expired. Campaign Slot and progression remain safe."))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void returnParticipantToCampaignSlot(MovementEntityDescriptor descriptor,
            String message) {
        AuthoritativeMovementSimulation simulation = movementSimulation;
        if(simulation != null) simulation.removeParticipant(descriptor.getParticipantId());
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.setParticipantCombatEligible(descriptor.getParticipantId(), false);
        }
        long lifecycleSequence = ++nextLifecycleSequence;
        movementReplication.applyDespawn(lifecycleSequence, descriptor.getEntityId());
        EntityDespawn despawn = new EntityDespawn(sessionId, lifecycleSequence,
                descriptor.getEntityId());
        broadcast(despawn);
        markPartyMemberDisconnected(descriptor.getCampaignSlot());
        status = status(DirectConnectPhase.READY, message,
                descriptor.getParticipantId().getValue(), sharedFloorId());
    }

    private void rejectClaim(Channel channel, ClaimOutcome outcome) {
        reject(channel, rejectCode(outcome.getStatus()), outcome.getReason());
    }

    private static RejectCode rejectCode(ClaimStatus status) {
        switch(status) {
            case CAMPAIGN_FULL: return RejectCode.CAMPAIGN_FULL;
            case SLOT_OCCUPIED: return RejectCode.SLOT_OCCUPIED;
            case RECONNECT_DENIED: return RejectCode.RECONNECT_DENIED;
            case NICKNAME_TAKEN: return RejectCode.NICKNAME_TAKEN;
            case AVATAR_UNAVAILABLE: return RejectCode.AVATAR_UNAVAILABLE;
            case RELINK_REQUIRED: return RejectCode.SLOT_OCCUPIED;
            case RELINK_DENIED: return RejectCode.SLOT_OCCUPIED;
            default: return RejectCode.MALFORMED_HANDSHAKE;
        }
    }

    private void reject(ChannelHandlerContext context, RejectCode code, String reason) {
        reject(context.channel(), code, reason);
    }

    private void reject(Channel channel, RejectCode code, String reason) {
        channel.writeAndFlush(new ServerRejected(code, boundedReason(reason)))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private synchronized void malformed(ChannelHandlerContext context, Throwable failure) {
        if(closing.get()) {
            context.close();
            return;
        }
        reject(context, RejectCode.MALFORMED_HANDSHAKE,
                "Malformed handshake: " + safeMessage(failure));
        RemoteConnection connection = connections.get(context.channel());
        if(connection != null && !sessionStarted) {
            status = status(DirectConnectPhase.DISCONNECTED,
                    "Launcher Identity sent malformed session input and was disconnected.",
                    connection.launcherIdentity.getFingerprint(), null);
        }
    }

    private synchronized void failConnection(RemoteConnection connection, String reason) {
        if(!sessionStarted) status = status(DirectConnectPhase.FAILED, boundedReason(reason),
                connection.launcherIdentity.getFingerprint(), null);
        connection.channel.close();
    }

    private RemoteConnection findConnection(LauncherIdentity identity) {
        for(RemoteConnection connection : connections.values()) {
            if(connection.launcherIdentity.equals(identity) && connection.channel.isActive()) {
                return connection;
            }
        }
        return null;
    }

    private RemoteConnection findConnectionValue(String launcherIdentity) {
        for(RemoteConnection connection : connections.values()) {
            if(connection.launcherIdentity.getValue().equals(launcherIdentity)
                    && connection.channel.isActive()) return connection;
        }
        return null;
    }

    private RemoteConnection findConnection(long udpToken) {
        if(udpToken == 0L) return null;
        for(RemoteConnection connection : connections.values()) {
            if(connection.udpToken == udpToken && connection.slot != null
                    && connection.channel.isActive()) return connection;
        }
        return null;
    }

    private RemoteConnection findPending(String launcherIdentity) {
        if(launcherIdentity == null) return null;
        for(RemoteConnection connection : connections.values()) {
            if(connection.request != null && connection.slot == null
                    && connection.launcherIdentity.getValue().equals(launcherIdentity)) {
                return connection;
            }
        }
        return null;
    }

    public CampaignRoster getRoster() {
        return roster;
    }

    @Override
    public NetworkEntityId getLocalMovementEntityId() {
        return localMovementEntityId;
    }

    @Override
    public List<MovementEntityDescriptor> getMovementEntities() {
        return movementReplication.getEntities();
    }

    @Override
    public List<MovementSnapshot> getMovementSnapshots() {
        return movementReplication.getSnapshots();
    }

    @Override
    public List<MovementEntityState> getCurrentMovementStates() {
        AuthoritativeMovementSimulation simulation = movementSimulation;
        return simulation == null ? null : simulation.currentStates();
    }

    @Override
    public PartyStatusSnapshot getPartyStatus() {
        return partyStatus;
    }

    /** Returns current Host protection state, or null after reconnect, kick, or expiry. */
    public synchronized ReconnectGrace getReconnectGrace(int campaignSlot) {
        for(GraceParticipant participant : reconnectingParticipants.values()) {
            if(participant.grace.getCampaignSlot() == campaignSlot) return participant.grace;
        }
        return null;
    }

    @Override
    public PartyCommunicationState getPartyCommunicationState() {
        return partyCommunication;
    }

    @Override
    public synchronized void submitPartyChat(String text) {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        if(descriptor == null) return;
        publishPartyChat(descriptor, text);
    }

    @Override
    public synchronized void requestPauseSession() {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        if(descriptor != null) publishPauseRequest(descriptor);
    }

    @Override
    public boolean isSessionPaused() {
        return partyCommunication.getPauseSession().isPaused();
    }

    @Override
    public boolean canControlSessionPause() {
        return true;
    }

    public synchronized void registerPartyPortal(String key, float x, float y, float z) {
        if(key == null || key.isEmpty() || key.length() > 256 || !Float.isFinite(x)
                || !Float.isFinite(y) || !Float.isFinite(z)) throw new IllegalArgumentException("Invalid Party portal.");
        partyPortals.put(key, new float[] { x, y, z });
    }

    @Override public com.interrupt.dungeoneer.multiplayer.floor.PartyTransition getPartyTransition() { return travel; }
    @Override public synchronized void requestPartyTransition(String portal) {
        MovementEntityDescriptor source = hostMovementDescriptor();
        if(source != null) travelIntent(source.getParticipantId(), nativeWorldGeneration, portal);
    }
    @Override public synchronized void cancelPartyTransition() { requestPartyTransition(""); }

    /** Native Host trigger attribution already passed authenticated object-use boundary. */
    public synchronized void requestNativePartyTransition(ParticipantId participant, String portal) {
        if(livesDescriptor(participant) != null) travelIntent(participant, nativeWorldGeneration, portal);
    }

    private void travelIntent(ParticipantId participant, long generation, String portal) {
        if(!sessionStarted || !nativeWorldStable || isSessionPaused() || partyWiped
                || partyProgression.victory || generation != nativeWorldGeneration || !lives.isStanding(participant)) return;
        if(portal.isEmpty()) {
            if(travel.phase != com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING) clearPartyTravel();
            return;
        }
        if(travel.phase != com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.IDLE) return;
        float[] at = partyPortals.get(portal);
        if(at == null || !nearPortal(movementSimulation.getState(participant), at, 1.1f)) return;
        travelActivator = participant;
        publishTravel(com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.GATHERING, portal,
                com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.COUNTDOWN_TICKS);
    }

    private boolean nearPortal(MovementEntityState state, float[] at, float radius) {
        if(state == null) return false;
        float dx = state.getX() - at[0], dy = state.getY() - at[1];
        return dx * dx + dy * dy <= radius * radius && Math.abs(state.getZ() - at[2]) <= 1.5f
                && movementWorld.hasLineOfSight(state.getX(), state.getY(), at[0], at[1]);
    }

    private synchronized void advancePartyTravel() {
        if(travel.phase == com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.IDLE
                || travel.phase == com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING) return;
        float[] at = partyPortals.get(travel.portal);
        if(at == null || !lives.isStanding(travelActivator)) { clearPartyTravel(); return; }
        boolean gathered = true;
        for(PartyMemberStatus member : partyStatus.getMembers()) {
            if(member.getState() != PartyMemberState.CONNECTED || member.getHealth() <= 0) continue;
            ParticipantId participant = new ParticipantId("campaign-slot-" + member.getCampaignSlot());
            if(!nearPortal(movementSimulation.getState(participant), at,
                    com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.GATHER_RADIUS)) gathered = false;
        }
        if(!gathered) {
            if(travel.phase == com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.COUNTDOWN)
                clearPartyTravel();
            return;
        }
        int remaining = travel.remainingTicks - 1;
        if(remaining == 0) {
            publishTravel(com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING, travel.portal, 0);
            nativeWorldStable = false;
            setSessionPaused(true);
        }
        else publishTravel(com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.COUNTDOWN, travel.portal, remaining);
    }

    private void publishTravel(com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase phase, String portal, int ticks) {
        travel = new com.interrupt.dungeoneer.multiplayer.floor.PartyTransition(travel.sequence + 1L,
                nativeWorldGeneration, phase, portal, ticks);
        broadcast(new DirectConnectWire.TravelState(sessionId, travel));
    }
    private void clearPartyTravel() {
        travelActivator = null;
        publishTravel(com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.IDLE, "", 0);
    }

    @Override
    public synchronized void setSessionPaused(boolean paused) {
        if(!paused && travel.phase == com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING) return;
        if(!sessionStarted || closing.get() || isSessionPaused() == paused) return;
        PauseSessionState state = new PauseSessionState(++nextPauseSessionSequence, paused);
        partyCommunication = partyCommunication.withPauseSession(state);
        if(paused) {
            for(ActorEffectsSnapshot effects : monsterEffects.values()) {
                if(!effects.sameState(publishedMonsterEffects.get(effects.monsterId))) {
                    publishMonsterEffects(effects, currentHostTick());
                }
            }
            for(NativeDynamicState dynamicState : nativeDynamicStates.values()) {
                if(!dynamicState.sameState(publishedNativeDynamicStates.get(dynamicState.id)))
                    publishNativeDynamicState(dynamicState, currentHostTick());
            }
        }
        broadcast(new PauseSessionStateMessage(sessionId, state));
    }

    private MovementEntityDescriptor hostMovementDescriptor() {
        NetworkEntityId hostEntity = localMovementEntityId;
        return hostEntity == null ? null : movementReplication.getEntity(hostEntity);
    }

    private void submitSessionCommand(AuthoritativeHostSession session,
            com.interrupt.dungeoneer.multiplayer.host.HostSessionCommand command) {
        // Pause check and enqueue share session monitor without holding Host monitor.
        session.submit(command, () -> !isSessionPaused());
    }

    @Override
    public void submitMovementInput(MovementInputFrame input) {
        if(input == null) throw new IllegalArgumentException("Movement input cannot be null.");
        NetworkEntityId localEntityId = localMovementEntityId;
        AuthoritativeHostSession session = movementSession;
        if(localEntityId == null || session == null || !sessionStarted || closing.get()
                || isSessionPaused()) return;
        MovementEntityDescriptor descriptor = movementReplication.getEntity(localEntityId);
        if(descriptor != null) {
            submitSessionCommand(session, new MovementInputCommand(descriptor.getParticipantId(), input));
        }
    }

    @Override
    public void submitCombatAction(long requestId, CombatAction action, String targetId) {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        AuthoritativeHostSession session = movementSession;
        if(descriptor == null || session == null || !sessionStarted || closing.get()
                || isSessionPaused()) return;
        submitSessionCommand(session, new CombatRequest(descriptor.getParticipantId(), requestId, action, targetId));
    }

    @Override
    public void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ) {
        submitCombatAction(requestId, action, aimX, aimY, aimZ, 1f);
    }

    @Override
    public void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ, float attackPower) {
        submitCombatAction(requestId, action, aimX, aimY, aimZ, attackPower, 0L);
    }

    @Override
    public void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ, float attackPower, long weaponEntityId) {
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        AuthoritativeHostSession session = movementSession;
        if(descriptor == null || session == null || !sessionStarted || closing.get()
                || isSessionPaused()) return;
        submitSessionCommand(session, new CombatRequest(descriptor.getParticipantId(), requestId,
                action, aimX, aimY, aimZ, attackPower, weaponEntityId));
    }

    @Override
    public CombatSnapshot getCombatSnapshot() {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        AuthoritativeHostSession session = movementSession;
        return encounter == null ? null : encounter.getSnapshot(session == null ? 0L : session.getHostTick());
    }

    @Override
    public List<CombatPresentationEvent> getCombatPresentationEvents() {
        return combatPresentations.getEvents();
    }

    @Override
    public long getNextCombatRequestId() {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        MovementEntityDescriptor descriptor = hostMovementDescriptor();
        return encounter == null || descriptor == null ? 1L
                : encounter.getNextRequestId(descriptor.getParticipantId());
    }

    @Override
    public void bindNativeMonster(int health, int maximumHealth,
            float x, float y, float z) {
        bindNativeMonster(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                health, maximumHealth, x, y, z);
    }

    @Override
    public void bindNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z) {
        bindNativeMonster(monsterId, health, maximumHealth, x, y, z, false);
    }

    @Override
    public void bindNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z, boolean gibbed) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.bindNativeMonster(currentHostTick(), monsterId, health, maximumHealth,
                    x, y, z, gibbed, nativeCombatOutput);
        }
    }

    @Override
    public void synchronizeNativeMonster(int health, int maximumHealth,
            float x, float y, float z) {
        synchronizeNativeMonster(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                health, maximumHealth, x, y, z);
    }

    @Override
    public void synchronizeNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z) {
        synchronizeNativeMonster(monsterId, health, maximumHealth, x, y, z, false);
    }

    @Override
    public void synchronizeNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z, boolean gibbed) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.synchronizeNativeMonster(currentHostTick(), monsterId, health,
                    maximumHealth, x, y, z, gibbed, nativeCombatOutput);
        }
    }

    @Override
    public List<CombatRequest> drainNativeCombatRequests() {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        return encounter == null ? Collections.<CombatRequest>emptyList()
                : encounter.drainNativeCombatRequests();
    }

    @Override
    public void applyNativeMonsterDamage(ParticipantId targetId, int damage,
            CombatAction action, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ) {
        applyNativeMonsterDamage(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                targetId, damage, action, originX, originY, originZ,
                impactX, impactY, impactZ);
    }

    @Override
    public void applyNativeMonsterDamage(String monsterId, ParticipantId targetId, int damage,
            CombatAction action, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.applyNativeMonsterDamage(currentHostTick(), monsterId, targetId,
                    damage, action, originX, originY, originZ,
                    impactX, impactY, impactZ, nativeCombatOutput);
        }
    }

    @Override
    public void recordNativeMonsterAttacker(String monsterId, ParticipantId attackerId) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.recordNativeMonsterAttacker(currentHostTick(), monsterId, attackerId);
        }
    }

    @Override public boolean canApplyNativeParticipantEffect(ParticipantId participant) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        return !isSessionPaused() && encounter != null
                && encounter.canApplyNativeParticipantEffect(participant);
    }

    @Override public void applyNativeParticipantDamage(String sourceId, ParticipantId participant,
            int amount, float x, float y, float z) {
        applyNativeParticipantDamage(sourceId, participant, amount, x, y, z, DeathCause.COMBAT);
    }

    @Override public void applyNativeParticipantDamage(String sourceId, ParticipantId participant,
            int amount, float x, float y, float z, DeathCause cause) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null && !isSessionPaused()) encounter.applyNativeParticipantDamage(
                currentHostTick(), sourceId, participant, amount, x, y, z, cause, nativeCombatOutput);
    }

    @Override
    public void applyNativeEnvironmentalDamage(String sourceId, ParticipantId targetId,
            int damage, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.applyNativeEnvironmentalDamage(currentHostTick(), sourceId,
                    targetId, damage, originX, originY, originZ,
                    impactX, impactY, impactZ, nativeCombatOutput);
        }
    }

    @Override
    public void publishNativePresentation(String sourceId, String targetId,
            CombatAction action, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.publishNativePresentation(currentHostTick(), sourceId, targetId,
                    action, originX, originY, originZ, impactX, impactY, impactZ,
                    stateChanged, nativeCombatOutput);
        }
    }

    @Override
    public void publishNativePresentation(String sourceId, String targetId,
            CombatAction action, CombatPresentationPhase phase,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) {
            encounter.publishNativePresentation(currentHostTick(), sourceId, targetId,
                    action, phase, originX, originY, originZ,
                    impactX, impactY, impactZ, stateChanged, nativeCombatOutput);
        }
    }

    @Override
    public void publishNativePresentation(String sourceId, String targetId,
            CombatAction action, CombatPresentationPhase phase,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged,
            ProjectileVisual visual) {
        AuthoritativeCombatEncounter encounter = combatEncounter;
        if(encounter != null) encounter.publishNativePresentation(currentHostTick(), sourceId, targetId,
                action, phase, originX, originY, originZ, impactX, impactY, impactZ,
                stateChanged, visual, nativeCombatOutput);
    }

    private long currentHostTick() {
        return authoritativeHostTick;
    }

    private void publishCombatEvent(HostSessionEvent event) {
        // Encounter can emit while holding its monitor; never make admission acquire it back.
        if(event instanceof CombatStateEvent) {
            CombatSnapshot incoming = ((CombatStateEvent)event).getSnapshot();
            CombatSnapshot previous;
            do {
                previous = publishedCombatSnapshot.get();
                // Tick output is buffered; a newer native damage event may publish first.
                if(previous != null && incoming.getSequence() < previous.getSequence()) return;
            } while(!publishedCombatSnapshot.compareAndSet(previous, incoming));
        }
        synchronized(this) {
            if(event instanceof CombatStateEvent) {
                CombatSnapshot snapshot = ((CombatStateEvent)event).getSnapshot();
                if(snapshot.getSequence() < publishedCombatSnapshot.get().getSequence()) return;
                synchronizePartyHealth(snapshot);
                broadcastCombatState(snapshot);
            }
            else if(event instanceof CombatPresentationEvent) {
                CombatPresentationEvent presentation = (CombatPresentationEvent)event;
                if(combatPresentations.add(presentation)) {
                    broadcastCombatPresentation(presentation);
                }
            }
        }
    }

    @Override
    public DirectConnectStatus getStatus() {
        return status;
    }

    public int getBoundPort() {
        return boundPort;
    }

    /** Failed reconstruction must not replace last durable state with partial startup state. */
    public void abortCampaignRecovery(String reason) {
        if(!closing.compareAndSet(false, true)) return;
        List<Channel> participants;
        synchronized(this) { participants = new ArrayList<Channel>(connections.keySet()); }
        for(Channel participant : participants) {
            if(participant.isActive()) participant.writeAndFlush(
                    new ServerDisconnect("Host recovery failed; wait for original Host."))
                    .awaitUninterruptibly(1000L);
        }
        if(sessionStarted) campaignSaveStore.endSession(roster.getCampaignId(), false);
        closeResources();
        removeRecoveryShutdownHook();
        status = status(DirectConnectPhase.FAILED, reason, null, null);
    }

    private void removeRecoveryShutdownHook() {
        if(recoveryShutdownHook == null) return;
        try { Runtime.getRuntime().removeShutdownHook(recoveryShutdownHook); }
        catch(IllegalStateException shuttingDown) { /* JVM shutdown already started. */ }
    }

    private static ParticipantId participantId(CampaignSlot slot) {
        return new ParticipantId("campaign-slot-" + slot.getNumber());
    }

    private static NetworkEntityId entityId(CampaignSlot slot) {
        return new NetworkEntityId(slot.getNumber());
    }

    @Override
    public String getRole() {
        return "Host";
    }

    @Override
    public String getEndpoint() {
        return "0.0.0.0:" + boundPort + " (TCP + UDP)";
    }

    @Override
    public void close() {
        if(!closing.compareAndSet(false, true)) return;
        RuntimeException persistenceFailure = null;
        if(sessionStarted && !confirmedSavePrepared) {
            try { persistCampaign(); }
            catch(RuntimeException failure) { persistenceFailure = failure; }
        }
        if(sessionStarted) {
            try { campaignSaveStore.endSession(roster.getCampaignId(), persistenceFailure == null); }
            catch(RuntimeException failure) { if(persistenceFailure == null) persistenceFailure = failure; }
        }
        List<Channel> participants;
        synchronized(this) {
            participants = new ArrayList<Channel>(connections.keySet());
        }
        for(Channel participant : participants) {
            if(participant.isActive()) {
                participant.writeAndFlush(new ServerDisconnect(persistenceFailure == null
                        ? "Host saved and closed session." : "Host lost session; crash recovery required."))
                        .awaitUninterruptibly(1000L);
            }
        }
        closeResources();
        removeRecoveryShutdownHook();
        status = persistenceFailure == null
                ? status(DirectConnectPhase.CLOSED, "Host session closed.", null, null)
                : status(DirectConnectPhase.FAILED,
                        "Host closed, but Campaign Save failed: "
                                + safeMessage(persistenceFailure), null, null);
        if(persistenceFailure != null) throw persistenceFailure;
    }

    /** Durable cold-resume checkpoint. Does not replay transient commands or cues. */
    public synchronized CampaignSave persistCampaign() {
        if(campaignSaveStore.isArchived(roster.getCampaignId())) return durableCampaign;
        CampaignSave saved = captureCampaign();
        campaignSaveStore.save(saved);
        durableCampaign = saved;
        if(saved.getOutcome() == CampaignSave.Outcome.ACTIVE) campaignSaveStore.snapshot(saved);
        return saved;
    }

    private synchronized CampaignSave captureCampaign() {
        if(!sessionStarted || movementSimulation == null || combatEncounter == null
                || partyStatus == null) {
            throw new IllegalStateException("Campaign play must start before it can be saved.");
        }
        // Render-thread capture synchronizes final Party facts before outcome is chosen.
        byte[] nativeFloor = captureNativeFloor();
        List<CampaignSave.ParticipantState> participants =
                new ArrayList<CampaignSave.ParticipantState>();
        for(CampaignSlot slot : roster.getSlots()) {
            CampaignSave.ParticipantState previous = durableCampaign == null ? null
                    : durableCampaign.getParticipant(slot.getNumber());
            MovementEntityDescriptor descriptor = descriptorForSlot(slot.getNumber());
            PartyMemberStatus party = partyMember(slot.getNumber());
            if(descriptor == null && previous != null) {
                participants.add(previous);
                continue;
            }
            if(party == null && lateParticipants.contains(slot.getLauncherIdentity())) {
                party = new PartyMemberStatus(slot.getNumber(), null, slot.getPresentation().getNickname(),
                        slot.getPresentation().getAvatarId(), 0, PartyMemberStatus.DEFAULT_HEALTH, 0,
                        PartyMemberState.DISCONNECTED);
            }
            if(party == null) party = PartyMemberStatus.initial(slot, descriptor, startingLives);
            MovementEntityState movement = descriptor == null ? null
                    : movementSimulation.getState(descriptor.getParticipantId());
            com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress progress =
                    economy.get(participantId(slot));
            boolean holdingOrb = previous != null && previous.isHoldingOrb();
            participants.add(new CampaignSave.ParticipantState(slot.getNumber(), party,
                    movement, progress, holdingOrb, getPersonalKnowledge(participantId(slot))));
        }
        CombatSnapshot combat = mergeAbsentCombat(publishedCombatSnapshot.get());
        CampaignSave saved = new CampaignSave(compatibility, roster.getCampaignId(),
                roster.getCapacity(), startingLives, partyWiped
                        ? CampaignSave.Outcome.DEFEATED : partyProgression.victory
                                ? CampaignSave.Outcome.COMPLETED : CampaignSave.Outcome.ACTIVE,
                sharedFloorId(), sharedFloorSeed, hostFloorFingerprint,
                nativeWorldGeneration, roster.getSlots(), participants, itemWorld.snapshot(),
                combat, getDoorSnapshots(), getBreakableSnapshots(), savedActorEffects(combat),
                new ArrayList<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>(
                        nativeMonsterSpawns),
                new ArrayList<String>(consumedMonsterSpawners), nativeFloor,
                itemWorld.getPartyKeys(), itemWorld.getKeyRevision(), partyProgression, potionMapping)
                .withFloorHistory(activeAreaKey, new ArrayList<>(dormantFloors.values()), remainingDropTimers());
        saved = saved.withScatterOwners(currentScatterOwners(saved)).withCampaignName(roster.getCampaignName());
        lastCapturedCampaign = saved;
        return saved;
    }

    private Map<Long, Integer> currentScatterOwners(CampaignSave saved) {
        Map<Long, PhysicalItemState> items = new java.util.HashMap<>();
        for(PhysicalItemState item : saved.getPhysicalItems()) items.put(item.entityId, item);
        for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor : saved.getDormantFloors())
            for(PhysicalItemState item : floor.getWorldItems()) items.put(item.entityId, item);
        Map<Long, Integer> owners = new LinkedHashMap<>();
        for(Map.Entry<Long, Integer> entry : scatterOwners.entrySet()) {
            PhysicalItemState item = items.get(entry.getKey());
            if(item != null && item.owner == null && !item.consumed) owners.put(entry.getKey(), entry.getValue());
        }
        return owners;
    }

    private long nextRecoveryNanos;
    private long savedRecoveryGeneration = -1L;
    private String campaignSaveError;
    private boolean confirmedSavePrepared;

    /** Called after native bridges synchronize, even during Party pause. Wall clock cadence. */
    public synchronized void updateCampaignRecovery(long nowNanos) {
        if(!sessionStarted || closing.get() || campaignSaveStore.isArchived(roster.getCampaignId())) return;
        if(!partyWiped && savedRecoveryGeneration == nativeWorldGeneration
                && nowNanos < nextRecoveryNanos) return;
        nextRecoveryNanos = nowNanos + TimeUnit.MINUTES.toNanos(1L);
        savedRecoveryGeneration = nativeWorldGeneration;
        try {
            CampaignSave saved = captureCampaign();
            if(partyWiped) campaignSaveStore.save(saved);
            else campaignSaveStore.snapshot(saved);
            durableCampaign = saved;
            campaignSaveError = null;
        }
        catch(RuntimeException failure) {
            campaignSaveError = "Campaign protection failed: " + safeMessage(failure);
            if(com.badlogic.gdx.Gdx.app != null) com.badlogic.gdx.Gdx.app.error("DelverMultiplayer", campaignSaveError);
        }
    }

    public synchronized String getCampaignSaveError() { return campaignSaveError; }

    /** Explicit confirmation path: failed save keeps session alive and marker dirty. */
    public void saveAndQuit() {
        boolean wasPaused = isSessionPaused();
        setSessionPaused(true);
        try { persistCampaign(); }
        catch(RuntimeException failure) {
            setSessionPaused(wasPaused);
            throw failure;
        }
        confirmedSavePrepared = true;
        close();
    }

    /**
     * Status effects of Participants and of Monsters the saved combat state holds. A Monster
     * whose effects were recorded but that never got a combat slot has nothing to resume, and
     * must never cost the Campaign its save.
     */
    private List<ActorEffectsSnapshot> savedActorEffects(CombatSnapshot combat) {
        List<ActorEffectsSnapshot> saved = new ArrayList<ActorEffectsSnapshot>();
        for(ActorEffectsSnapshot effects : getActorEffects()) {
            if(effects.monsterId.startsWith("participant:")
                    || combat != null && combat.getCombatant(effects.monsterId) != null) saved.add(effects);
        }
        return saved;
    }

    private volatile com.interrupt.dungeoneer.multiplayer.floor.PartyDestination partyDestination;
    private final java.util.Set<Integer> destinationReadySlots = new java.util.HashSet<>();
    @Override public com.interrupt.dungeoneer.multiplayer.floor.PartyDestination getPartyDestination() { return partyDestination; }
    @Override public synchronized void acknowledgePartyDestination(long generation) {
        destinationReady(1, generation);
    }
    private synchronized void destinationReady(int slot, long generation) {
        if(travel.phase != com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING
                || partyDestination == null || generation != nativeWorldGeneration) return;
        destinationReadySlots.add(slot);
        finishPartyDestinationIfReady();
    }
    private void finishPartyDestinationIfReady() {
        if(!destinationReadySlots.contains(1)) return;
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot != null && connection.channel.isActive() && connection.reconnectGrace == null
                    && (connection.movementDescriptor != null || connection.spectatorReady)
                    && !destinationReadySlots.contains(connection.slot.getNumber())) return;
        }
        nativeWorldStable = true;
        clearPartyTravel();
        setSessionPaused(false);
    }

    /** Destination save includes all Fresh Returns before any native scene is replaced. */
    public synchronized boolean activatePartyDestination(String areaKey, String floorId, long seed,
            SharedFloorFingerprint fingerprint, java.util.function.Supplier<com.interrupt.dungeoneer.game.Level> build,
            java.util.function.Consumer<com.interrupt.dungeoneer.game.Level> install,
            java.util.function.IntFunction<com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter> starter) {
        if(starter == null) throw new IllegalArgumentException("Native starter factory is required.");
        return activateCampaignFloor(areaKey, floorId, seed, fingerprint, build, install,
                source -> prepareFreshReturns(prepareAbandonedDowned(source), !dormantFloors.containsKey(areaKey), starter), level -> { });
    }

    /** Detached Life-loss plan: nothing live changes until destination replacement succeeds. */
    public synchronized String getActiveAreaKey() { return activeAreaKey; }
    public synchronized boolean activatePartyDestination(String areaKey, String floorId, long seed,
            java.util.function.Supplier<com.interrupt.dungeoneer.game.Level> build,
            java.util.function.Consumer<com.interrupt.dungeoneer.game.Level> arrive,
            java.util.function.Consumer<com.interrupt.dungeoneer.game.Level> install,
            java.util.function.IntFunction<com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter> starter) {
        return activateCampaignFloor(areaKey, floorId, seed, null, build, install,
                source -> prepareFreshReturns(prepareAbandonedDowned(source), !dormantFloors.containsKey(areaKey), starter), arrive);
    }

    private CampaignSave prepareAbandonedDowned(CampaignSave source) {
        List<CampaignSave.ParticipantState> participants = new ArrayList<>();
        List<PhysicalItemState> items = new ArrayList<>(source.getPhysicalItems());
        List<CombatantSnapshot> actors = new ArrayList<>(source.getCombat().getCombatants());
        List<ActorEffectsSnapshot> effects = new ArrayList<>(source.getActorEffects());
        Map<Long, Long> timers = new LinkedHashMap<>(source.getDropTimers());
        Map<Long, Integer> owners = new LinkedHashMap<>(source.getScatterOwners());
        for(CampaignSave.ParticipantState old : source.getParticipants()) {
            PartyMemberStatus member = old.getParty();
            if(member.getEntityId() == null || member.getRemainingLives() == 0
                    || member.getState() != PartyMemberState.DOWNED
                    && lives.getCondition(new ParticipantId("campaign-slot-" + old.getCampaignSlot()))
                            != AuthoritativeLives.Condition.DOWNED) { participants.add(old); continue; }
            ParticipantId id = new ParticipantId("campaign-slot-" + old.getCampaignSlot());
            int remaining = member.getRemainingLives() - 1;
            int health = remaining == 0 ? 0 : Math.max(1, member.getMaximumHealth() / 2);
            DeathDrop drop = deathDrops.get(id);
            MovementEntityState at = old.getMovement();
            if(drop == null) drop = new DeathDrop(at.getX(), at.getY(), at.getZ(), DeathCause.COMBAT);
            List<PhysicalItemState> carried = new ArrayList<>();
            List<ItemKind> kinds = new ArrayList<>();
            List<Integer> conditions = new ArrayList<>();
            long wielded = itemWorld.getWielded(id);
            for(PhysicalItemState item : items) {
                if(!id.equals(item.owner) || item.consumed || !item.equipmentSlot.isEmpty()
                        || item.entityId == wielded) continue;
                carried.add(item); kinds.add(itemWorld.getKind(item.entityId)); conditions.add(item.properties.condition);
            }
            List<DeathDropRules.Verdict> verdicts;
            synchronized(deathDropRandom) { verdicts = DeathDropRules.judge(drop.cause, kinds, conditions, deathDropRandom); }
            int destroyed = 0;
            for(int index = 0; index < carried.size(); index++) {
                PhysicalItemState item = carried.get(index);
                DeathDropRules.Verdict verdict = verdicts.get(index);
                boolean lost = verdict.fate == DeathDropRules.Fate.DESTROYED;
                if(lost) destroyed++;
                float[] position = scatterPosition(drop.x, drop.y, drop.z, DeathDropRules.scatterRadius(drop.cause));
                ItemProperties properties = item.properties;
                items.set(items.indexOf(item), new PhysicalItemState(item.entityId, item.revision + 1L,
                        item.templateId, null, position[0], position[1], position[2],
                        new ItemProperties(verdict.condition, properties.level, properties.suffix, properties.prefix,
                                properties.quantity, properties.potionType), lost));
                if(!lost) {
                    owners.put(item.entityId, old.getCampaignSlot());
                    if(remaining == 0) timers.put(item.entityId, exhaustedDropTicks);
                }
            }
            // Broken worn/held gear follows the same forfeiture rule as ordinary bleedout.
            for(int index = 0; index < items.size(); index++) {
                PhysicalItemState item = items.get(index);
                if(id.equals(item.owner) && item.properties.condition == 0)
                    items.set(index, new PhysicalItemState(item.entityId, item.revision + 1L, item.templateId,
                            null, drop.x, drop.y, drop.z, item.properties, true));
            }
            com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress p = old.getProgress();
            if(p != null) p = new com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress(id, p.revision + 1L,
                    p.gold - DeathDropRules.goldLoss(p.gold, lives.getLifeLosses(id), destroyed), p.experience, p.level,
                    p.attack, p.defense, p.agility, p.speed, p.magic, p.endurance, p.pendingStatChoices,
                    p.maximumHealth, p.inventorySize, p.hotbarSize);
            participants.add(new CampaignSave.ParticipantState(old.getCampaignSlot(),
                    new PartyMemberStatus(old.getCampaignSlot(), member.getEntityId(), member.getNickname(), member.getAvatarId(),
                            health, member.getMaximumHealth(), remaining,
                            isInReconnectGrace(old.getCampaignSlot()) ? PartyMemberState.RECONNECTING
                                    : remaining == 0 ? PartyMemberState.SPECTATING : PartyMemberState.CONNECTED),
                    at, p, false, old.getPersonalKnowledge()));
            String actorId = "participant:" + id.getValue();
            actors.removeIf(actor -> actorId.equals(actor.getId()));
            actors.add(new CombatantSnapshot(actorId, CombatantKind.PARTICIPANT, health, member.getMaximumHealth()));
            effects.removeIf(effect -> actorId.equals(effect.monsterId));
        }
        return travelCharacterSave(source, participants, items, actors, effects, timers).withScatterOwners(owners);
    }

    private CampaignSave travelCharacterSave(CampaignSave source, List<CampaignSave.ParticipantState> participants,
            List<PhysicalItemState> items, List<CombatantSnapshot> actors, List<ActorEffectsSnapshot> effects,
            Map<Long, Long> timers) {
        return new CampaignSave(compatibility, source.getCampaignId(), source.getCapacity(), startingLives,
                source.getOutcome(), source.getFloorId(), source.getFloorSeed(), source.getFloorFingerprint(),
                source.getNativeWorldGeneration(), source.getSlots(), participants, items,
                new CombatSnapshot(source.getCombat().getSequence() + 1L, currentHostTick(), source.getCombat().getMonsters(), actors),
                source.getDoors(), source.getBreakables(), effects, source.getMonsterSpawns(),
                source.getConsumedMonsterSpawners(), source.getNativeFloor(), source.getPartyKeys(), source.getKeyRevision(),
                source.getPartyProgression(), source.getPotionMapping())
                .withFloorHistory(source.getActiveAreaKey(), source.getDormantFloors(), timers);
    }

    private CampaignSave prepareFreshReturns(CampaignSave source, boolean firstArrival,
            java.util.function.IntFunction<com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter> starter) {
        if(!firstArrival) return source;
        List<CampaignSave.ParticipantState> participants = new ArrayList<>();
        List<PhysicalItemState> items = new ArrayList<>(source.getPhysicalItems());
        List<CombatantSnapshot> actors = new ArrayList<>(source.getCombat().getCombatants());
        List<ActorEffectsSnapshot> effects = new ArrayList<>(source.getActorEffects());
        long nextId = nextCampaignItemId(items, source.getDormantFloors());
        for(CampaignSave.ParticipantState old : source.getParticipants()) {
            if(old.getParty().getRemainingLives() > 0) { participants.add(old); continue; }
            int slot = old.getCampaignSlot();
            ParticipantId id = new ParticipantId("campaign-slot-" + slot);
            com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter fresh = starter.apply(slot);
            if(fresh == null || !id.equals(fresh.progress.participantId))
                throw new IllegalArgumentException("Fresh Return starter belongs to another Slot.");
            com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress p = fresh.progress;
            long revision = old.getProgress() == null ? 1L : old.getProgress().revision + 1L;
            p = new com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress(id, revision,
                    0, 0, 1, p.attack, p.defense, p.agility, p.speed, p.magic, p.endurance, 0,
                    p.maximumHealth, p.inventorySize, p.hotbarSize);
            PartyMemberStatus member = old.getParty();
            boolean connected = member.getState() != PartyMemberState.DISCONNECTED;
            MovementEntityState movement = old.getMovement();
            if(connected && movement == null) movement = new MovementEntityState(new NetworkEntityId(slot),
                    nextLifecycleSequence + slot, 0L, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f,
                    com.interrupt.dungeoneer.multiplayer.movement.MovementState.IDLE);
            participants.add(new CampaignSave.ParticipantState(slot,
                    new PartyMemberStatus(slot, connected ? new NetworkEntityId(slot) : null, member.getNickname(),
                            member.getAvatarId(), p.maximumHealth, p.maximumHealth, startingLives,
                            connected ? (isInReconnectGrace(slot)
                                    ? PartyMemberState.RECONNECTING : PartyMemberState.CONNECTED)
                                    : PartyMemberState.DISCONNECTED),
                    movement, p, false, old.getPersonalKnowledge()));
            items.removeIf(item -> id.equals(item.owner));
            for(com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter.Starter item : fresh.kit) {
                if(nextId >= Long.MAX_VALUE - 1L) throw new IllegalArgumentException("Campaign item identity space exhausted.");
                items.add(new PhysicalItemState(nextId++, revision, item.templateId, id, 0f, 0f, 0f,
                        item.properties, false, item.equipmentSlot));
            }
            String actorId = "participant:" + id.getValue();
            actors.removeIf(actor -> actorId.equals(actor.getId()));
            actors.add(new CombatantSnapshot(actorId, CombatantKind.PARTICIPANT, p.maximumHealth, p.maximumHealth));
            effects.removeIf(effect -> actorId.equals(effect.monsterId));
        }
        CampaignSave freshSave = travelCharacterSave(source, participants, items, actors, effects, source.getDropTimers());
        java.util.Set<Long> lost = new java.util.HashSet<>();
        Map<Long, Integer> owners = new LinkedHashMap<>(source.getScatterOwners());
        for(Map.Entry<Long, Integer> entry : source.getScatterOwners().entrySet())
            if(source.getParticipant(entry.getValue()).getParty().getRemainingLives() == 0) {
                lost.add(entry.getKey()); owners.remove(entry.getKey());
            }
        List<PhysicalItemState> kept = new ArrayList<>(freshSave.getPhysicalItems());
        kept.removeIf(item -> lost.contains(item.entityId));
        Map<Long, Long> timers = new LinkedHashMap<>(source.getDropTimers());
        for(Long id : lost) timers.remove(id);
        List<com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState> history = new ArrayList<>();
        for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor : source.getDormantFloors()) {
            List<PhysicalItemState> world = new ArrayList<>(floor.getWorldItems());
            world.removeIf(item -> lost.contains(item.entityId));
            Map<Long, Long> oldTimers = new LinkedHashMap<>(floor.getDropTimers());
            for(Long id : lost) oldTimers.remove(id);
            history.add(new com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState(floor.getAreaKey(),
                    floor.getFloorId(), floor.getSeed(), floor.getFingerprint(),
                    com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.withoutItems(floor.getNativeFloor(), lost),
                    world, floor.getCombat(), floor.getDoors(), floor.getBreakables(), floor.getActorEffects(),
                    floor.getMonsterSpawns(), floor.getConsumedMonsterSpawners(), oldTimers));
        }
        return new CampaignSave(compatibility, source.getCampaignId(), source.getCapacity(), startingLives,
                source.getOutcome(), source.getFloorId(), source.getFloorSeed(), source.getFloorFingerprint(),
                source.getNativeWorldGeneration(), source.getSlots(), participants, kept, freshSave.getCombat(),
                source.getDoors(), source.getBreakables(), effects, source.getMonsterSpawns(), source.getConsumedMonsterSpawners(),
                com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.withoutItems(source.getNativeFloor(), lost),
                source.getPartyKeys(), source.getKeyRevision(), source.getPartyProgression(), source.getPotionMapping())
                .withFloorHistory(source.getActiveAreaKey(), history, timers).withScatterOwners(owners);
    }

    private void installTravelParticipants(CampaignSave source, CampaignSave candidate) {
        List<MovementEntityDescriptor> descriptors = new ArrayList<>(livesDescriptors);
        List<PartyMemberStatus> members = new ArrayList<>();
        for(CampaignSave.ParticipantState saved : candidate.getParticipants()) {
            ParticipantId id = new ParticipantId("campaign-slot-" + saved.getCampaignSlot());
            if(saved.getProgress() != null) economy.restoreParticipant(saved.getProgress());
            PartyMemberStatus member = saved.getParty();
            members.add(member);
            CampaignSave.ParticipantState previous = source.getParticipant(saved.getCampaignSlot());
            if(previous != null && (previous.getParty().getRemainingLives() != member.getRemainingLives()
                    || previous.getParty().getHealth() == 0 && member.getHealth() > 0)) {
                CampaignSlot slot = roster.getSlot(saved.getCampaignSlot());
                lateParticipants.remove(slot.getLauncherIdentity()); lateEntryGenerations.remove(slot.getLauncherIdentity());
                lives.addParticipant(id, startingLives);
                lives.restore(id, member.getRemainingLives(), member.getRemainingLives() == 0
                        ? AuthoritativeLives.Condition.EXHAUSTED : AuthoritativeLives.Condition.STANDING,
                        0, 0, null, Math.max(0, startingLives - member.getRemainingLives()));
                deathDrops.remove(id); revivalAnchors.remove(id);
                if(member.getEntityId() != null) {
                    MovementEntityDescriptor descriptor = descriptorForSlot(saved.getCampaignSlot());
                    if(descriptor == null) {
                        descriptor = new MovementEntityDescriptor(saved.getMovement().getLifecycleSequence(),
                                member.getEntityId(), id, saved.getCampaignSlot(), member.getNickname(), member.getAvatarId());
                        nextLifecycleSequence = Math.max(nextLifecycleSequence, descriptor.getLifecycleSequence());
                        movementSimulation.addParticipant(descriptor); descriptors.add(descriptor);
                        movementReplication.applySpawn(descriptor);
                        broadcast(new EntitySpawn(sessionId, descriptor));
                    }
                    if(member.getRemainingLives() > 0 && member.getState() != PartyMemberState.RECONNECTING)
                        movementSimulation.resumeParticipant(id);
                    else movementSimulation.freezeParticipant(id);
                    for(RemoteConnection connection : connections.values()) {
                        if(connection.slot == null || connection.slot.getNumber() != saved.getCampaignSlot()) continue;
                        connection.movementDescriptor = descriptor; connection.lateParticipant = false;
                        connection.spectatorReady = false;
                    }
                }
            }
        }
        livesDescriptors = descriptors;
        partyStatus = new PartyStatusSnapshot(++nextPartyStatusSequence, members);
        broadcastPartyStatus(); publishEconomy();
    }

    /**
     * Render-thread floor-preservation boundary for #27. Caller holds Party pause and installs
     * destination native bridges through install; pause stays held for observer readiness.
     * Returns true only on first arrival. Visited areas always restore their own native graph.
     */
    public synchronized boolean activateCampaignFloor(String areaKey, String floorId, long seed,
            SharedFloorFingerprint fingerprint,
            java.util.function.Supplier<com.interrupt.dungeoneer.game.Level> build,
            java.util.function.Consumer<com.interrupt.dungeoneer.game.Level> install) {
        return activateCampaignFloor(areaKey, floorId, seed, fingerprint, build, install, source -> source, level -> { });
    }

    private boolean activateCampaignFloor(String areaKey, String floorId, long seed,
            SharedFloorFingerprint fingerprint,
            java.util.function.Supplier<com.interrupt.dungeoneer.game.Level> build,
            java.util.function.Consumer<com.interrupt.dungeoneer.game.Level> install,
            java.util.function.UnaryOperator<CampaignSave> prepare,
            java.util.function.Consumer<com.interrupt.dungeoneer.game.Level> arrive) {
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState.requireAreaKey(areaKey);
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState.requireAreaKey(floorId);
        if(!sessionStarted || !isSessionPaused() || closing.get() || partyWiped || partyProgression.victory)
            throw new IllegalStateException("Party must be paused in a live Campaign before floor activation.");
        if(areaKey.equals(activeAreaKey) || build == null || install == null || seed == 0L)
            throw new IllegalArgumentException("Destination area/build/install is invalid.");
        floorActivationTickFence = movementSession.discardPendingCommands();
        authoritativeHostTick = floorActivationTickFence;
        CampaignSave originalSource = captureCampaign();
        CampaignSave source = prepare.apply(originalSource);
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState dormant =
                com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState.fromCampaign(activeAreaKey, source);
        com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState destination = null;
        for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState saved : source.getDormantFloors())
            if(saved.getAreaKey().equals(areaKey)) destination = saved;
        boolean firstArrival = destination == null;
        if(destination != null && !floorId.equals(destination.getFloorId()))
            throw new IllegalArgumentException("Visited area cannot change its native content definition.");
        com.interrupt.dungeoneer.game.Level level = destination == null ? build.get()
                : com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(destination.getNativeFloor());
        arrive.accept(level);
        if(fingerprint == null && destination == null) fingerprint = level.multiplayerBuiltFingerprint;
        if(destination == null) destination = new com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState(
                areaKey, floorId, seed, fingerprint,
                com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(level),
                Collections.<PhysicalItemState>emptyList(), null,
                Collections.<DoorSnapshot>emptyList(), Collections.<BreakableSnapshot>emptyList(),
                Collections.<ActorEffectsSnapshot>emptyList(),
                Collections.<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>emptyList(),
                Collections.<String>emptyList(), Collections.<Long, Long>emptyMap());
        LevelMovementCollisionWorld collision = new LevelMovementCollisionWorld(level);
        List<CampaignSave.ParticipantState> participants = new ArrayList<>();
        for(CampaignSave.ParticipantState participant : source.getParticipants()) {
            MovementEntityState old = participant.getMovement();
            MovementEntityState arrival = old;
            if(old != null) {
                MovementSpawn point = collision.getSpawn(participant.getCampaignSlot());
                arrival = new MovementEntityState(old.getEntityId(), old.getLifecycleSequence(),
                        old.getLastProcessedInputTick(), point.getX(), point.getY(), point.getZ(),
                        0f, 0f, 0f, point.getRotation(), com.interrupt.dungeoneer.multiplayer.movement.MovementState.IDLE,
                        old.getLookY());
            }
            participants.add(new CampaignSave.ParticipantState(participant.getCampaignSlot(),
                    participant.getParty(), arrival, participant.getProgress(), participant.isHoldingOrb(),
                    participant.getPersonalKnowledge()));
        }
        List<PhysicalItemState> items = new ArrayList<>(destination.getWorldItems());
        for(PhysicalItemState item : source.getPhysicalItems()) if(item.owner != null) items.add(item);
        List<CombatantSnapshot> combatants = new ArrayList<>();
        for(CombatantSnapshot actor : source.getCombat().getCombatants())
            if(actor.getKind() == CombatantKind.PARTICIPANT) combatants.add(actor);
        if(destination.getCombat() != null) combatants.addAll(destination.getCombat().getCombatants());
        CombatSnapshot combat = new CombatSnapshot(source.getCombat().getSequence() + 1L,
                currentHostTick(), destination.getCombat() == null
                        ? Collections.<com.interrupt.dungeoneer.multiplayer.combat.MonsterSnapshot>emptyList()
                        : destination.getCombat().getMonsters(), combatants);
        List<ActorEffectsSnapshot> effects = new ArrayList<>(destination.getActorEffects());
        for(ActorEffectsSnapshot effect : source.getActorEffects())
            if(effect.monsterId.startsWith("participant:")) effects.add(effect);
        Map<String, com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState> history = new LinkedHashMap<>();
        for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState saved : source.getDormantFloors()) history.put(saved.getAreaKey(), saved);
        history.put(activeAreaKey, dormant); history.remove(areaKey);
        com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot destinationProgression = source.getPartyProgression();
        if(firstArrival && level.multiplayerBuiltProgression != null) {
            com.interrupt.dungeoneer.game.Progression built = new com.interrupt.dungeoneer.game.Progression();
            level.multiplayerBuiltProgression.applyTo(built);
            destinationProgression = com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(
                    built, destinationProgression.revision + 1L);
        }
        CampaignSave candidate = new CampaignSave(compatibility, roster.getCampaignId(), roster.getCapacity(),
                startingLives, CampaignSave.Outcome.ACTIVE, floorId, destination.getSeed(), destination.getFingerprint(),
                nativeWorldGeneration + 1L, roster.getSlots(), participants, items, combat,
                destination.getDoors(), destination.getBreakables(), effects, destination.getMonsterSpawns(),
                destination.getConsumedMonsterSpawners(), com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(level), source.getPartyKeys(),
                source.getKeyRevision(), destinationProgression, source.getPotionMapping())
                .withFloorHistory(areaKey, new ArrayList<>(history.values()), destination.getDropTimers())
                .withScatterOwners(source.getScatterOwners());
        long nextItemId = nextCampaignItemId(items, candidate.getDormantFloors());
        // Atomic disk replacement precedes live mutation: refused writes leave source running intact.
        campaignSaveStore.save(candidate);
        scatterOwners.clear(); scatterOwners.putAll(candidate.getScatterOwners());
        partyProgression = candidate.getPartyProgression();
        broadcast(new DirectConnectWire.PartyProgressionMessage(sessionId, partyProgression));
        activeAreaKey = areaKey; activeFloorId = floorId;
        sharedFloorSeed = destination.getSeed(); hostFloorFingerprint = destination.getFingerprint();
        savedFloorFingerprint = destination.getFingerprint();
        dormantFloors.clear(); dormantFloors.putAll(history);
        movementWorld = collision;
        partyPortals.clear();
        installTravelParticipants(originalSource, candidate);
        itemWorld.installCampaignItems(source.getPhysicalItems(), nextItemId);
        movementSimulation.activateFloor(collision);
        resumeNativeWorldPending = false;
        beginNativeWorld();
        resumeNativeWorldPending = true; // Native bridge attachment preserves installed destination ledger.
        doorSnapshots.clear();
        for(DoorSnapshot door : destination.getDoors()) doorSnapshots.put(door.entityId, door);
        breakableSnapshots.clear();
        for(BreakableSnapshot object : destination.getBreakables()) breakableSnapshots.put(object.entityId, object);
        for(ActorEffectsSnapshot effect : effects) monsterEffects.put(effect.monsterId, effect);
        nativeMonsterSpawns.addAll(destination.getMonsterSpawns());
        restoredMonsterSpawns.addAll(destination.getMonsterSpawns());
        consumedMonsterSpawners.addAll(destination.getConsumedMonsterSpawners());
        pendingItemRequests.clear();
        expiringDrops.clear();
        for(Map.Entry<Long, Long> timer : destination.getDropTimers().entrySet())
            expiringDrops.put(timer.getKey(), currentHostTick() + timer.getValue());
        combatEncounter.activateFloor(collision, combat);
        publishedCombatSnapshot.set(combatEncounter.getSnapshot(currentHostTick()));
        itemWorld.activateFloor(destination.getWorldItems(), nextItemId);
        publishedItemRevisions.clear();
        durableCampaign = candidate; lastCapturedCampaign = candidate;
        restoredNativeFloor = null;
        status = status(DirectConnectPhase.READY, "Party floor installed; reconstruction pause held.", null, floorId);
        nativeFloorCapture = () -> com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(level);
        try { install.accept(level); }
        catch(RuntimeException failure) {
            failNativePresentation("Destination floor installation failed: " + safeMessage(failure));
            throw failure;
        }
        // Publish current destination state even while reconstruction pause stops Host ticks.
        publishPhysicalItems();
        broadcastCombatState(combatEncounter.getSnapshot(currentHostTick()));
        for(DoorSnapshot door : doorSnapshots.values())
            broadcast(new DirectConnectWire.DoorStateMessage(sessionId, door));
        for(BreakableSnapshot object : breakableSnapshots.values())
            broadcast(new DirectConnectWire.BreakableStateMessage(sessionId, object));
        for(ActorEffectsSnapshot effect : monsterEffects.values()) publishMonsterEffects(effect, currentHostTick());
        if(travel.phase == com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.Phase.LOADING) {
            destinationReadySlots.clear();
            partyDestination = new com.interrupt.dungeoneer.multiplayer.floor.PartyDestination(nativeWorldGeneration,
                    areaKey, floorId, sharedFloorSeed, level.multiplayerNativeRecipe == null ? "" : level.multiplayerNativeRecipe, level.multiplayerArrival);
            broadcast(new DirectConnectWire.TravelDestination(sessionId, partyDestination));
        }
        return firstArrival;
    }

    private Map<Long, Long> remainingDropTimers() {
        Map<Long, Long> remaining = new LinkedHashMap<>();
        long tick = currentHostTick();
        for(Map.Entry<Long, Long> expiry : expiringDrops.entrySet()) {
            PhysicalItemState item = itemWorld.get(expiry.getKey());
            if(item != null && item.owner == null && !item.consumed)
                remaining.put(expiry.getKey(), Math.max(0L, expiry.getValue() - tick));
        }
        return remaining;
    }

    private static long nextCampaignItemId(List<PhysicalItemState> active,
            List<com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState> dormant) {
        long next = 1L;
        for(PhysicalItemState item : active) next = nextItemId(next, item);
        for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor : dormant)
            for(PhysicalItemState item : floor.getWorldItems()) next = nextItemId(next, item);
        return next;
    }

    private static long nextItemId(long next, PhysicalItemState item) {
        if(item.entityId >= Long.MAX_VALUE - 1L)
            throw new IllegalArgumentException("Campaign item identity space exhausted.");
        return Math.max(next, item.entityId + 1L);
    }

    /** Render-thread writer of the live Active Floor, set once Host has entered it. */
    public synchronized void setNativeFloorCapture(java.util.function.Supplier<byte[]> capture) {
        nativeFloorCapture = capture;
    }

    /** Saved Active Floor to load like a single-player save, once; null when it must be rebuilt. */
    public synchronized byte[] takeRestoredNativeFloor() {
        byte[] floor = restoredNativeFloor;
        restoredNativeFloor = null;
        return floor;
    }

    public SharedFloorFingerprint getSavedFloorFingerprint() { return savedFloorFingerprint; }

    /** Live floor once Host has entered it; before that the last checkpoint, never dropped. */
    private byte[] captureNativeFloor() {
        java.util.function.Supplier<byte[]> capture = nativeFloorCapture;
        if(capture == null) return durableCampaign == null ? null : durableCampaign.getNativeFloor();
        byte[] floor = capture.get();
        if(floor == null) throw new IllegalStateException("Active Floor capture returned no native state; previous save retained.");
        return floor;
    }

    private MovementEntityDescriptor descriptorForSlot(int campaignSlot) {
        for(MovementEntityDescriptor descriptor : livesDescriptors) {
            if(descriptor.getCampaignSlot() == campaignSlot) return descriptor;
        }
        return null;
    }

    private PartyMemberStatus partyMember(int campaignSlot) {
        if(partyStatus == null) return null;
        for(PartyMemberStatus member : partyStatus.getMembers()) {
            if(member.getCampaignSlot() == campaignSlot) return member;
        }
        return null;
    }

    private CombatSnapshot mergeAbsentCombat(CombatSnapshot current) {
        if(durableCampaign == null || durableCampaign.getCombat() == null) return current;
        List<CombatantSnapshot> merged = new ArrayList<CombatantSnapshot>(current.getCombatants());
        for(CombatantSnapshot previous : durableCampaign.getCombat().getCombatants()) {
            if(previous.getKind() != CombatantKind.PARTICIPANT
                    || current.getCombatant(previous.getId()) != null) continue;
            merged.add(previous);
        }
        return new CombatSnapshot(Math.max(current.getSequence(),
                durableCampaign.getCombat().getSequence()), current.getHostTick(),
                current.getMonsters(), merged);
    }

    private void closeResources() {
        ScheduledFuture<?> ticks = movementTask;
        if(ticks != null) ticks.cancel(false);
        movementTask = null;
        movementSession = null;
        movementSimulation = null;
        List<Channel> participants;
        synchronized(this) {
            participants = new ArrayList<Channel>(connections.keySet());
            connections.clear();
            reconnectingParticipants.clear();
        }
        for(Channel participant : participants) closeChannel(participant);
        closeChannel(udpListener);
        closeChannel(tcpListener);
        networkGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly();
        acceptGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static void closeChannel(Channel channel) {
        if(channel != null) channel.close().awaitUninterruptibly(2000L);
    }

    private DirectConnectStatus status(DirectConnectPhase phase, String message,
            String participantId, String floorId) {
        return new DirectConnectStatus(phase, message, sessionId, participantId, floorId);
    }

    private volatile String ownedFloorId;

    /** Development floor override chosen before play; clients load it from their own copy. */
    public void useOwnedFloor(String floorId) {
        boolean tutorial = GameApplication.OWNED_TUTORIAL_FLOOR.equals(floorId);
        if(compatibility.usesOpenSourceTestContent()
                || (!tutorial && !GameApplication.isOwnedLevelFloor(floorId))) {
            throw new IllegalArgumentException(
                    "Owned floor requires Owned Game Copy tutorial content or a levels/*.bin path.");
        }
        if(sessionStarted) throw new IllegalStateException("Shared floor must be chosen before play starts.");
        if(durableCampaign != null && !durableCampaign.getFloorId().equals(floorId)) {
            throw new IllegalStateException("Campaign resumes saved floor "
                    + durableCampaign.getFloorId() + ", not requested floor " + floorId + ".");
        }
        ownedFloorId = tutorial ? null : floorId;
        if(durableCampaign == null) activeAreaKey = floorId;
    }

    private String sharedFloorId() {
        if(activeFloorId != null) return activeFloorId;
        if(compatibility.usesOpenSourceTestContent()) return GameApplication.OPEN_SOURCE_TEST_LEVEL;
        return ownedFloorId == null ? GameApplication.OWNED_TUTORIAL_FLOOR : ownedFloorId;
    }

    private String sharedFloorName() {
        if(compatibility.usesOpenSourceTestContent()) return "shared open-source test floor";
        return ownedFloorId == null ? "shared Owned Game Copy tutorial"
                : "shared Owned Game Copy floor " + ownedFloorId;
    }

    private static boolean sameAddress(InetSocketAddress tcpAddress,
            InetSocketAddress udpAddress) {
        if(tcpAddress == null || udpAddress == null) return false;
        InetAddress tcp = tcpAddress.getAddress();
        InetAddress udp = udpAddress.getAddress();
        return tcp != null && tcp.equals(udp);
    }

    private static long nextNonZeroLong(SecureRandom random) {
        long value;
        do {
            value = random.nextLong();
        }
        while(value == 0L);
        return value;
    }

    private static String newSessionId(SecureRandom random) {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for(int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static String safeMessage(Throwable failure) {
        if(failure == null) return "unknown network failure";
        Throwable current = failure;
        while(current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if(message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        return boundedReason(message);
    }

    private static String boundedReason(String reason) {
        if(reason == null || reason.trim().isEmpty()) return "No reason supplied.";
        String trimmed = reason.trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    private final class MovementTransport implements HostSessionTransport {
        @Override
        public void publishSnapshot(long hostTick,
                com.interrupt.dungeoneer.multiplayer.host.HostSessionSnapshot snapshot) {
            synchronized(DirectConnectHost.this) {
                if(hostTick <= floorActivationTickFence) return;
                authoritativeHostTick = hostTick;
                if(!(snapshot instanceof MovementSnapshot)
                        || hostTick % SNAPSHOT_INTERVAL_TICKS != 0L) return;
                MovementSnapshot movementSnapshot = (MovementSnapshot)snapshot;
                movementReplication.applySnapshot(movementSnapshot);

                List<RemoteConnection> snapshotConnections = new ArrayList<>(connections.values());
                for(RemoteConnection connection : snapshotConnections) {
                    if(connection.lateParticipant && !connection.spectatorReady) {
                        deliverCurrentState(connection, new MovementSnapshotMessage(sessionId, movementSnapshot, nativeWorldGeneration));
                    }
                }
                for(RemoteConnection connection : snapshotConnections) {
                    if(connection.udpAddress == null || !connection.channel.isActive()
                            || connection.movementDescriptor == null && !connection.spectatorReady) continue;
                    try {
                        ByteBuf encoded = DirectConnectWire.encodeDatagram(udpListener.alloc(),
                                new MovementSnapshotMessage(sessionId, movementSnapshot, nativeWorldGeneration));
                        udpListener.writeAndFlush(new DatagramPacket(encoded,
                                connection.udpAddress));
                    }
                    catch(ProtocolException failure) {
                        failConnection(connection, "Could not encode movement snapshot: "
                                + safeMessage(failure));
                    }
                }
            }
        }

        @Override
        public void publishEvent(long hostTick, HostSessionEvent event) {
            synchronized(DirectConnectHost.this) {
                if(hostTick <= floorActivationTickFence) return;
                authoritativeHostTick = hostTick;
                publishCombatEvent(event);
            }
        }

        @Override
        public void publishDisconnect(long hostTick, HostDisconnectOutcome outcome) { }

        @Override
        public void publishTransition(long hostTick, HostTransitionOutcome outcome) { }
    }

    private static final int MAX_ADMISSION_CHANGES = 4096;

    private static final class RemoteConnection {
        private final Channel channel;
        private final InetSocketAddress tcpAddress;
        private final LauncherIdentity launcherIdentity;
        private SlotClaimRequest request;
        private CampaignSlot slot;
        private InetSocketAddress udpAddress;
        private long udpToken;
        private MovementEntityDescriptor movementDescriptor;
        private GraceParticipant reconnectGrace;
        private ReturnedParticipant returnedParticipant;
        private boolean lateParticipant;
        private boolean spectatorReady;
        private boolean lobbySynchronized;
        private boolean playerReady;
        private long lastLobbySequence, lobbyAuthenticationSequence, minimumReadySequence;
        private long admissionId, admissionGeneration, admissionTick, admissionStartedNanos;
        private int admissionStage;
        private final java.util.ArrayDeque<Message> admissionChanges = new java.util.ArrayDeque<Message>();
        private boolean kicked;
        private SharedFloorFingerprint floorFingerprint;

        private RemoteConnection(Channel channel, InetSocketAddress tcpAddress,
                LauncherIdentity launcherIdentity) {
            this.channel = channel;
            this.tcpAddress = tcpAddress;
            this.launcherIdentity = launcherIdentity;
        }
    }

    private enum PartyMemberStateChange {
        CONNECTED,
        RECONNECTING,
        DISCONNECTED
    }

    private static final class RevivalAnchor {
        /** Intent arrives under Host lock, where combat state is off limits; next tick observes it. */
        private static final int UNOBSERVED_HEALTH = -1;
        private final ParticipantId target;
        private final float x;
        private final float y;
        private int health;

        private RevivalAnchor(ParticipantId target, float x, float y, int health) {
            this.target = target;
            this.x = x;
            this.y = y;
            this.health = health;
        }
    }

    /** Where and from what a Participant went Down; consumed when the Life resolves. */
    private static final class DeathDrop {
        private final float x;
        private final float y;
        private final float z;
        private final DeathCause cause;

        private DeathDrop(float x, float y, float z, DeathCause cause) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.cause = cause == null ? DeathCause.COMBAT : cause;
        }
    }

    private static final class LifeLoss {
        private final ParticipantId participant;
        private final float x, y, z;
        private final DeathCause cause;
        private final boolean exhausted;
        private final int previousLifeLosses;

        private LifeLoss(ParticipantId participant, DeathDrop drop, boolean exhausted,
                int previousLifeLosses) {
            this.participant = participant;
            this.x = drop.x;
            this.y = drop.y;
            this.z = drop.z;
            this.cause = drop.cause;
            this.exhausted = exhausted;
            this.previousLifeLosses = previousLifeLosses;
        }
    }

    private static final class GraceParticipant {
        private final CampaignSlot slot;
        private final MovementEntityDescriptor descriptor;
        private final ReconnectGrace grace;

        private GraceParticipant(CampaignSlot slot, MovementEntityDescriptor descriptor,
                ReconnectGrace grace) {
            if(slot == null || descriptor == null || grace == null) {
                throw new IllegalArgumentException("Reconnect grace participant is incomplete.");
            }
            if(slot.getNumber() != descriptor.getCampaignSlot()
                    || descriptor.getEntityId() == null
                    || !descriptor.getEntityId().equals(grace.getEntityId())) {
                throw new IllegalArgumentException("Reconnect grace identity does not match Campaign Slot.");
            }
            this.slot = slot;
            this.descriptor = descriptor;
            this.grace = grace;
        }
    }

    private static final class ReturnedParticipant {
        private final CampaignSlot slot;
        private final MovementEntityDescriptor descriptor;
        private final boolean coldResume;

        private ReturnedParticipant(CampaignSlot slot, MovementEntityDescriptor descriptor) {
            this(slot, descriptor, false);
        }

        private ReturnedParticipant(CampaignSlot slot, MovementEntityDescriptor descriptor, boolean coldResume) {
            if(slot == null || descriptor == null
                    || slot.getNumber() != descriptor.getCampaignSlot()) {
                throw new IllegalArgumentException("Returned Participant does not match Campaign Slot.");
            }
            this.slot = slot;
            this.descriptor = descriptor;
            this.coldResume = coldResume;
        }
    }

    private final class HostTcpHandler extends SimpleChannelInboundHandler<Message> {
        private boolean helloAccepted;

        @Override
        public void channelActive(final ChannelHandlerContext context) {
            context.executor().schedule(new Runnable() {
                @Override
                public void run() {
                    if(!helloAccepted && context.channel().isActive()) {
                        reject(context, RejectCode.MALFORMED_HANDSHAKE,
                                "Malformed handshake: Client hello timed out.");
                    }
                }
            }, 10L, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, Message message) {
            if(message instanceof ClientHello && !helloAccepted) {
                helloAccepted = acceptHello(context, (ClientHello)message);
            }
            else if(message instanceof DirectConnectWire.LobbyReceived && helloAccepted) {
                lobbyReceived(context, (DirectConnectWire.LobbyReceived)message);
            }
            else if(message instanceof DirectConnectWire.PlayerReady && helloAccepted) {
                playerReady(context, (DirectConnectWire.PlayerReady)message);
            }
            else if(message instanceof SlotClaim && helloAccepted) {
                claimSlot(context, (SlotClaim)message);
            }
            else if(message instanceof DirectConnectWire.TravelReady && helloAccepted) {
                RemoteConnection connection = connections.get(context.channel());
                DirectConnectWire.TravelReady ready = (DirectConnectWire.TravelReady)message;
                if(connection != null && connection.slot != null && connection.udpAddress != null
                        && connection.reconnectGrace == null && sessionId.equals(ready.sessionId))
                    destinationReady(connection.slot.getNumber(), ready.generation);
            }
            else if(message instanceof DirectConnectWire.TravelIntent && helloAccepted) {
                synchronized(DirectConnectHost.this) {
                    RemoteConnection connection = connections.get(context.channel());
                    DirectConnectWire.TravelIntent intent = (DirectConnectWire.TravelIntent)message;
                    if(connection != null && connection.movementDescriptor != null && connection.udpAddress != null
                            && connection.reconnectGrace == null && sessionId.equals(intent.sessionId))
                        travelIntent(connection.movementDescriptor.getParticipantId(), intent.generation, intent.portal);
                }
            }
            else if(message instanceof DirectConnectWire.AdmissionSync && helloAccepted) {
                admissionAcknowledged(context, (DirectConnectWire.AdmissionSync)message);
            }
            else if(message instanceof PartyChatSubmit && helloAccepted && sessionStarted) {
                partyChat(context, (PartyChatSubmit)message);
            }
            else if(message instanceof PauseRequestMessage && helloAccepted && sessionStarted) {
                pauseRequested(context, (PauseRequestMessage)message);
            }
            else if(message instanceof DirectConnectWire.ItemRequestMessage
                    && helloAccepted && sessionStarted) {
                itemAction(context, (DirectConnectWire.ItemRequestMessage)message);
            }
            else if(message instanceof DirectConnectWire.ReviveIntentMessage
                    && helloAccepted && sessionStarted) {
                reviveIntent(context, (DirectConnectWire.ReviveIntentMessage)message);
            }
            else if(message instanceof CombatActionRequestMessage && helloAccepted && sessionStarted) {
                combatAction(context, (CombatActionRequestMessage)message);
            }
            else if(message instanceof DirectConnectWire.SharedFloorFingerprintMessage
                    && helloAccepted && sessionStarted) {
                sharedFloorReported(context, (DirectConnectWire.SharedFloorFingerprintMessage)message);
            }
            else if(message instanceof ClientDisconnect && helloAccepted) {
                clientDisconnected(context, ((ClientDisconnect)message).reason);
            }
            else {
                reject(context, RejectCode.MALFORMED_HANDSHAKE,
                        "Malformed handshake: message is not valid in current session state.");
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            channelClosed(context.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            malformed(context, cause);
        }
    }
}
