package com.interrupt.dungeoneer.multiplayer.network;
import com.badlogic.gdx.Gdx;
import com.interrupt.dungeoneer.multiplayer.items.ItemActionResult;
import com.interrupt.dungeoneer.multiplayer.combat.NativeExplosionPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationCue;
import com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState;
import com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue;
import com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation;

import com.interrupt.dungeoneer.multiplayer.items.DoorFeedback;

import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;

import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationJournal;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CampaignChallenge;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CombatActionRequestMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CombatPresentationMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CombatStateMessage;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientHello;
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
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerAccepted;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SessionReady;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SlotClaim;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SlotPending;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegister;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegistered;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementReplicationState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;

import io.netty.bootstrap.Bootstrap;
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
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct Connect client whose private Launcher Identity claims one persistent Campaign Slot. */
public final class DirectConnectClient implements DirectConnectPeer {
    private com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge personalKnowledge = com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge.empty();
    private static final int MAX_PENDING_MOVEMENT_INPUTS =
            DirectConnectProtocol.MAX_INPUT_FRAMES * 4;

    interface MovementDatagramPolicy {
        int copiesForBundle(List<MovementInputFrame> inputs);
    }

    private static final MovementDatagramPolicy NORMAL_MOVEMENT_DATAGRAMS =
            new MovementDatagramPolicy() {
                @Override
                public int copiesForBundle(List<MovementInputFrame> inputs) {
                    return 1;
                }
            };

    private final String host;
    private final int port;
    private final LauncherIdentity launcherIdentity;
    private final SlotPresentation presentation;
    private final int requestedSlot;
    private final ReconnectTokenStore reconnectTokens;
    private final DirectConnectCompatibility compatibility;
    private final MovementDatagramPolicy movementDatagramPolicy;
    private final EventLoopGroup networkGroup = new NioEventLoopGroup(1);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean resourcesClosing = new AtomicBoolean(false);
    private final MovementReplicationState movementReplication =
            new MovementReplicationState();
    private final List<MovementInputFrame> pendingMovementInputs =
            new ArrayList<MovementInputFrame>();
    private final CombatPresentationJournal combatPresentations =
            new CombatPresentationJournal(DirectConnectProtocol.MAX_COMBAT_PRESENTATION_EVENTS);

    private volatile DirectConnectStatus status;
    private volatile Channel tcpChannel;
    private volatile Channel udpChannel;
    private volatile String sessionId;
    private volatile String campaignId;
    private volatile int campaignCapacity;
    private volatile int campaignSlot;
    private String acceptedReconnectToken;
    private volatile long udpToken;
    private volatile boolean udpRegistered;
    private volatile SessionReady readyMessage;
    private volatile int udpRegistrationAttempts;
    private volatile NetworkEntityId localMovementEntityId;
    private volatile PartyStatusSnapshot partyStatus;
    private volatile CombatSnapshot combatSnapshot;
    private volatile PartyCommunicationState partyCommunication =
            PartyCommunicationState.initial();
    private com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot partyProgression =
            com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.empty();
    private long partyProgressionRevision = -1L;
    private int partyKeys;
    private long keyRevision = -1L;
    private final java.util.Map<ParticipantId, com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress>
            participantProgress = new java.util.LinkedHashMap<ParticipantId,
                    com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress>();
    private final java.util.Map<String, com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState> shopEntries =
            new java.util.LinkedHashMap<String, com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState>();
    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> shopOpenings =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening>();
    private final java.util.Map<String, ActorEffectsSnapshot> monsterEffects =
            new java.util.LinkedHashMap<String, ActorEffectsSnapshot>();
    private final java.util.Map<Long, NativeDynamicState> nativeDynamicStates =
            new java.util.LinkedHashMap<Long, NativeDynamicState>();
    private final java.util.ArrayDeque<NativeDynamicState> nativeDynamicTombstones =
            new java.util.ArrayDeque<NativeDynamicState>();
    private final java.util.Map<Long, PhysicalItemState> physicalItems =
            new java.util.LinkedHashMap<Long, PhysicalItemState>();
    private final java.util.Map<Long, DoorSnapshot> doorSnapshots =
            new java.util.LinkedHashMap<Long, DoorSnapshot>();
    private final java.util.Map<Long, com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot> moverSnapshots =
            new java.util.LinkedHashMap<Long, com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot>();
    private final java.util.Map<Long, BreakableSnapshot> breakableSnapshots =
            new java.util.LinkedHashMap<Long, BreakableSnapshot>();
    private long nextItemRequestId = 1L;
    private long lastSubmittedMovementTick;

    private DirectConnectClient(String host, int port, LauncherIdentity launcherIdentity,
            SlotPresentation presentation, int requestedSlot,
            ReconnectTokenStore reconnectTokens, DirectConnectCompatibility compatibility,
            MovementDatagramPolicy movementDatagramPolicy) {
        if(host == null || host.trim().isEmpty()
                || host.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Direct Connect address must be 1-255 bytes.");
        }
        if(port < 1 || port > 65535) {
            throw new IllegalArgumentException("Direct Connect port must be between 1 and 65535.");
        }
        if(launcherIdentity == null) throw new IllegalArgumentException("Launcher Identity cannot be null.");
        if(presentation == null) throw new IllegalArgumentException("Slot presentation cannot be null.");
        if(requestedSlot < 0 || requestedSlot > 4) {
            throw new IllegalArgumentException("Requested Campaign Slot must be zero (any) or 1-4.");
        }
        if(reconnectTokens == null) throw new IllegalArgumentException("Reconnect token store cannot be null.");
        if(compatibility == null) throw new IllegalArgumentException("Client compatibility cannot be null.");
        if(movementDatagramPolicy == null) {
            throw new IllegalArgumentException("Movement datagram policy cannot be null.");
        }
        this.host = host.trim();
        this.port = port;
        this.launcherIdentity = launcherIdentity;
        this.presentation = presentation;
        this.requestedSlot = requestedSlot;
        this.reconnectTokens = reconnectTokens;
        this.compatibility = compatibility;
        this.movementDatagramPolicy = movementDatagramPolicy;
        status = new DirectConnectStatus(DirectConnectPhase.CONNECTING,
                "Connecting TCP to " + this.host + ":" + port + ".",
                null, null, null);
    }

    public static DirectConnectClient connect(String host, int port,
            LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, ReconnectTokenStore reconnectTokens,
            DirectConnectCompatibility compatibility) {
        DirectConnectClient client = new DirectConnectClient(host, port, launcherIdentity,
                presentation, requestedSlot, reconnectTokens, compatibility,
                NORMAL_MOVEMENT_DATAGRAMS);
        client.start();
        return client;
    }

    /** Native callers acknowledge only after render-thread controllers reconstruct each checkpoint. */
    public static DirectConnectClient connectForNativeWorld(String host, int port,
            LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, ReconnectTokenStore reconnectTokens,
            DirectConnectCompatibility compatibility) {
        DirectConnectClient client = new DirectConnectClient(host, port, launcherIdentity,
                presentation, requestedSlot, reconnectTokens, compatibility, NORMAL_MOVEMENT_DATAGRAMS);
        client.nativeAdmission = true;
        client.start();
        return client;
    }

    static DirectConnectClient connectForTest(String host, int port,
            LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, ReconnectTokenStore reconnectTokens,
            DirectConnectCompatibility compatibility,
            MovementDatagramPolicy movementDatagramPolicy) {
        DirectConnectClient client = new DirectConnectClient(host, port, launcherIdentity,
                presentation, requestedSlot, reconnectTokens, compatibility,
                movementDatagramPolicy);
        client.start();
        return client;
    }

