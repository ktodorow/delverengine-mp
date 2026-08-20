package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSession;
import com.interrupt.dungeoneer.multiplayer.host.HostDisconnectOutcome;
import com.interrupt.dungeoneer.multiplayer.host.HostPersistedState;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionStorage;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionTransport;
import com.interrupt.dungeoneer.multiplayer.host.HostTransitionOutcome;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster.ClaimOutcome;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster.ClaimStatus;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSlot;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotClaimRequest;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CampaignChallenge;
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
import com.interrupt.dungeoneer.multiplayer.movement.MovementReplicationState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.movement.RectangularMovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Participant Host authority for explicit lobby approval and persistent Campaign Slots. */
public final class DirectConnectHost implements DirectConnectPeer {
    private static final int SNAPSHOT_INTERVAL_TICKS =
            AuthoritativeHostSession.TICKS_PER_SECOND / 20;

    private final DirectConnectCompatibility compatibility;
    private final CampaignRoster roster;
    private final CampaignRosterStore rosterStore;
    private final MovementCollisionWorld movementWorld;
    private final MovementReplicationState movementReplication =
            new MovementReplicationState();
    private final EventLoopGroup acceptGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup networkGroup = new NioEventLoopGroup(2);
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final String sessionId;
    private final Map<Channel, RemoteConnection> connections =
            new LinkedHashMap<Channel, RemoteConnection>();

    private volatile DirectConnectStatus status;
    private volatile Channel tcpListener;
    private volatile Channel udpListener;
    private volatile int boundPort;
    private volatile boolean sessionStarted;
    private volatile NetworkEntityId localMovementEntityId;
    private volatile AuthoritativeMovementSimulation movementSimulation;
    private volatile AuthoritativeHostSession movementSession;
    private volatile ScheduledFuture<?> movementTask;
    private volatile PartyStatusSnapshot partyStatus;
    private long nextLifecycleSequence;
    private long nextPartyStatusSequence;

    private DirectConnectHost(DirectConnectCompatibility compatibility, CampaignRoster roster,
            CampaignRosterStore rosterStore, MovementCollisionWorld movementWorld) {
        if(compatibility == null) throw new IllegalArgumentException("Host compatibility cannot be null.");
        if(roster == null) throw new IllegalArgumentException("Campaign Roster cannot be null.");
        if(rosterStore == null) throw new IllegalArgumentException("Campaign Roster store cannot be null.");
        if(movementWorld == null) throw new IllegalArgumentException("Movement collision world cannot be null.");
        this.compatibility = compatibility;
        this.roster = roster;
        this.rosterStore = rosterStore;
        this.movementWorld = movementWorld;
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
        DirectConnectHost host = new DirectConnectHost(compatibility, roster, rosterStore,
                movementWorld);
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

        ChannelFuture tcpBind = tcp.bind(new InetSocketAddress(requestedPort)).syncUninterruptibly();
        requireSuccess(tcpBind, "Could not bind Direct Connect TCP listener");
        tcpListener = tcpBind.channel();
        boundPort = ((InetSocketAddress)tcpListener.localAddress()).getPort();

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

        ChannelFuture udpBind = udp.bind(new InetSocketAddress(boundPort)).syncUninterruptibly();
        requireSuccess(udpBind, "Could not bind Direct Connect UDP listener");
        udpListener = udpBind.channel();
        status = status(DirectConnectPhase.LISTENING,
                "Campaign " + roster.getCampaignId() + " has " + roster.getSlots().size()
                        + "/" + roster.getCapacity() + " claimed slots. Waiting on TCP and UDP port "
                        + boundPort + ".", null, null);
    }

    private static void requireSuccess(ChannelFuture future, String message) {
        if(future.isSuccess()) return;
        Throwable cause = future.cause();
        throw new IllegalStateException(message + (cause == null ? "." : ": "
                + safeMessage(cause)), cause);
    }

    private synchronized boolean acceptHello(ChannelHandlerContext context, ClientHello hello) {
        if(sessionStarted) {
            reject(context, RejectCode.NOT_IN_LOBBY,
                    "Campaign Slot claims are closed after Active Floor play starts.");
            return false;
        }
        if(connections.size() >= roster.getCapacity() - 1) {
            reject(context, RejectCode.SESSION_FULL,
                    "Session lobby already has its maximum remote connections.");
            return false;
        }
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
                    "Content mismatch: normalized content identity differs from Host.");
            return false;
        }
        if(findConnection(launcherIdentity) != null) {
            reject(context, RejectCode.IDENTITY_IN_USE,
                    "Launcher Identity is already connected to this Private Session.");
            return false;
        }

        RemoteConnection connection = new RemoteConnection(context.channel(),
                (InetSocketAddress)context.channel().remoteAddress(), launcherIdentity);
        connections.put(context.channel(), connection);
        context.writeAndFlush(new CampaignChallenge(sessionId, roster.getCampaignId(),
                roster.getCapacity()));
        status = status(DirectConnectPhase.HANDSHAKING,
                "Compatible Launcher Identity " + launcherIdentity.getFingerprint()
                        + " is selecting its Campaign Slot.",
                launcherIdentity.getFingerprint(), null);
        return true;
    }

    private synchronized void claimSlot(ChannelHandlerContext context, SlotClaim claim) {
        RemoteConnection connection = connections.get(context.channel());
        if(connection == null || connection.request != null || !sessionId.equals(claim.sessionId)) {
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
        connection.request = request;

        ClaimOutcome outcome = roster.submit(request);
        if(outcome.getStatus() == ClaimStatus.ADMITTED) {
            if(!persistRoster(context.channel())) return;
            admit(connection, outcome.getSlot());
        }
        else if(outcome.getStatus() == ClaimStatus.NEEDS_APPROVAL) {
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

    public synchronized boolean canStartSession() {
        return !sessionStarted && getUdpReadyParticipantCount() >= 2;
    }

    public synchronized void startSession() {
        if(sessionStarted) return;
        int participantCount = getUdpReadyParticipantCount();
        if(participantCount < 2) {
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
        movementSession = new AuthoritativeHostSession(movementSimulation,
                new MovementTransport(), new HostSessionStorage() {
                    @Override
                    public void persist(long hostTick, HostPersistedState state) { }
                });
        sessionStarted = true;

        for(MovementEntityDescriptor descriptor : descriptors) {
            movementReplication.applySpawn(descriptor);
            if(descriptor.getCampaignSlot() == 1) {
                localMovementEntityId = descriptor.getEntityId();
            }
        }
        partyStatus = createPartyStatus(descriptors);
        for(RemoteConnection connection : connections.values()) {
            if(connection.slot != null && connection.udpAddress != null
                    && connection.channel.isActive()) {
                for(MovementEntityDescriptor descriptor : descriptors) {
                    connection.channel.write(new EntitySpawn(sessionId, descriptor));
                    if(descriptor.getCampaignSlot() == connection.slot.getNumber()) {
                        connection.movementDescriptor = descriptor;
                    }
                }
                connection.channel.write(new PartyStatusMessage(sessionId, partyStatus));
                connection.channel.writeAndFlush(new SessionReady(sessionId, participantCount,
                        GameApplication.OPEN_SOURCE_TEST_LEVEL));
            }
        }
        startMovementTicks();
        status = status(DirectConnectPhase.READY,
                "Host started shared open-source test floor with " + participantCount
                        + " Participants.", null, GameApplication.OPEN_SOURCE_TEST_LEVEL);
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
            descriptors.add(new MovementEntityDescriptor(++nextLifecycleSequence,
                    entityId(slot), participantId(slot), slot.getNumber(),
                    slot.getPresentation().getNickname(),
                    slot.getPresentation().getAvatarId()));
        }
        return descriptors;
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
            members.add(PartyMemberStatus.initial(slot, descriptor));
        }
        return new PartyStatusSnapshot(++nextPartyStatusSequence, members);
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
                        session.advanceOneTick();
                    }
                }
                catch(RuntimeException failure) {
                    status = status(DirectConnectPhase.FAILED,
                            "Authoritative movement tick failed: " + safeMessage(failure),
                            null, GameApplication.OPEN_SOURCE_TEST_LEVEL);
                    throw failure;
                }
            }
        }, tickNanos, tickNanos, TimeUnit.NANOSECONDS);
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
        connection.udpToken = nextNonZeroLong(random);
        connection.channel.writeAndFlush(new ServerAccepted(sessionId, connection.udpToken,
                roster.getCampaignId(), slot.getNumber(), slot.getReconnectToken()));
        status = status(DirectConnectPhase.REGISTERING_UDP,
                slot.getPresentation().getNickname() + " owns Campaign Slot "
                        + slot.getNumber() + ". Waiting for authenticated UDP registration.",
                connection.launcherIdentity.getFingerprint(), null);
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
        if(decoded instanceof UdpRegister && !sessionStarted) {
            UdpRegister register = (UdpRegister)decoded;
            if(!sessionId.equals(register.sessionId)) return;

            RemoteConnection connection = findConnection(register.udpToken);
            if(connection == null || !sameAddress(connection.tcpAddress, packet.sender())) return;
            connection.udpAddress = packet.sender();
            try {
                ByteBuf response = DirectConnectWire.encodeDatagram(udpListener.alloc(),
                        new UdpRegistered(sessionId, connection.udpToken));
                udpListener.writeAndFlush(new DatagramPacket(response, connection.udpAddress));
                status = status(DirectConnectPhase.LOBBY,
                        "Campaign Slot " + connection.slot.getNumber()
                                + " is TCP/UDP ready. Host may approve more claims or start play.",
                        connection.launcherIdentity.getFingerprint(), null);
            }
            catch(ProtocolException ex) {
                failConnection(connection, "Could not encode UDP registration response: "
                        + safeMessage(ex));
            }
            return;
        }

        if(decoded instanceof MovementInputs && sessionStarted) {
            MovementInputs inputs = (MovementInputs)decoded;
            if(!sessionId.equals(inputs.sessionId)) return;
            RemoteConnection connection = findConnection(inputs.udpToken);
            if(connection == null || connection.movementDescriptor == null
                    || !packet.sender().equals(connection.udpAddress)) return;
            AuthoritativeHostSession session = movementSession;
            if(session == null) return;
            for(MovementInputFrame input : inputs.inputs) {
                session.submit(new MovementInputCommand(
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
                sessionStarted ? GameApplication.OPEN_SOURCE_TEST_LEVEL : null);
        context.close();
    }

    private synchronized void channelClosed(Channel channel) {
        RemoteConnection connection = connections.remove(channel);
        if(connection == null || closing.get()) return;
        if(sessionStarted && connection.movementDescriptor != null) {
            MovementEntityDescriptor descriptor = connection.movementDescriptor;
            AuthoritativeMovementSimulation simulation = movementSimulation;
            if(simulation != null) simulation.removeParticipant(descriptor.getParticipantId());
            long lifecycleSequence = ++nextLifecycleSequence;
            movementReplication.applyDespawn(lifecycleSequence, descriptor.getEntityId());
            EntityDespawn despawn = new EntityDespawn(sessionId, lifecycleSequence,
                    descriptor.getEntityId());
            for(RemoteConnection remaining : connections.values()) {
                if(remaining.channel.isActive() && remaining.movementDescriptor != null) {
                    remaining.channel.writeAndFlush(despawn);
                }
            }
            markPartyMemberDisconnected(descriptor.getCampaignSlot());
            status = status(DirectConnectPhase.READY,
                    descriptor.getNickname()
                            + " left Active Floor; stable remote Avatar despawned.",
                    descriptor.getParticipantId().getValue(),
                    GameApplication.OPEN_SOURCE_TEST_LEVEL);
            return;
        }
        if(status.getPhase() != DirectConnectPhase.DISCONNECTED) {
            status = status(DirectConnectPhase.DISCONNECTED,
                    "Participant TCP connection closed; persistent Campaign Slot was preserved.",
                    connection.launcherIdentity.getFingerprint(), null);
        }
    }

    private void markPartyMemberDisconnected(int campaignSlot) {
        PartyStatusSnapshot current = partyStatus;
        if(current == null) return;
        List<PartyMemberStatus> updated =
                new ArrayList<PartyMemberStatus>(current.getMembers());
        for(int index = 0; index < updated.size(); index++) {
            PartyMemberStatus member = updated.get(index);
            if(member.getCampaignSlot() == campaignSlot) {
                updated.set(index, member.disconnected());
                break;
            }
        }
        partyStatus = new PartyStatusSnapshot(++nextPartyStatusSequence, updated);
        PartyStatusMessage message = new PartyStatusMessage(sessionId, partyStatus);
        for(RemoteConnection remaining : connections.values()) {
            if(remaining.channel.isActive() && remaining.movementDescriptor != null) {
                remaining.channel.writeAndFlush(message);
            }
        }
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
        if(connection != null) {
            status = status(DirectConnectPhase.DISCONNECTED,
                    "Launcher Identity sent malformed session input and was disconnected.",
                    connection.launcherIdentity.getFingerprint(), null);
        }
    }

    private synchronized void failConnection(RemoteConnection connection, String reason) {
        status = status(DirectConnectPhase.FAILED, boundedReason(reason),
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
    public PartyStatusSnapshot getPartyStatus() {
        return partyStatus;
    }

    @Override
    public void submitMovementInput(MovementInputFrame input) {
        if(input == null) throw new IllegalArgumentException("Movement input cannot be null.");
        NetworkEntityId localEntityId = localMovementEntityId;
        AuthoritativeHostSession session = movementSession;
        if(localEntityId == null || session == null || !sessionStarted || closing.get()) return;
        MovementEntityDescriptor descriptor = movementReplication.getEntity(localEntityId);
        if(descriptor != null) {
            session.submit(new MovementInputCommand(descriptor.getParticipantId(), input));
        }
    }

    @Override
    public DirectConnectStatus getStatus() {
        return status;
    }

    public int getBoundPort() {
        return boundPort;
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
        List<Channel> participants;
        synchronized(this) {
            participants = new ArrayList<Channel>(connections.keySet());
        }
        for(Channel participant : participants) {
            if(participant.isActive()) {
                participant.writeAndFlush(new ServerDisconnect("Host closed session."))
                        .awaitUninterruptibly(1000L);
            }
        }
        closeResources();
        status = status(DirectConnectPhase.CLOSED, "Host session closed.", null, null);
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
            if(!(snapshot instanceof MovementSnapshot)
                    || hostTick % SNAPSHOT_INTERVAL_TICKS != 0L) return;
            MovementSnapshot movementSnapshot = (MovementSnapshot)snapshot;
            movementReplication.applySnapshot(movementSnapshot);

            List<RemoteConnection> snapshotConnections;
            synchronized(DirectConnectHost.this) {
                snapshotConnections = new ArrayList<RemoteConnection>(connections.values());
            }
            for(RemoteConnection connection : snapshotConnections) {
                if(connection.udpAddress == null || !connection.channel.isActive()
                        || connection.movementDescriptor == null) continue;
                try {
                    ByteBuf encoded = DirectConnectWire.encodeDatagram(udpListener.alloc(),
                            new MovementSnapshotMessage(sessionId, movementSnapshot));
                    udpListener.writeAndFlush(new DatagramPacket(encoded,
                            connection.udpAddress));
                }
                catch(ProtocolException failure) {
                    failConnection(connection, "Could not encode movement snapshot: "
                            + safeMessage(failure));
                }
            }
        }

        @Override
        public void publishEvent(long hostTick, HostSessionEvent event) { }

        @Override
        public void publishDisconnect(long hostTick, HostDisconnectOutcome outcome) { }

        @Override
        public void publishTransition(long hostTick, HostTransitionOutcome outcome) { }
    }

    private static final class RemoteConnection {
        private final Channel channel;
        private final InetSocketAddress tcpAddress;
        private final LauncherIdentity launcherIdentity;
        private SlotClaimRequest request;
        private CampaignSlot slot;
        private InetSocketAddress udpAddress;
        private long udpToken;
        private MovementEntityDescriptor movementDescriptor;

        private RemoteConnection(Channel channel, InetSocketAddress tcpAddress,
                LauncherIdentity launcherIdentity) {
            this.channel = channel;
            this.tcpAddress = tcpAddress;
            this.launcherIdentity = launcherIdentity;
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
            else if(message instanceof SlotClaim && helloAccepted) {
                claimSlot(context, (SlotClaim)message);
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