    private void start() {
        if(DirectConnectNetworkSimulation.enabled()) {
            System.out.println("[Network simulation] Client: +110 ms RTT on TCP/UDP; 2% UDP loss each way.");
        }
        Bootstrap tcp = new Bootstrap();
        tcp.group(networkGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        DirectConnectWire.configureTcp(channel.pipeline());
                        if(DirectConnectNetworkSimulation.enabled()) channel.pipeline().addLast(
                                "gateNetworkSimulation", new DirectConnectNetworkSimulation(false));
                        channel.pipeline().addLast("directConnectClient", new ClientTcpHandler());
                    }
                });

        ChannelFuture connect = tcp.connect(host, port);
        tcpChannel = connect.channel();
        connect.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if(!future.isSuccess()) fail(connectFailureReason(future.cause()));
            }
        });
    }

    private String connectFailureReason(Throwable failure) {
        Throwable current = failure;
        while(current != null) {
            String message = current.getMessage();
            if(current instanceof ConnectException && message != null
                    && message.toLowerCase(Locale.ROOT).contains("refused")) {
                return "No Direct Connect Host is listening at " + host + ":" + port
                        + ". Start Host first and check that both ports match.";
            }
            if(current.getCause() == current) break;
            current = current.getCause();
        }
        return "TCP connection failed: " + safeMessage(failure);
    }

    private synchronized void tcpConnected(ChannelHandlerContext context) {
        if(closing.get()) {
            context.close();
            return;
        }
        status = new DirectConnectStatus(DirectConnectPhase.HANDSHAKING,
                "TCP connected. Validating protocol build and normalized content identity.",
                null, null, null);
        context.writeAndFlush(new ClientHello(DirectConnectProtocol.VERSION,
                compatibility.getBuildId(), compatibility.getContentFormat(),
                compatibility.getContentSha256(), launcherIdentity.getValue()));
        context.executor().schedule(new Runnable() {
            @Override
            public void run() {
                DirectConnectPhase phase = status.getPhase();
                if(phase == DirectConnectPhase.HANDSHAKING
                        || phase == DirectConnectPhase.CLAIMING_SLOT
                        || phase == DirectConnectPhase.REGISTERING_UDP) {
                    fail("Direct Connect lobby handshake timed out.");
                }
            }
        }, 10L, TimeUnit.SECONDS);
    }

    private synchronized void campaignChallenge(CampaignChallenge challenge) {
        if(sessionId != null || challenge.sessionId == null || challenge.sessionId.trim().isEmpty()
                || challenge.capacity < 2 || challenge.capacity > 4) {
            fail("Host returned malformed Campaign lobby details.");
            return;
        }
        try {
            CampaignRoster.requireCampaignId(challenge.campaignId);
        }
        catch(IllegalArgumentException ex) {
            fail("Host returned malformed Campaign identity.");
            return;
        }
        sessionId = challenge.sessionId;
        campaignId = challenge.campaignId;
        campaignCapacity = challenge.capacity;

        String reconnectToken;
        try {
            reconnectToken = reconnectTokens.load(campaignId);
        }
        catch(RuntimeException ex) {
            fail("Could not read private Campaign reconnect credential: " + safeMessage(ex));
            return;
        }
        tcpChannel.writeAndFlush(new SlotClaim(sessionId, presentation.getNickname(),
                presentation.getAvatarId(), requestedSlot,
                reconnectToken == null ? "" : reconnectToken));
        status = new DirectConnectStatus(DirectConnectPhase.CLAIMING_SLOT,
                reconnectToken == null
                        ? "Requesting a new Host-approved Campaign Slot."
                        : "Reclaiming persistent Campaign Slot with private reconnect credential.",
                sessionId, "host", null);
    }

    private synchronized void pending(SlotPending pending) {
        if(sessionId == null || !sessionId.equals(pending.sessionId)) {
            fail("Host returned malformed pending approval state.");
            return;
        }
        status = new DirectConnectStatus(DirectConnectPhase.AWAITING_APPROVAL,
                pending.reason, sessionId, "host", null);
    }

    private synchronized void accepted(ServerAccepted accepted) {
        if(sessionId == null || !sessionId.equals(accepted.sessionId)
                || !campaignId.equals(accepted.campaignId) || accepted.udpToken == 0L
                || accepted.slotNumber < 1 || accepted.slotNumber > campaignCapacity) {
            fail("Host returned malformed Campaign Slot acceptance.");
            return;
        }
        if(campaignSlot != 0) {
            if(campaignSlot != accepted.slotNumber || udpToken != accepted.udpToken
                    || !java.util.Objects.equals(acceptedReconnectToken, accepted.reconnectToken)) {
                fail("Host changed Campaign Slot acceptance during admission.");
            }
            return;
        }
        try {
            reconnectTokens.save(campaignId, accepted.reconnectToken);
        }
        catch(RuntimeException ex) {
            fail("Could not store private Campaign reconnect credential: " + safeMessage(ex));
            return;
        }
        campaignSlot = accepted.slotNumber;
        acceptedReconnectToken = accepted.reconnectToken;
        udpToken = accepted.udpToken;
        status = new DirectConnectStatus(DirectConnectPhase.REGISTERING_UDP,
                "Campaign Slot " + campaignSlot
                        + " accepted. Registering UDP on Host's same numeric port.",
                sessionId, "host", null);
        bindUdp();
    }

    private void bindUdp() {
        Bootstrap udp = new Bootstrap();
        udp.group(networkGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    public void handlerAdded(ChannelHandlerContext context) {
                        if(DirectConnectNetworkSimulation.enabled()) context.pipeline().addBefore(
                                context.name(), "gateNetworkSimulation", new DirectConnectNetworkSimulation(true));
                    }

                    @Override
                    protected void channelRead0(ChannelHandlerContext context,
                            DatagramPacket packet) {
                        udpMessage(packet);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                        fail("UDP session failed: " + safeMessage(cause));
                    }
                });

        udp.bind(0).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if(!future.isSuccess()) {
                    fail("Could not open client UDP socket: " + safeMessage(future.cause()));
                    return;
                }
                udpChannel = future.channel();
                sendUdpRegistration();
            }
        });
    }

    private synchronized void sendUdpRegistration() {
        if(udpChannel == null || tcpChannel == null || !tcpChannel.isActive()) return;
        if(udpRegistered) return;
        if(udpRegistrationAttempts >= 20) {
            fail("UDP registration timed out while TCP remained connected.");
            return;
        }
        udpRegistrationAttempts++;
        InetSocketAddress remote = (InetSocketAddress)tcpChannel.remoteAddress();
        try {
            ByteBuf registration = DirectConnectWire.encodeDatagram(udpChannel.alloc(),
                    new UdpRegister(sessionId, udpToken));
            udpChannel.writeAndFlush(new DatagramPacket(registration,
                    new InetSocketAddress(remote.getAddress(), port)));
            udpChannel.eventLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    sendUdpRegistration();
                }
            }, 250L, TimeUnit.MILLISECONDS);
        }
        catch(ProtocolException ex) {
            fail("Could not encode UDP registration: " + safeMessage(ex));
        }
    }

    private synchronized void udpMessage(DatagramPacket packet) {
        if(!fromExpectedHost(packet.sender())) return;
        Message message;
        try {
            message = DirectConnectWire.decodeDatagram(packet.content());
        }
        catch(ProtocolException ignored) {
            return;
        }
        if(message instanceof UdpRegistered) {
            UdpRegistered registered = (UdpRegistered)message;
            if(!sessionId.equals(registered.sessionId) || udpToken != registered.udpToken) return;
            udpRegistered = true;
            if(!admissionPending) status = new DirectConnectStatus(DirectConnectPhase.LOBBY,
                    "Campaign Slot " + campaignSlot
                            + " is ready. Waiting for Host to start play.",
                    sessionId, "host", null);
            becomeReadyIfComplete();
            return;
        }
        if(message instanceof MovementSnapshotMessage) {
            MovementSnapshotMessage movement = (MovementSnapshotMessage)message;
            if(!sessionId.equals(movement.sessionId) || movement.generation != nativeWorldGeneration) return;
            if(floorMovementBaselinePending) return;
            if(movementReplication.applySnapshot(movement.snapshot)) {
                acknowledgeMovementInputs(movement.snapshot);
            }
        }
    }

    private synchronized void acknowledgeMovementInputs(MovementSnapshot snapshot) {
        NetworkEntityId localEntity = localMovementEntityId;
        if(localEntity == null) return;
        MovementEntityState state = snapshot.getEntity(localEntity);
        if(state == null) return;
        long acknowledged = state.getLastProcessedInputTick();
        while(!pendingMovementInputs.isEmpty()
                && pendingMovementInputs.get(0).getInputTick() <= acknowledged) {
            pendingMovementInputs.remove(0);
        }
    }

    private synchronized void sessionReady(SessionReady ready) {
        if(sessionId == null || !sessionId.equals(ready.sessionId)
                || ready.participantCount < 2 || ready.participantCount > campaignCapacity
                || ready.nextCombatRequestId < 1L) {
            fail("Host returned malformed session-ready state.");
            return;
        }
        nextItemRequestId = Math.max(nextItemRequestId, ready.nextItemRequestId);
        readyMessage = ready;
        becomeReadyIfComplete();
    }

    private synchronized void partyStatus(PartyStatusMessage message) {
        if(sessionId == null || !sessionId.equals(message.sessionId)
                || message.snapshot == null) {
            fail("Host returned malformed Party status state.");
            return;
        }
        PartyMemberStatus local = message.snapshot.getMember(campaignSlot);
        if(local == null) {
            fail("Host Party status omitted local Campaign Slot.");
            return;
        }
        if(local.getState() == com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState.DISCONNECTED) {
            if(partyStatus == null || message.snapshot.getSequence() > partyStatus.getSequence()) {
                partyStatus = message.snapshot;
            }
            return;
        }
        boolean bodylessSpectator = local.getState()
                == com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState.SPECTATING
                && local.getEntityId() == null;
        if(!bodylessSpectator && (local.getEntityId() == null
                || !local.getEntityId().equals(localMovementEntityId))) {
            fail("Host Party status omitted local Active Floor Entity.");
            return;
        }
        if(partyStatus == null || message.snapshot.getSequence() > partyStatus.getSequence()) {
            partyStatus = message.snapshot;
        }
        becomeReadyIfComplete();
    }

    private synchronized void combatState(CombatStateMessage message) {
        if(sessionId == null || !sessionId.equals(message.sessionId) || message.snapshot == null) {
            fail("Host returned malformed combat state.");
            return;
        }
        if(combatSnapshot == null || message.snapshot.getSequence() > combatSnapshot.getSequence()) {
            combatSnapshot = message.snapshot;
        }
        becomeReadyIfComplete();
    }

    private synchronized void combatPresentation(CombatPresentationMessage message) {
        if(sessionId == null || !sessionId.equals(message.sessionId) || message.event == null) {
            fail("Host returned malformed combat presentation.");
            return;
        }
        combatPresentations.add(message.event);
    }

    private synchronized void partyChat(PartyChatDelivery delivery) {
        if(sessionId == null || !sessionId.equals(delivery.sessionId)
                || delivery.message == null) {
            fail("Host returned Party chat outside current session.");
            return;
        }
        partyCommunication = partyCommunication.withChat(delivery.message);
    }

    private synchronized void pauseRequested(PauseRequestedMessage delivery) {
        if(!isReadySession(delivery.sessionId)) {
            fail("Host returned Pause request outside current session.");
            return;
        }
        partyCommunication = partyCommunication.withPauseRequest(delivery.request);
    }

    private volatile boolean partyWiped;

    @Override
    public boolean isPartyWiped() { return partyWiped; }

    private long lastNativeMonsterSpawnSequence;
    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> nativeMonsterSpawns =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>();

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> drainNativeMonsterSpawns() {
        List<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> result =
                new ArrayList<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn>(nativeMonsterSpawns);
        nativeMonsterSpawns.clear();
        return result;
    }

    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState> nativeDecals =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState>();
    private static final int MAX_TRIGGER_PRESENTATIONS = 64;
    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation> triggerPresentations =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation>();

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState> drainNativeDecals() {
        List<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState> result =
                new ArrayList<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState>(nativeDecals);
        nativeDecals.clear();
        return result;
    }

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation> drainTriggerPresentations() {
        List<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation> result =
                new ArrayList<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation>(triggerPresentations);
        triggerPresentations.clear();
        return result;
    }

    private synchronized void nativeDecal(DirectConnectWire.NativeDecalMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Native decal belongs to another session."); return; }
        if(message.generation != nativeWorldGeneration) return;
        if(nativeDecals.size() >= DirectConnectHost.MAX_NATIVE_DECALS) nativeDecals.removeFirst();
        nativeDecals.addLast(message.decal);
    }

    private long lastTriggerPresentationSequence;
    private synchronized void triggerPresentation(DirectConnectWire.TriggerPresentationMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Trigger presentation belongs to another session."); return;
        }
        if(message.presentation.generation != nativeWorldGeneration
                || message.presentation.sequence <= lastTriggerPresentationSequence) return;
        lastTriggerPresentationSequence = message.presentation.sequence;
        if(triggerPresentations.size() >= MAX_TRIGGER_PRESENTATIONS) {
            fail("Trigger presentation queue exceeded."); return;
        }
        triggerPresentations.addLast(message.presentation);
    }

    private synchronized void nativeMonsterSpawn(DirectConnectWire.NativeMonsterSpawnMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Monster spawn belongs to another session."); return; }
        if(message.generation != nativeWorldGeneration) return;
        if(message.sequence <= lastNativeMonsterSpawnSequence) return;
        lastNativeMonsterSpawnSequence = message.sequence;
        if(nativeMonsterSpawns.size() >= DirectConnectProtocol.MAX_MONSTERS) {
            fail("Native monster spawn queue exceeded."); return;
        }
        nativeMonsterSpawns.addLast(message.spawn);
    }

    private synchronized void partyWipe(DirectConnectWire.PartyWipeMessage delivery) {
        if(sessionId == null || !sessionId.equals(delivery.sessionId)) {
            fail("Party Wipe belongs to another session.");
            return;
        }
        partyWiped = true;
    }

    private synchronized void pauseSession(PauseSessionStateMessage delivery) {
        if(!isReadySession(delivery.sessionId)) {
            fail("Host returned Pause Session state outside current session.");
            return;
        }
        partyCommunication = partyCommunication.withPauseSession(delivery.state);
    }

    private boolean isReadySession(String messageSessionId) {
        return sessionId != null && sessionId.equals(messageSessionId)
                && campaignSlot != 0;
    }

    private synchronized void entitySpawn(EntitySpawn spawn) {
        if(sessionId == null || !sessionId.equals(spawn.sessionId)
                || spawn.descriptor == null) {
            fail("Host returned malformed Entity spawn state.");
            return;
        }
        if(movementReplication.applySpawn(spawn.descriptor)
                && spawn.descriptor.getCampaignSlot() == campaignSlot) {
            localMovementEntityId = spawn.descriptor.getEntityId();
        }
    }

    private synchronized void entityDespawn(EntityDespawn despawn) {
        if(sessionId == null || !sessionId.equals(despawn.sessionId)) {
            fail("Host returned malformed Entity despawn state.");
            return;
        }
        movementReplication.applyDespawn(despawn.lifecycleSequence, despawn.entityId);
    }

    private void becomeReadyIfComplete() {
        if(admissionPending) return;
        if(!udpRegistered || readyMessage == null || partyStatus == null
                || combatSnapshot == null) return;
        status = new DirectConnectStatus(DirectConnectPhase.READY,
                "Host started play with " + readyMessage.participantCount
                        + " Participants. Entering shared open-source test floor.",
                sessionId, "host", readyMessage.floorId);
    }

    private boolean admissionPending;
    private long admissionId, admissionGeneration;
    private boolean nativeAdmission;
    private long nextAdmissionCheckpoint, pendingAdmissionCheckpoint;
    private volatile com.interrupt.dungeoneer.multiplayer.floor.PartyTransition travel =
            com.interrupt.dungeoneer.multiplayer.floor.PartyTransition.idle(1L, 1L);
    private volatile com.interrupt.dungeoneer.multiplayer.floor.PartyDestination partyDestination;
    @Override public com.interrupt.dungeoneer.multiplayer.floor.PartyDestination getPartyDestination() { return partyDestination; }
    @Override public void acknowledgePartyDestination(long generation) {
        if(canSendReliableSessionEvent()) tcpChannel.writeAndFlush(new DirectConnectWire.TravelReady(sessionId, generation));
    }
    @Override public com.interrupt.dungeoneer.multiplayer.floor.PartyTransition getPartyTransition() { return travel; }
    @Override public synchronized void requestPartyTransition(String portal) {
        if(canSendReliableSessionEvent()) tcpChannel.writeAndFlush(
                new DirectConnectWire.TravelIntent(sessionId, getNativeWorldGeneration(), portal));
    }
    @Override public void cancelPartyTransition() { requestPartyTransition(""); }

    private DirectConnectWire.AdmissionSync pendingAdmissionFence;

    @Override public synchronized long getPendingAdmissionCheckpoint() {
        return pendingAdmissionCheckpoint;
    }

    @Override public synchronized void acknowledgeNativeWorldReadiness(long checkpoint) {
        if(checkpoint == 0L || checkpoint != pendingAdmissionCheckpoint
                || pendingAdmissionFence == null) return;
        DirectConnectWire.AdmissionSync fence = pendingAdmissionFence;
        pendingAdmissionCheckpoint = 0L;
        pendingAdmissionFence = null;
        int stage = fence.stage == DirectConnectWire.AdmissionSync.BASELINE_END
                ? DirectConnectWire.AdmissionSync.ACK_BASELINE : DirectConnectWire.AdmissionSync.ACK_CATCH_UP;
        tcpChannel.writeAndFlush(new DirectConnectWire.AdmissionSync(sessionId,
                fence.admissionId, fence.generation, fence.hostTick, stage));
    }

    @Override public int getLocalCampaignSlot() { return campaignSlot; }

    private synchronized void admissionSync(DirectConnectWire.AdmissionSync sync) {
        if(!sessionId.equals(sync.sessionId)) { fail("Admission belongs to another session."); return; }
        if(sync.stage == DirectConnectWire.AdmissionSync.BEGIN) {
            if(sync.admissionId <= admissionId) return;
            admissionPending = true;
            admissionId = sync.admissionId;
            admissionGeneration = sync.generation;
            pendingAdmissionCheckpoint = 0L;
            pendingAdmissionFence = null;
            return;
        }
        if(sync.admissionId != admissionId || sync.generation != admissionGeneration) return;
        if(sync.stage == DirectConnectWire.AdmissionSync.BASELINE_END
                || sync.stage == DirectConnectWire.AdmissionSync.CATCH_UP_END) {
            pendingAdmissionFence = sync;
            pendingAdmissionCheckpoint = ++nextAdmissionCheckpoint;
            if(nativeAdmission) {
                status = new DirectConnectStatus(DirectConnectPhase.SYNCHRONIZING,
                        "Loading current Active Floor as Spectator.", sessionId, "host",
                        readyMessage == null ? null : readyMessage.floorId);
            }
            else acknowledgeNativeWorldReadiness(pendingAdmissionCheckpoint);
        }
        else if(sync.stage == DirectConnectWire.AdmissionSync.ACTIVATED) {
            admissionPending = false;
            becomeReadyIfComplete();
        }
    }

    private synchronized void rejected(ServerRejected rejection) {
        status = new DirectConnectStatus(DirectConnectPhase.REJECTED,
                rejection.code.name() + ": " + rejection.reason,
                sessionId, "host", null);
        shutdownAsync();
    }

    private synchronized void serverDisconnected(ServerDisconnect disconnect) {
        status = new DirectConnectStatus(DirectConnectPhase.DISCONNECTED,
                "Host disconnected cleanly: " + disconnect.reason,
                sessionId, "host", null);
        shutdownAsync();
    }

    private synchronized void channelClosed() {
        DirectConnectPhase phase = status.getPhase();
        if(!closing.get() && phase != DirectConnectPhase.REJECTED
                && phase != DirectConnectPhase.DISCONNECTED
                && phase != DirectConnectPhase.FAILED) {
            status = new DirectConnectStatus(DirectConnectPhase.DISCONNECTED,
                    "Host TCP connection closed.", sessionId, "host", null);
        }
        shutdownAsync();
    }

    private synchronized void unexpected(Message message) {
        fail("Host sent message outside current session state: "
                + message.getClass().getSimpleName() + ".");
    }

    private synchronized void fail(String reason) {
        if(closing.get() || status.getPhase() == DirectConnectPhase.REJECTED) return;
        String diagnostic = boundedReason(reason);
        status = new DirectConnectStatus(DirectConnectPhase.FAILED, diagnostic,
                sessionId, "host", null);
        if(Gdx.app != null) Gdx.app.error("DelverMultiplayer", diagnostic);
        shutdownAsync();
    }

    private void shutdownAsync() {
        if(!resourcesClosing.compareAndSet(false, true)) return;
        Channel udp = udpChannel;
        Channel tcp = tcpChannel;
        if(udp != null) udp.close();
        if(tcp != null) tcp.close();
        networkGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS);
    }

    private boolean fromExpectedHost(InetSocketAddress sender) {
        if(tcpChannel == null || sender == null) return false;
        InetSocketAddress tcpRemote = (InetSocketAddress)tcpChannel.remoteAddress();
        if(tcpRemote == null || sender.getPort() != port) return false;
        InetAddress tcpAddress = tcpRemote.getAddress();
        InetAddress udpAddress = sender.getAddress();
        return tcpAddress != null && tcpAddress.equals(udpAddress);
    }

    public LauncherIdentity getLauncherIdentity() {
        return launcherIdentity;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public int getCampaignCapacity() {
        return campaignCapacity;
    }

    public int getCampaignSlot() {
        return campaignSlot;
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
    public CombatSnapshot getCombatSnapshot() {
        return combatSnapshot;
    }

    @Override
    public List<CombatPresentationEvent> getCombatPresentationEvents() {
        return combatPresentations.getEvents();
    }

    @Override
    public long getNextCombatRequestId() {
        SessionReady ready = readyMessage;
        return ready == null ? 1L : ready.nextCombatRequestId;
    }

    @Override
    public PartyStatusSnapshot getPartyStatus() {
        return partyStatus;
    }

    @Override
    public PartyCommunicationState getPartyCommunicationState() {
        return partyCommunication;
    }

    @Override
    public synchronized void submitPartyChat(String text) {
        PartyChatSubmit chat = new PartyChatSubmit(sessionId, text);
        if(!canSendReliableSessionEvent()) return;
        tcpChannel.writeAndFlush(chat);
    }

    @Override
    public synchronized void requestPauseSession() {
        if(!canSendReliableSessionEvent()) return;
        tcpChannel.writeAndFlush(new PauseRequestMessage(sessionId));
    }

    @Override
    public synchronized void submitReviveIntent(int targetSlot, boolean active) {
        if(!canSendReliableSessionEvent() || targetSlot < 1 || targetSlot > 4) return;
        tcpChannel.writeAndFlush(new DirectConnectWire.ReviveIntentMessage(sessionId, targetSlot, active, nativeWorldGeneration));
    }

    @Override
    public boolean isSessionPaused() {
        return partyCommunication.getPauseSession().isPaused();
    }

    @Override
    public boolean canControlSessionPause() {
        return false;
    }

    @Override
    public void setSessionPaused(boolean paused) {
        throw new UnsupportedOperationException("Only Host can control Pause Session.");
    }

    @Override
    public synchronized void submitCombatAction(long requestId, CombatAction action, String targetId) {
        CombatActionRequestMessage request = new CombatActionRequestMessage(sessionId, requestId,
                action, targetId).atGeneration(nativeWorldGeneration);
        if(!canSendReliableSessionEvent()) return;
        tcpChannel.writeAndFlush(request);
    }

    @Override
    public synchronized void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ) {
        submitCombatAction(requestId, action, aimX, aimY, aimZ, 1f);
    }

    @Override
    public synchronized void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ, float attackPower) {
        submitCombatAction(requestId, action, aimX, aimY, aimZ, attackPower, 0L);
    }

    @Override
    public synchronized void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ, float attackPower, long weaponEntityId) {
        CombatActionRequestMessage request = new CombatActionRequestMessage(sessionId, requestId,
                action, aimX, aimY, aimZ, attackPower, weaponEntityId).atGeneration(nativeWorldGeneration);
        if(!canSendReliableSessionEvent()) return;
        tcpChannel.writeAndFlush(request);
    }

    @Override
    public synchronized List<DoorSnapshot> getDoorSnapshots() {
        return new ArrayList<DoorSnapshot>(doorSnapshots.values());
    }

    @Override
    public synchronized List<com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot> getMoverSnapshots() {
        return new ArrayList<com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot>(moverSnapshots.values());
    }

    private synchronized void moverState(DirectConnectWire.MoverStateMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Mover state belongs to another session.");
            return;
        }
        com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot previous = moverSnapshots.get(message.state.entityId);
        if(previous == null && moverSnapshots.size() >= 4096) {
            fail("Mover count exceeds session bound.");
            return;
        }
        if(previous == null || previous.revision < message.state.revision) {
            moverSnapshots.put(message.state.entityId, message.state);
        }
    }

    private synchronized void doorState(DirectConnectWire.DoorStateMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Door state belongs to another session.");
            return;
        }
        DoorSnapshot previous = doorSnapshots.get(message.state.entityId);
        if(previous == null && doorSnapshots.size() >= 4096) {
            fail("Door count exceeds session bound.");
            return;
        }
        if(previous == null || previous.revision < message.state.revision) {
            doorSnapshots.put(message.state.entityId, message.state);
        }
    }

    @Override
    public synchronized List<BreakableSnapshot> getBreakableSnapshots() {
        return new ArrayList<BreakableSnapshot>(breakableSnapshots.values());
    }

    private synchronized void breakableState(
            DirectConnectWire.BreakableStateMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Breakable state belongs to another session.");
            return;
        }
        BreakableSnapshot previous = breakableSnapshots.get(message.state.entityId);
        if(previous == null && breakableSnapshots.size() >= 4096) {
            fail("Breakable count exceeds session bound.");
            return;
        }
        if(previous == null || previous.revision < message.state.revision) {
            breakableSnapshots.put(message.state.entityId, message.state);
        }
    }

    @Override
    public synchronized List<PhysicalItemState> getPhysicalItems() {
        return new ArrayList<PhysicalItemState>(physicalItems.values());
    }

    @Override
    public synchronized long getNextItemRequestId() { return nextItemRequestId; }

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
        DirectConnectWire.ItemRequestMessage request = new DirectConnectWire.ItemRequestMessage(
                sessionId, requestId, nativeWorldGeneration, action, entityId, condition, quantity,
                hasAim, aimX, aimY, aimZ);
        if(!canSendReliableSessionEvent() || isSessionPaused()) return;
        nextItemRequestId = Math.max(nextItemRequestId, requestId + 1L);
        tcpChannel.writeAndFlush(request);
    }

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

    private long nativeWorldGeneration = 1;
    @Override public synchronized long getNativeWorldGeneration() { return nativeWorldGeneration; }

    @Override public synchronized long getSharedFloorSeed() {
        return partyDestination != null ? partyDestination.seed : readyMessage == null ? 0L : readyMessage.floorSeed;
    }

    /** Host compares this build with its own and removes a client whose floor differs. */
    @Override public synchronized void recordSharedFloorFingerprint(
            com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint fingerprint) {
        if(fingerprint == null || !canSendReliableSessionEvent()) return;
        tcpChannel.writeAndFlush(new DirectConnectWire.SharedFloorFingerprintMessage(sessionId, fingerprint));
    }
    private synchronized void nativeWorldGeneration(DirectConnectWire.NativeWorldGenerationMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Native world belongs to another session."); return; }
        if(message.generation <= nativeWorldGeneration) return;
        nativeWorldGeneration = message.generation;
        floorMovementBaselinePending = true;
        movementReplication.beginFloor();
        physicalItems.entrySet().removeIf(entry -> entry.getValue().owner == null);
        combatSnapshot = null;
        combatPresentations.clear();
        pendingMovementInputs.clear();
        itemActionResults.clear(); doorFeedback.clear();
        if(admissionPending) {
            pendingAdmissionFence = null;
            pendingAdmissionCheckpoint = 0L;
        }
        shopEntries.clear(); shopOpenings.clear();
        triggerPresentations.clear(); lastTriggerPresentationSequence = 0L;
        monsterEffects.clear(); nativeStatusCues.clear(); nativeExplosions.clear(); nativeAnimationCues.clear();
        nativeDynamicCues.clear();
        nativeMonsterSpawns.clear(); lastNativeMonsterSpawnSequence = 0L;
        nativeDecals.clear();
        nativeSpellPresentations.clear();
        nativeMeleePresentations.clear();
        nativeRangedPresentations.clear();
        nativeDynamicStates.clear(); nativeDynamicTombstones.clear();
        doorSnapshots.clear();
        moverSnapshots.clear();
        breakableSnapshots.clear();
    }

    private long lastNativeAnimationCueSequence;
    private boolean floorMovementBaselinePending;
    private final java.util.ArrayDeque<NativeAnimationCue> nativeAnimationCues = new java.util.ArrayDeque<>();
    @Override public synchronized List<NativeAnimationCue> drainNativeAnimationCues() {
        List<NativeAnimationCue> result = new ArrayList<>(nativeAnimationCues);
        nativeAnimationCues.clear(); return result;
    }
    private synchronized void nativeAnimationCue(DirectConnectWire.NativeAnimationCueMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("FrameCue belongs to another session."); return; }
        if(message.generation != nativeWorldGeneration) return;
        if(message.sequence <= lastNativeAnimationCueSequence) return;
        lastNativeAnimationCueSequence = message.sequence;
        // Cosmetic cue: a burst past the bound drops the oldest instead of ending the session.
        if(nativeAnimationCues.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) nativeAnimationCues.removeFirst();
        nativeAnimationCues.addLast(message.presentation);
    }

    private long lastNativeExplosionSequence;
    private final java.util.ArrayDeque<NativeExplosionPresentation> nativeExplosions = new java.util.ArrayDeque<>();
    @Override public synchronized List<NativeExplosionPresentation> drainNativeExplosions() {
        List<NativeExplosionPresentation> result = new ArrayList<>(nativeExplosions);
        nativeExplosions.clear(); return result;
    }
    private synchronized void nativeExplosion(DirectConnectWire.NativeExplosionMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Explosion belongs to another session."); return; }
        if(message.generation != nativeWorldGeneration) return;
        if(message.sequence <= lastNativeExplosionSequence) return;
        lastNativeExplosionSequence = message.sequence;
        if(nativeExplosions.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) nativeExplosions.removeFirst();
        nativeExplosions.addLast(message.presentation);
    }

    private long lastNativeDynamicSequence;
    @Override public synchronized List<NativeDynamicState> getNativeDynamicStates() {
        List<NativeDynamicState> result = new ArrayList<NativeDynamicState>(nativeDynamicStates.values());
        while(!nativeDynamicTombstones.isEmpty()) result.add(nativeDynamicTombstones.removeFirst());
        return result;
    }
    private synchronized void nativeDynamicState(DirectConnectWire.NativeDynamicStateMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Native entity belongs to another session."); return; }
        if(message.generation != nativeWorldGeneration || message.sequence <= lastNativeDynamicSequence) return;
        lastNativeDynamicSequence = message.sequence;
        if(message.state.active) {
            // Host bounds these too; one more projectile than the bound is simply not drawn.
            if(!nativeDynamicStates.containsKey(message.state.id) && nativeDynamicStates.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) return;
            nativeDynamicStates.put(message.state.id, message.state);
        }
        else {
            nativeDynamicStates.remove(message.state.id);
            if(nativeDynamicTombstones.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES * 2) {
                nativeDynamicTombstones.removeFirst();
            }
            nativeDynamicTombstones.addLast(message.state);
        }
    }

    private long lastNativeDynamicCueSequence;
    private final java.util.ArrayDeque<NativeDynamicCue> nativeDynamicCues =
            new java.util.ArrayDeque<NativeDynamicCue>();
    @Override public synchronized List<NativeDynamicCue> drainNativeDynamicCues() {
        List<NativeDynamicCue> result = new ArrayList<NativeDynamicCue>(nativeDynamicCues);
        nativeDynamicCues.clear(); return result;
    }
    private synchronized void nativeDynamicCue(DirectConnectWire.NativeDynamicCueMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Native dynamic cue belongs to another session."); return;
        }
        if(message.generation != nativeWorldGeneration
                || message.sequence <= lastNativeDynamicCueSequence) return;
        lastNativeDynamicCueSequence = message.sequence;
        if(nativeDynamicCues.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) nativeDynamicCues.removeFirst();
        nativeDynamicCues.addLast(message.cue);
    }

    private long lastNativeSpellPresentationSequence;
    private final java.util.ArrayDeque<NativeSpellPresentation> nativeSpellPresentations =
            new java.util.ArrayDeque<NativeSpellPresentation>();
    @Override public synchronized List<NativeSpellPresentation> drainNativeSpellPresentations() {
        List<NativeSpellPresentation> result =
                new ArrayList<NativeSpellPresentation>(nativeSpellPresentations);
        nativeSpellPresentations.clear();
        return result;
    }
    private synchronized void nativeSpellPresentation(
            DirectConnectWire.NativeSpellPresentationMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Native spell presentation belongs to another session."); return;
        }
        if(message.generation != nativeWorldGeneration
                || message.sequence <= lastNativeSpellPresentationSequence) return;
        lastNativeSpellPresentationSequence = message.sequence;
        if(nativeSpellPresentations.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) nativeSpellPresentations.removeFirst();
        nativeSpellPresentations.addLast(message.presentation);
    }

    @Override public synchronized void failNativePresentation(String reason) {
        fail("Native presentation compatibility failure: " + reason);
    }

    private long lastNativeMeleePresentationSequence;
    private final java.util.ArrayDeque<NativeMeleePresentation> nativeMeleePresentations =
            new java.util.ArrayDeque<NativeMeleePresentation>();
    @Override public synchronized List<NativeMeleePresentation> drainNativeMeleePresentations() {
        List<NativeMeleePresentation> result =
                new ArrayList<NativeMeleePresentation>(nativeMeleePresentations);
        nativeMeleePresentations.clear();
        return result;
    }
    private synchronized void nativeMeleePresentation(
            DirectConnectWire.NativeMeleePresentationMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Native melee presentation belongs to another session."); return;
        }
        if(message.generation != nativeWorldGeneration
                || message.sequence <= lastNativeMeleePresentationSequence) return;
        lastNativeMeleePresentationSequence = message.sequence;
        if(nativeMeleePresentations.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) nativeMeleePresentations.removeFirst();
        nativeMeleePresentations.addLast(message.presentation);
    }

    private long lastNativeRangedPresentationSequence;
    private final java.util.ArrayDeque<NativeRangedPresentation> nativeRangedPresentations =
            new java.util.ArrayDeque<NativeRangedPresentation>();
    @Override public synchronized List<NativeRangedPresentation> drainNativeRangedPresentations() {
        List<NativeRangedPresentation> result =
                new ArrayList<NativeRangedPresentation>(nativeRangedPresentations);
        nativeRangedPresentations.clear();
        return result;
    }
    private synchronized void nativeRangedPresentation(
            DirectConnectWire.NativeRangedPresentationMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Native ranged presentation belongs to another session."); return;
        }
        if(message.generation != nativeWorldGeneration
                || message.sequence <= lastNativeRangedPresentationSequence) return;
        lastNativeRangedPresentationSequence = message.sequence;
        if(nativeRangedPresentations.size() >= DirectConnectProtocol.MAX_NATIVE_DYNAMIC_ENTITIES) nativeRangedPresentations.removeFirst();
        nativeRangedPresentations.addLast(message.presentation);
    }

    private synchronized void doorFeedback(DirectConnectWire.DoorFeedbackMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Door feedback belongs to another session."); return; }
        queueDoorFeedback(message.feedback);
    }

    private final java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue> nativeStatusCues =
            new java.util.ArrayDeque<com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue>();
    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue> drainNativeStatusCues() {
        List<com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue> result = new ArrayList<>(nativeStatusCues);
        nativeStatusCues.clear();
        return result;
    }

    private synchronized void itemActionResult(DirectConnectWire.ItemActionResultMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Item response belongs to another session."); return; }
        if(itemActionResults.size() >= 128) { fail("Item action response queue exceeded."); return; }
        itemActionResults.addLast(message.result);
    }

    /**
     * Host frees the slot of a Monster dead long enough for everyone to see; its effects entry
     * goes with it. Dead in, or already gone from, Host's latest combat state qualifies.
     */
    private void dropSettledMonsterEffects() {
        CombatSnapshot combat = combatSnapshot;
        if(combat == null) return;
        for(java.util.Iterator<String> ids = monsterEffects.keySet().iterator(); ids.hasNext();) {
            String id = ids.next();
            if(!id.startsWith(com.interrupt.dungeoneer.multiplayer.combat.AuthoritativeCombatEncounter.MONSTER_ID_PREFIX)) continue;
            CombatantSnapshot state = combat.getCombatant(id);
            if(state == null || state.getHealth() <= 0) ids.remove();
        }
    }

    private synchronized void monsterEffects(DirectConnectWire.MonsterEffectsMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Native effects belong to another session."); return; }
        if(message.generation != nativeWorldGeneration) return;
        ActorEffectsSnapshot previous = monsterEffects.get(message.state.monsterId);
        if(previous == null && monsterEffects.size() >= DirectConnectProtocol.MAX_MONSTERS + 4) {
            dropSettledMonsterEffects();
        }
        if(previous == null && monsterEffects.size() >= DirectConnectProtocol.MAX_MONSTERS + 4) {
            fail("Native effect actor count exceeds session bound."); return;
        }
        if(previous == null || previous.sequence < message.state.sequence) {
            if(message.live) for(com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState effect : message.state.effects) {
                boolean existed = false;
                if(previous != null) for(com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState old : previous.effects) {
                    if(old.instanceId == effect.instanceId) { existed = true; break; }
                }
                if(!existed) {
                    if(!queueNativeStatusCue(new com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue(
                            message.state.monsterId, effect))) return;
                }
                else {
                    for(com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState old : previous.effects) {
                        if(old.instanceId == effect.instanceId && effect.pulses > old.pulses) {
                            if(!queueNativeStatusCue(new com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue(
                                    message.state.monsterId, effect,
                                    com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue.Kind.PULSE))) return;
                            break;
                        }
                    }
                }
            }
            monsterEffects.put(message.state.monsterId, message.state);
        }
    }

    private boolean queueNativeStatusCue(com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue cue) {
        if(nativeStatusCues.size() >= (DirectConnectProtocol.MAX_MONSTERS + 4)
                * ActorEffectsSnapshot.MAX_EFFECTS) nativeStatusCues.removeFirst();
        nativeStatusCues.addLast(cue); return true;
    }

    @Override public synchronized int getPartyKeys() { return partyKeys; }

    @Override public synchronized com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot getPartyProgression() {
        return partyProgression;
    }

    @Override public synchronized com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge getPersonalKnowledge() { return personalKnowledge; }

    private com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping potionMapping = com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping.empty();
    @Override public synchronized com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping getPotionMapping() { return potionMapping; }
    private synchronized void potionMapping(DirectConnectWire.PotionMappingMessage message) {
        if(!sessionId.equals(message.sessionId) || !message.state.effects.entrySet().containsAll(potionMapping.effects.entrySet())) {
            fail("Campaign potion mapping changed or belongs to another session."); return;
        }
        potionMapping = message.state;
    }

    private synchronized void personalKnowledge(DirectConnectWire.PersonalKnowledgeMessage message) {
        if(!sessionId.equals(message.sessionId) || !message.participant.equals(new ParticipantId("campaign-slot-" + campaignSlot))
                || message.generation != getNativeWorldGeneration()) {
            fail("Personal knowledge belongs to another session, slot or floor."); return;
        }
        if(message.state.revision > personalKnowledge.revision) personalKnowledge = message.state;
    }

    private synchronized void partyProgression(DirectConnectWire.PartyProgressionMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Party Progression belongs to another session."); return; }
        if(message.state.revision > partyProgressionRevision) {
            partyProgression = message.state; partyProgressionRevision = message.state.revision;
        }
    }

    private synchronized void partyKeys(DirectConnectWire.PartyKeysMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Party Keys belong to another session."); return; }
        if(message.revision > keyRevision) { partyKeys = message.count; keyRevision = message.revision; }
    }

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress> getParticipantProgress() {
        return new ArrayList<com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress>(participantProgress.values());
    }

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState> getShopEntries() {
        return new ArrayList<com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState>(shopEntries.values());
    }

    @Override public synchronized List<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> drainShopOpenings() {
        List<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> result =
                new ArrayList<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening>(shopOpenings);
        shopOpenings.clear();
        return result;
    }

    private synchronized void participantProgress(DirectConnectWire.ParticipantProgressMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Participant progress belongs to another session."); return; }
        com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress previous =
                participantProgress.get(message.progress.participantId);
        if(previous == null && participantProgress.size() >= DirectConnectProtocol.MAX_PARTY_MEMBERS) {
            fail("Participant progress exceeds Party bound."); return;
        }
        if(previous == null || previous.revision < message.progress.revision) {
            participantProgress.put(message.progress.participantId, message.progress);
        }
    }

    private synchronized void shopEntry(DirectConnectWire.ShopEntryStateMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Shop entry belongs to another session."); return; }
        if(message.entry.generation != nativeWorldGeneration) return;
        String key = message.entry.shopId + ":" + message.entry.entryId;
        com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState previous = shopEntries.get(key);
        if(previous == null && shopEntries.size()
                >= com.interrupt.dungeoneer.multiplayer.economy.AuthoritativeEconomy.MAX_SHOP_ENTRIES) {
            fail("Shop entries exceed session bound."); return;
        }
        if(previous == null || previous.revision < message.entry.revision) shopEntries.put(key, message.entry);
    }

    private synchronized void shopOpening(DirectConnectWire.ShopOpeningMessage message) {
        if(!sessionId.equals(message.sessionId)) { fail("Shop opening belongs to another session."); return; }
        if(message.opening.generation != nativeWorldGeneration) return;
        if(shopOpenings.size() >= 16) shopOpenings.removeFirst();
        shopOpenings.addLast(message.opening);
    }

    private synchronized void physicalItem(DirectConnectWire.ItemStateMessage message) {
        if(!sessionId.equals(message.sessionId)) {
            fail("Physical item state belongs to another session.");
            return;
        }
        PhysicalItemState previous = physicalItems.get(message.state.entityId);
        if(previous == null && physicalItems.size() >= AuthoritativeItemWorld.MAX_ITEMS) {
            fail("Physical item count exceeds session bound.");
            return;
        }
        if(previous == null || previous.revision < message.state.revision) {
            physicalItems.put(message.state.entityId, message.state);
        }
    }

    private boolean canSendReliableSessionEvent() {
        return status.getPhase() == DirectConnectPhase.READY && sessionId != null
                && tcpChannel != null && tcpChannel.isActive();
    }

    @Override
    public synchronized void submitMovementInput(MovementInputFrame input) {
        if(input == null) throw new IllegalArgumentException("Movement input cannot be null.");
        if(input.getInputTick() <= lastSubmittedMovementTick) return;
        lastSubmittedMovementTick = input.getInputTick();
        if(status.getPhase() != DirectConnectPhase.READY || udpChannel == null
                || tcpChannel == null || !tcpChannel.isActive()) return;

        pendingMovementInputs.add(input);
        while(pendingMovementInputs.size() > MAX_PENDING_MOVEMENT_INPUTS) {
            pendingMovementInputs.remove(0);
        }
        int first = Math.max(0, pendingMovementInputs.size()
                - DirectConnectProtocol.MAX_INPUT_FRAMES);
        List<MovementInputFrame> bundle = new ArrayList<MovementInputFrame>(
                pendingMovementInputs.subList(first, pendingMovementInputs.size()));
        InetSocketAddress remote = (InetSocketAddress)tcpChannel.remoteAddress();
        try {
            int copies = movementDatagramPolicy.copiesForBundle(bundle);
            if(copies < 0 || copies > 2) {
                throw new IllegalStateException("Movement datagram policy returned invalid copy count.");
            }
            for(int copy = 0; copy < copies; copy++) {
                ByteBuf encoded = DirectConnectWire.encodeDatagram(udpChannel.alloc(),
                        new MovementInputs(sessionId, udpToken, bundle, nativeWorldGeneration));
                udpChannel.writeAndFlush(new DatagramPacket(encoded,
                        new InetSocketAddress(remote.getAddress(), port)));
            }
        }
        catch(ProtocolException failure) {
            fail("Could not encode movement input: " + safeMessage(failure));
        }
    }

    private volatile com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot lobbySnapshot;
    @Override public com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot getLobbySnapshot() { return lobbySnapshot; }

    private synchronized void lobbySnapshot(DirectConnectWire.LobbySnapshotMessage report) {
        if(!java.util.Objects.equals(sessionId, report.sessionId) || campaignSlot == 0
                || report.snapshot.getCapacity() != campaignCapacity
                || report.snapshot.getSlot(campaignSlot) == null
                || !report.snapshot.getSlot(campaignSlot).isConnected()) {
            fail("Host returned invalid lobby ownership state.");
            return;
        }
        if(lobbySnapshot != null && report.snapshot.getSequence() <= lobbySnapshot.getSequence()) return;
        lobbySnapshot = report.snapshot;
        com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot.Slot local = lobbySnapshot.getSlot(campaignSlot);
        if(local.isAuthenticated() && !local.isSynchronized())
            tcpChannel.writeAndFlush(new DirectConnectWire.LobbyReceived(sessionId, lobbySnapshot.getSequence()));
    }

    @Override
    public DirectConnectStatus getStatus() {
        return status;
    }

    @Override
    public String getRole() {
        return "Client";
    }

    @Override
    public String getEndpoint() {
        return host + ":" + port + " (TCP + UDP)";
    }

    @Override
    public void close() {
        if(!closing.compareAndSet(false, true)) return;
        Channel tcp = tcpChannel;
        if(tcp != null && tcp.isActive()) {
            tcp.writeAndFlush(new ClientDisconnect("Client closed session."))
                    .awaitUninterruptibly(1000L);
        }
        if(resourcesClosing.compareAndSet(false, true)) {
            if(udpChannel != null) udpChannel.close().awaitUninterruptibly(2000L);
            if(tcp != null) tcp.close().awaitUninterruptibly(2000L);
            networkGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly();
        }
        else {
            networkGroup.terminationFuture().awaitUninterruptibly(2000L);
        }
        status = new DirectConnectStatus(DirectConnectPhase.CLOSED,
                "Client session closed.", sessionId, "host", null);
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

    private final class ClientTcpHandler extends SimpleChannelInboundHandler<Message> {
        @Override
        public void channelActive(ChannelHandlerContext context) {
            tcpConnected(context);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, Message message) {
            if(message instanceof CampaignChallenge && sessionId == null) {
                campaignChallenge((CampaignChallenge)message);
            }
            else if(message instanceof SlotPending
                    && status.getPhase() == DirectConnectPhase.CLAIMING_SLOT) {
                pending((SlotPending)message);
            }
            else if(message instanceof ServerAccepted && sessionId != null
                    && campaignSlot == 0) {
                accepted((ServerAccepted)message);
            }
            else if(message instanceof DirectConnectWire.LobbySnapshotMessage) {
                lobbySnapshot((DirectConnectWire.LobbySnapshotMessage)message);
            }
            else if(message instanceof ServerRejected) {
                rejected((ServerRejected)message);
            }
            else if(message instanceof DirectConnectWire.TravelDestination && sessionId != null) {
                DirectConnectWire.TravelDestination report = (DirectConnectWire.TravelDestination)message;
                if(sessionId.equals(report.sessionId) && report.destination.generation == getNativeWorldGeneration()
                        && (partyDestination == null || report.destination.generation > partyDestination.generation)) {
                    partyDestination = report.destination;
                    status = new DirectConnectStatus(DirectConnectPhase.READY, "Reconstructing Party destination.",
                            sessionId, "host", report.destination.floorId);
                }
            }
            else if(message instanceof DirectConnectWire.TravelState && sessionId != null) {
                DirectConnectWire.TravelState report = (DirectConnectWire.TravelState)message;
                if(sessionId.equals(report.sessionId) && report.state.sequence > travel.sequence)
                    travel = report.state;
            }
            else if(message instanceof DirectConnectWire.AdmissionSync && sessionId != null
                    && campaignSlot != 0) {
                admissionSync((DirectConnectWire.AdmissionSync)message);
            }
            else if(message instanceof MovementSnapshotMessage && sessionId != null && campaignSlot != 0) {
                MovementSnapshotMessage movement = (MovementSnapshotMessage)message;
                if(!sessionId.equals(movement.sessionId)) { fail("Movement belongs to another session."); return; }
                if(movement.generation != nativeWorldGeneration) return;
                boolean applied = floorMovementBaselinePending
                        ? movementReplication.applyFloorSnapshot(movement.snapshot)
                        : movementReplication.applySnapshot(movement.snapshot);
                if(applied) {
                    floorMovementBaselinePending = false;
                    acknowledgeMovementInputs(movement.snapshot);
                }
            }
            else if(message instanceof SessionReady && sessionId != null
                    && campaignSlot != 0) {
                sessionReady((SessionReady)message);
            }
            else if(message instanceof PartyStatusMessage && sessionId != null
                    && campaignSlot != 0) {
                partyStatus((PartyStatusMessage)message);
            }
            else if(message instanceof DirectConnectWire.DoorStateMessage && sessionId != null
                    && campaignSlot != 0) {
                doorState((DirectConnectWire.DoorStateMessage)message);
            }
            else if(message instanceof DirectConnectWire.MoverStateMessage && sessionId != null
                    && campaignSlot != 0) {
                moverState((DirectConnectWire.MoverStateMessage)message);
            }
            else if(message instanceof DirectConnectWire.BreakableStateMessage
                    && sessionId != null && campaignSlot != 0) {
                breakableState((DirectConnectWire.BreakableStateMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeWorldGenerationMessage && sessionId != null
                    && campaignSlot != 0) {
                nativeWorldGeneration((DirectConnectWire.NativeWorldGenerationMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeAnimationCueMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                nativeAnimationCue((DirectConnectWire.NativeAnimationCueMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeExplosionMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                nativeExplosion((DirectConnectWire.NativeExplosionMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeDynamicStateMessage && sessionId != null
                    && campaignSlot != 0) {
                nativeDynamicState((DirectConnectWire.NativeDynamicStateMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeDynamicCueMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                nativeDynamicCue((DirectConnectWire.NativeDynamicCueMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeSpellPresentationMessage
                    && sessionId != null && status.getPhase() == DirectConnectPhase.READY) {
                nativeSpellPresentation((DirectConnectWire.NativeSpellPresentationMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeMeleePresentationMessage
                    && sessionId != null && status.getPhase() == DirectConnectPhase.READY) {
                nativeMeleePresentation((DirectConnectWire.NativeMeleePresentationMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeRangedPresentationMessage
                    && sessionId != null && status.getPhase() == DirectConnectPhase.READY) {
                nativeRangedPresentation((DirectConnectWire.NativeRangedPresentationMessage)message);
            }
            else if(message instanceof DirectConnectWire.ItemActionResultMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                itemActionResult((DirectConnectWire.ItemActionResultMessage)message);
            }
            else if(message instanceof DirectConnectWire.DoorFeedbackMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                doorFeedback((DirectConnectWire.DoorFeedbackMessage)message);
            }
            else if(message instanceof DirectConnectWire.MonsterEffectsMessage && sessionId != null
                    && campaignSlot != 0) {
                monsterEffects((DirectConnectWire.MonsterEffectsMessage)message);
            }
            else if(message instanceof DirectConnectWire.PotionMappingMessage && sessionId != null && campaignSlot != 0) {
                potionMapping((DirectConnectWire.PotionMappingMessage)message);
            }
            else if(message instanceof DirectConnectWire.PersonalKnowledgeMessage && sessionId != null
                    && campaignSlot != 0) {
                personalKnowledge((DirectConnectWire.PersonalKnowledgeMessage)message);
            }
            else if(message instanceof DirectConnectWire.PartyProgressionMessage && sessionId != null
                    && campaignSlot != 0) {
                partyProgression((DirectConnectWire.PartyProgressionMessage)message);
            }
            else if(message instanceof DirectConnectWire.PartyKeysMessage && sessionId != null
                    && campaignSlot != 0) {
                partyKeys((DirectConnectWire.PartyKeysMessage)message);
            }
            else if(message instanceof DirectConnectWire.ParticipantProgressMessage && sessionId != null
                    && campaignSlot != 0) {
                participantProgress((DirectConnectWire.ParticipantProgressMessage)message);
            }
            else if(message instanceof DirectConnectWire.ShopEntryStateMessage && sessionId != null
                    && campaignSlot != 0) {
                shopEntry((DirectConnectWire.ShopEntryStateMessage)message);
            }
            else if(message instanceof DirectConnectWire.ShopOpeningMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                shopOpening((DirectConnectWire.ShopOpeningMessage)message);
            }
            else if(message instanceof DirectConnectWire.ItemStateMessage && sessionId != null
                    && campaignSlot != 0) {
                physicalItem((DirectConnectWire.ItemStateMessage)message);
            }
            else if(message instanceof CombatStateMessage && sessionId != null
                    && campaignSlot != 0) {
                combatState((CombatStateMessage)message);
            }
            else if(message instanceof CombatPresentationMessage && sessionId != null
                    && campaignSlot != 0) {
                combatPresentation((CombatPresentationMessage)message);
            }
            else if(message instanceof PartyChatDelivery && sessionId != null
                    && campaignSlot != 0) {
                partyChat((PartyChatDelivery)message);
            }
            else if(message instanceof PauseRequestedMessage && sessionId != null
                    && campaignSlot != 0) {
                pauseRequested((PauseRequestedMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeMonsterSpawnMessage && sessionId != null) {
                nativeMonsterSpawn((DirectConnectWire.NativeMonsterSpawnMessage)message);
            }
            else if(message instanceof DirectConnectWire.NativeDecalMessage && sessionId != null) {
                nativeDecal((DirectConnectWire.NativeDecalMessage)message);
            }
            else if(message instanceof DirectConnectWire.TriggerPresentationMessage && sessionId != null
                    && status.getPhase() == DirectConnectPhase.READY) {
                triggerPresentation((DirectConnectWire.TriggerPresentationMessage)message);
            }
            else if(message instanceof DirectConnectWire.PartyWipeMessage && sessionId != null) {
                partyWipe((DirectConnectWire.PartyWipeMessage)message);
            }
            else if(message instanceof PauseSessionStateMessage && sessionId != null
                    && campaignSlot != 0) {
                pauseSession((PauseSessionStateMessage)message);
            }
            else if(message instanceof EntitySpawn && sessionId != null
                    && campaignSlot != 0) {
                entitySpawn((EntitySpawn)message);
            }
            else if(message instanceof EntityDespawn && sessionId != null
                    && campaignSlot != 0) {
                entityDespawn((EntityDespawn)message);
            }
            else if(message instanceof ServerDisconnect) {
                serverDisconnected((ServerDisconnect)message);
            }
            else {
                unexpected(message);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            channelClosed();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            fail("TCP session failed: " + safeMessage(cause));
        }
    }
}
