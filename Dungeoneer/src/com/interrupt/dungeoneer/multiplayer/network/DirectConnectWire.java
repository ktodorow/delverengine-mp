package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationPhase;
import com.interrupt.dungeoneer.multiplayer.combat.CombatRequest;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.MonsterSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.communication.PartyChatMessage;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationText;
import com.interrupt.dungeoneer.multiplayer.communication.PauseRequest;
import com.interrupt.dungeoneer.multiplayer.communication.PauseSessionState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Manual allowlisted wire codec. It never serializes engine, entity, or save object graphs. */
final class DirectConnectWire {
    private static final int CLIENT_HELLO = 1;
    private static final int SERVER_ACCEPTED = 2;
    private static final int SERVER_REJECTED = 3;
    private static final int UDP_REGISTER = 4;
    private static final int UDP_REGISTERED = 5;
    private static final int SESSION_READY = 6;
    private static final int CLIENT_DISCONNECT = 7;
    private static final int SERVER_DISCONNECT = 8;
    private static final int CAMPAIGN_CHALLENGE = 9;
    private static final int SLOT_CLAIM = 10;
    private static final int SLOT_PENDING = 11;
    private static final int ENTITY_SPAWN = 12;
    private static final int ENTITY_DESPAWN = 13;
    private static final int MOVEMENT_INPUTS = 14;
    private static final int MOVEMENT_SNAPSHOT = 15;
    private static final int DISCOVERY_PROBE = 16;
    private static final int DISCOVERY_ANNOUNCEMENT = 17;
    private static final int PARTY_STATUS = 18;
    private static final int PARTY_CHAT_SUBMIT = 19;
    private static final int PARTY_CHAT_DELIVERY = 20;
    private static final int PAUSE_REQUEST = 23;
    private static final int PAUSE_REQUESTED = 24;
    private static final int PAUSE_SESSION_STATE = 25;
    private static final int COMBAT_ACTION_REQUEST = 26;
    private static final int COMBAT_STATE = 27;
    private static final int COMBAT_PRESENTATION = 28;

    private DirectConnectWire() { }

    static void configureTcp(ChannelPipeline pipeline) {
        pipeline.addLast("directConnectFrameDecoder", new LengthFieldBasedFrameDecoder(
                DirectConnectProtocol.MAX_TCP_FRAME_BYTES + 4, 0, 4, 0, 4));
        pipeline.addLast("directConnectMessageDecoder", new TcpMessageDecoder());
        pipeline.addLast("directConnectFrameEncoder", new LengthFieldPrepender(4));
        pipeline.addLast("directConnectMessageEncoder", new TcpMessageEncoder());
    }

    static ByteBuf encodeDatagram(ByteBufAllocator allocator, Message message)
            throws ProtocolException {
        ByteBuf output = allocator.buffer(128);
        boolean successful = false;
        try {
            encode(message, output);
            if(output.readableBytes() > DirectConnectProtocol.MAX_UDP_DATAGRAM_BYTES) {
                throw new ProtocolException("Datagram exceeded protocol size bound.");
            }
            successful = true;
            return output;
        }
        finally {
            if(!successful) output.release();
        }
    }

    static Message decodeDatagram(ByteBuf input) throws ProtocolException {
        if(input.readableBytes() > DirectConnectProtocol.MAX_UDP_DATAGRAM_BYTES) {
            throw new ProtocolException("Datagram exceeded protocol size bound.");
        }
        return decode(input);
    }

    private static void encode(Message message, ByteBuf output) throws ProtocolException {
        if(message == null) throw new ProtocolException("Wire message cannot be null.");
        output.writeInt(DirectConnectProtocol.MAGIC);

        if(message instanceof DiscoveryProbe) {
            DiscoveryProbe probe = (DiscoveryProbe)message;
            if(probe.nonce == 0L || probe.protocolVersion < 1) {
                throw new ProtocolException("Discovery probe identity is invalid.");
            }
            output.writeByte(DISCOVERY_PROBE);
            output.writeLong(probe.nonce);
            output.writeInt(probe.protocolVersion);
            writeString(output, probe.buildId, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                    "build identity");
            writeString(output, probe.contentFormat,
                    DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES, "content format");
            writeString(output, probe.contentSha256,
                    DirectConnectProtocol.MAX_CONTENT_HASH_BYTES, "content hash");
        }
        else if(message instanceof DiscoveryAnnouncement) {
            DiscoveryAnnouncement announcement = (DiscoveryAnnouncement)message;
            if(announcement.nonce == 0L || announcement.protocolVersion < 1
                    || announcement.port < 1 || announcement.port > 65535
                    || announcement.capacity < 2 || announcement.capacity > 4
                    || announcement.claimedSlots < 1
                    || announcement.claimedSlots > announcement.capacity) {
                throw new ProtocolException("Discovery announcement is outside protocol bounds.");
            }
            output.writeByte(DISCOVERY_ANNOUNCEMENT);
            output.writeLong(announcement.nonce);
            output.writeInt(announcement.protocolVersion);
            writeString(output, announcement.buildId,
                    DirectConnectProtocol.MAX_BUILD_ID_BYTES, "build identity");
            writeString(output, announcement.contentFormat,
                    DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES, "content format");
            writeString(output, announcement.contentSha256,
                    DirectConnectProtocol.MAX_CONTENT_HASH_BYTES, "content hash");
            writeString(output, announcement.sessionId,
                    DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
            writeString(output, announcement.campaignId,
                    DirectConnectProtocol.MAX_CAMPAIGN_ID_BYTES, "campaign identity");
            output.writeInt(announcement.port);
            output.writeByte(announcement.capacity);
            output.writeByte(announcement.claimedSlots);
            output.writeBoolean(announcement.lobbyOpen);
        }
        else if(message instanceof ClientHello) {
            ClientHello hello = (ClientHello)message;
            output.writeByte(CLIENT_HELLO);
            output.writeInt(hello.protocolVersion);
            writeString(output, hello.buildId, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                    "build identity");
            writeString(output, hello.contentFormat,
                    DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES, "content format");
            writeString(output, hello.contentSha256,
                    DirectConnectProtocol.MAX_CONTENT_HASH_BYTES, "content hash");
            writeString(output, hello.launcherIdentity,
                    DirectConnectProtocol.MAX_LAUNCHER_IDENTITY_BYTES, "Launcher Identity");
        }
        else if(message instanceof CampaignChallenge) {
            CampaignChallenge challenge = (CampaignChallenge)message;
            output.writeByte(CAMPAIGN_CHALLENGE);
            writeString(output, challenge.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            writeString(output, challenge.campaignId, DirectConnectProtocol.MAX_CAMPAIGN_ID_BYTES,
                    "campaign identity");
            if(challenge.capacity < 2 || challenge.capacity > 4) {
                throw new ProtocolException("Campaign Capacity is outside protocol bounds.");
            }
            output.writeByte(challenge.capacity);
        }
        else if(message instanceof SlotClaim) {
            SlotClaim claim = (SlotClaim)message;
            output.writeByte(SLOT_CLAIM);
            writeString(output, claim.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            writeString(output, claim.nickname, DirectConnectProtocol.MAX_NICKNAME_BYTES,
                    "Nickname");
            writeString(output, claim.avatarId, DirectConnectProtocol.MAX_AVATAR_ID_BYTES,
                    "Avatar identity");
            if(claim.requestedSlot < 0 || claim.requestedSlot > 4) {
                throw new ProtocolException("Requested Campaign Slot is outside protocol bounds.");
            }
            output.writeByte(claim.requestedSlot);
            writeString(output, claim.reconnectToken,
                    DirectConnectProtocol.MAX_RECONNECT_TOKEN_BYTES, "reconnect credential");
        }
        else if(message instanceof SlotPending) {
            SlotPending pending = (SlotPending)message;
            output.writeByte(SLOT_PENDING);
            writeString(output, pending.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            writeString(output, pending.reason, DirectConnectProtocol.MAX_REASON_BYTES,
                    "pending approval reason");
        }
        else if(message instanceof ServerAccepted) {
            ServerAccepted accepted = (ServerAccepted)message;
            output.writeByte(SERVER_ACCEPTED);
            writeString(output, accepted.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(accepted.udpToken);
            writeString(output, accepted.campaignId, DirectConnectProtocol.MAX_CAMPAIGN_ID_BYTES,
                    "campaign identity");
            if(accepted.slotNumber < 1 || accepted.slotNumber > 4) {
                throw new ProtocolException("Campaign Slot number is outside protocol bounds.");
            }
            output.writeByte(accepted.slotNumber);
            writeString(output, accepted.reconnectToken,
                    DirectConnectProtocol.MAX_RECONNECT_TOKEN_BYTES, "reconnect credential");
        }
        else if(message instanceof ServerRejected) {
            ServerRejected rejected = (ServerRejected)message;
            output.writeByte(SERVER_REJECTED);
            output.writeByte(rejected.code.id);
            writeString(output, rejected.reason, DirectConnectProtocol.MAX_REASON_BYTES,
                    "rejection reason");
        }
        else if(message instanceof UdpRegister) {
            UdpRegister register = (UdpRegister)message;
            output.writeByte(UDP_REGISTER);
            writeString(output, register.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(register.udpToken);
        }
        else if(message instanceof UdpRegistered) {
            UdpRegistered registered = (UdpRegistered)message;
            output.writeByte(UDP_REGISTERED);
            writeString(output, registered.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(registered.udpToken);
        }
        else if(message instanceof SessionReady) {
            SessionReady ready = (SessionReady)message;
            output.writeByte(SESSION_READY);
            writeString(output, ready.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            if(ready.participantCount < 1 || ready.participantCount > 4) {
                throw new ProtocolException("Participant count is outside protocol bounds.");
            }
            output.writeByte(ready.participantCount);
            if(ready.nextCombatRequestId < 1L) {
                throw new ProtocolException("Combat request ID floor is outside protocol bounds.");
            }
            output.writeLong(ready.nextCombatRequestId);
            writeString(output, ready.floorId, DirectConnectProtocol.MAX_FLOOR_ID_BYTES,
                    "floor identity");
        }
        else if(message instanceof EntitySpawn) {
            EntitySpawn spawn = (EntitySpawn)message;
            MovementEntityDescriptor descriptor = spawn.descriptor;
            output.writeByte(ENTITY_SPAWN);
            writeString(output, spawn.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(descriptor.getLifecycleSequence());
            output.writeLong(descriptor.getEntityId().getValue());
            writeString(output, descriptor.getParticipantId().getValue(),
                    DirectConnectProtocol.MAX_PARTICIPANT_ID_BYTES, "Participant identity");
            output.writeByte(descriptor.getCampaignSlot());
            writeString(output, descriptor.getNickname(), DirectConnectProtocol.MAX_NICKNAME_BYTES,
                    "Nickname");
            writeString(output, descriptor.getAvatarId(), DirectConnectProtocol.MAX_AVATAR_ID_BYTES,
                    "Avatar identity");
        }
        else if(message instanceof EntityDespawn) {
            EntityDespawn despawn = (EntityDespawn)message;
            if(despawn.lifecycleSequence <= 0L) {
                throw new ProtocolException("Entity lifecycle sequence must be positive.");
            }
            output.writeByte(ENTITY_DESPAWN);
            writeString(output, despawn.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(despawn.lifecycleSequence);
            output.writeLong(despawn.entityId.getValue());
        }
        else if(message instanceof MovementInputs) {
            MovementInputs inputs = (MovementInputs)message;
            if(inputs.inputs.isEmpty()
                    || inputs.inputs.size() > DirectConnectProtocol.MAX_INPUT_FRAMES) {
                throw new ProtocolException("Movement input bundle count is outside protocol bounds.");
            }
            output.writeByte(MOVEMENT_INPUTS);
            writeString(output, inputs.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(inputs.udpToken);
            output.writeByte(inputs.inputs.size());
            for(MovementInputFrame input : inputs.inputs) {
                output.writeLong(input.getInputTick());
                output.writeFloat(input.getForward());
                output.writeFloat(input.getStrafe());
                output.writeFloat(input.getRotation());
                output.writeBoolean(input.isJump());
            }
        }
        else if(message instanceof MovementSnapshotMessage) {
            MovementSnapshotMessage snapshotMessage = (MovementSnapshotMessage)message;
            MovementSnapshot snapshot = snapshotMessage.snapshot;
            List<MovementEntityState> entities = snapshot.getEntities();
            if(entities.size() > DirectConnectProtocol.MAX_MOVEMENT_ENTITIES) {
                throw new ProtocolException("Movement snapshot entity count is outside protocol bounds.");
            }
            output.writeByte(MOVEMENT_SNAPSHOT);
            writeString(output, snapshotMessage.sessionId,
                    DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
            output.writeLong(snapshot.getSequence());
            output.writeLong(snapshot.getHostTick());
            output.writeByte(entities.size());
            for(MovementEntityState entity : entities) {
                output.writeLong(entity.getEntityId().getValue());
                output.writeLong(entity.getLifecycleSequence());
                output.writeLong(entity.getLastProcessedInputTick());
                output.writeFloat(entity.getX());
                output.writeFloat(entity.getY());
                output.writeFloat(entity.getZ());
                output.writeFloat(entity.getVelocityX());
                output.writeFloat(entity.getVelocityY());
                output.writeFloat(entity.getVelocityZ());
                output.writeFloat(entity.getRotation());
                output.writeByte(entity.getMovementState().getWireId());
            }
        }
        else if(message instanceof CombatActionRequestMessage) {
            CombatActionRequestMessage request = (CombatActionRequestMessage)message;
            output.writeByte(COMBAT_ACTION_REQUEST);
            writeString(output, request.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(request.requestId);
            output.writeByte(request.action.getWireId());
            output.writeBoolean(request.directed);
            if(request.directed) {
                output.writeFloat(request.aimX);
                output.writeFloat(request.aimY);
                output.writeFloat(request.aimZ);
                output.writeFloat(request.attackPower);
            }
            else {
                writeString(output, request.targetId,
                        DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                        "combat target identity");
            }
        }
        else if(message instanceof CombatStateMessage) {
            CombatStateMessage state = (CombatStateMessage)message;
            List<MonsterSnapshot> monsters = state.snapshot.getMonsters();
            List<CombatantSnapshot> combatants = state.snapshot.getCombatants();
            if(monsters.size() > DirectConnectProtocol.MAX_MONSTERS) {
                throw new ProtocolException("Combat state monster count is outside protocol bounds.");
            }
            if(combatants.size() > DirectConnectProtocol.MAX_COMBATANTS) {
                throw new ProtocolException("Combat state combatant count is outside protocol bounds.");
            }
            output.writeByte(COMBAT_STATE);
            writeString(output, state.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(state.snapshot.getSequence());
            output.writeLong(state.snapshot.getHostTick());
            output.writeByte(monsters.size());
            for(MonsterSnapshot monster : monsters) {
                writeString(output, monster.getId(),
                        DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                        "combat monster identity");
                writeString(output, monster.getTargetId(),
                        DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                        "combat monster target");
                output.writeFloat(monster.getX());
                output.writeFloat(monster.getY());
                output.writeFloat(monster.getZ());
                output.writeBoolean(monster.isGibbed());
            }
            output.writeByte(combatants.size());
            for(CombatantSnapshot combatant : combatants) {
                writeString(output, combatant.getId(),
                        DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES, "combatant identity");
                output.writeByte(combatant.getKind().getWireId());
                output.writeInt(combatant.getHealth());
                output.writeInt(combatant.getMaximumHealth());
            }
        }
        else if(message instanceof CombatPresentationMessage) {
            CombatPresentationMessage presentation = (CombatPresentationMessage)message;
            CombatPresentationEvent event = presentation.event;
            output.writeByte(COMBAT_PRESENTATION);
            writeString(output, presentation.sessionId,
                    DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
            output.writeLong(event.getSequence());
            output.writeLong(event.getHostTick());
            writeString(output, event.getSourceId(),
                    DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES, "combat source identity");
            writeString(output, event.getTargetId(),
                    DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES, "combat target identity");
            output.writeByte(event.getPhase().getWireId());
            output.writeByte(event.getAction().getWireId());
            output.writeFloat(event.getOriginX());
            output.writeFloat(event.getOriginY());
            output.writeFloat(event.getOriginZ());
            output.writeFloat(event.getImpactX());
            output.writeFloat(event.getImpactY());
            output.writeFloat(event.getImpactZ());
            output.writeBoolean(event.isStateChanged());
        }
        else if(message instanceof PartyStatusMessage) {
            PartyStatusMessage partyMessage = (PartyStatusMessage)message;
            List<PartyMemberStatus> members = partyMessage.snapshot.getMembers();
            if(members.isEmpty() || members.size() > DirectConnectProtocol.MAX_PARTY_MEMBERS) {
                throw new ProtocolException("Party status member count is outside protocol bounds.");
            }
            output.writeByte(PARTY_STATUS);
            writeString(output, partyMessage.sessionId,
                    DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
            output.writeLong(partyMessage.snapshot.getSequence());
            output.writeByte(members.size());
            for(PartyMemberStatus member : members) {
                output.writeByte(member.getCampaignSlot());
                output.writeBoolean(member.getEntityId() != null);
                if(member.getEntityId() != null) {
                    output.writeLong(member.getEntityId().getValue());
                }
                writeString(output, member.getNickname(),
                        DirectConnectProtocol.MAX_NICKNAME_BYTES, "Nickname");
                writeString(output, member.getAvatarId(),
                        DirectConnectProtocol.MAX_AVATAR_ID_BYTES, "Avatar identity");
                output.writeInt(member.getHealth());
                output.writeInt(member.getMaximumHealth());
                output.writeByte(member.getRemainingLives());
                output.writeByte(member.getState().getWireId());
            }
        }
        else if(message instanceof PartyChatSubmit) {
            PartyChatSubmit chat = (PartyChatSubmit)message;
            output.writeByte(PARTY_CHAT_SUBMIT);
            writeString(output, chat.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            writeString(output, PartyCommunicationText.requireChat(chat.text),
                    DirectConnectProtocol.MAX_PARTY_CHAT_BYTES, "Party chat");
        }
        else if(message instanceof PartyChatDelivery) {
            PartyChatDelivery chat = (PartyChatDelivery)message;
            PartyChatMessage delivery = chat.message;
            output.writeByte(PARTY_CHAT_DELIVERY);
            writeString(output, chat.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(delivery.getSequence());
            output.writeByte(delivery.getCampaignSlot());
            writeString(output, delivery.getNickname(), DirectConnectProtocol.MAX_NICKNAME_BYTES,
                    "Nickname");
            writeString(output, delivery.getText(), DirectConnectProtocol.MAX_PARTY_CHAT_BYTES,
                    "Party chat");
        }
        else if(message instanceof PauseRequestMessage) {
            PauseRequestMessage request = (PauseRequestMessage)message;
            output.writeByte(PAUSE_REQUEST);
            writeString(output, request.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
        }
        else if(message instanceof PauseRequestedMessage) {
            PauseRequestedMessage request = (PauseRequestedMessage)message;
            PauseRequest delivery = request.request;
            output.writeByte(PAUSE_REQUESTED);
            writeString(output, request.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(delivery.getSequence());
            output.writeByte(delivery.getCampaignSlot());
            writeString(output, delivery.getNickname(), DirectConnectProtocol.MAX_NICKNAME_BYTES,
                    "Nickname");
        }
        else if(message instanceof PauseSessionStateMessage) {
            PauseSessionStateMessage pause = (PauseSessionStateMessage)message;
            output.writeByte(PAUSE_SESSION_STATE);
            writeString(output, pause.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(pause.state.getSequence());
            output.writeBoolean(pause.state.isPaused());
        }
        else if(message instanceof ClientDisconnect) {
            output.writeByte(CLIENT_DISCONNECT);
            writeString(output, ((ClientDisconnect)message).reason,
                    DirectConnectProtocol.MAX_REASON_BYTES, "disconnect reason");
        }
        else if(message instanceof ServerDisconnect) {
            output.writeByte(SERVER_DISCONNECT);
            writeString(output, ((ServerDisconnect)message).reason,
                    DirectConnectProtocol.MAX_REASON_BYTES, "disconnect reason");
        }
        else {
            throw new ProtocolException("Wire message type is not allowlisted: "
                    + message.getClass().getName());
        }
    }

    private static Message decode(ByteBuf input) throws ProtocolException {
        requireReadable(input, 5, "message header");
        int magic = input.readInt();
        if(magic != DirectConnectProtocol.MAGIC) {
            throw new ProtocolException("Handshake magic did not match Direct Connect protocol.");
        }

        int type = input.readUnsignedByte();
        Message message;
        switch(type) {
            case DISCOVERY_PROBE:
                requireReadable(input, 12, "discovery probe identity");
                long probeNonce = input.readLong();
                int probeProtocol = input.readInt();
                if(probeNonce == 0L || probeProtocol < 1) {
                    throw new ProtocolException("Discovery probe identity is invalid.");
                }
                message = new DiscoveryProbe(probeNonce, probeProtocol,
                        readString(input, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                                "build identity"),
                        readString(input, DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES,
                                "content format"),
                        readString(input, DirectConnectProtocol.MAX_CONTENT_HASH_BYTES,
                                "content hash"));
                break;
            case DISCOVERY_ANNOUNCEMENT:
                requireReadable(input, 12, "discovery announcement identity");
                long announcementNonce = input.readLong();
                int announcementProtocol = input.readInt();
                if(announcementNonce == 0L || announcementProtocol < 1) {
                    throw new ProtocolException("Discovery announcement identity is invalid.");
                }
                String announcementBuild = readString(input,
                        DirectConnectProtocol.MAX_BUILD_ID_BYTES, "build identity");
                String announcementFormat = readString(input,
                        DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES, "content format");
                String announcementHash = readString(input,
                        DirectConnectProtocol.MAX_CONTENT_HASH_BYTES, "content hash");
                String announcementSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                String announcementCampaign = readString(input,
                        DirectConnectProtocol.MAX_CAMPAIGN_ID_BYTES, "campaign identity");
                requireReadable(input, 7, "discovery announcement lobby details");
                int announcementPort = input.readInt();
                int announcementCapacity = input.readUnsignedByte();
                int announcementClaimed = input.readUnsignedByte();
                boolean announcementLobbyOpen = input.readBoolean();
                if(announcementPort < 1 || announcementPort > 65535
                        || announcementCapacity < 2 || announcementCapacity > 4
                        || announcementClaimed < 1
                        || announcementClaimed > announcementCapacity) {
                    throw new ProtocolException(
                            "Discovery announcement is outside protocol bounds.");
                }
                message = new DiscoveryAnnouncement(announcementNonce,
                        announcementProtocol, announcementBuild, announcementFormat,
                        announcementHash, announcementSession, announcementCampaign,
                        announcementPort, announcementCapacity, announcementClaimed,
                        announcementLobbyOpen);
                break;
            case CLIENT_HELLO:
                requireReadable(input, 4, "protocol version");
                message = new ClientHello(input.readInt(),
                        readString(input, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                                "build identity"),
                        readString(input, DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES,
                                "content format"),
                        readString(input, DirectConnectProtocol.MAX_CONTENT_HASH_BYTES,
                                "content hash"),
                        readString(input, DirectConnectProtocol.MAX_LAUNCHER_IDENTITY_BYTES,
                                "Launcher Identity"));
                break;
            case CAMPAIGN_CHALLENGE:
                String challengeSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                String campaignId = readString(input,
                        DirectConnectProtocol.MAX_CAMPAIGN_ID_BYTES, "campaign identity");
                requireReadable(input, 1, "Campaign Capacity");
                int capacity = input.readUnsignedByte();
                if(capacity < 2 || capacity > 4) {
                    throw new ProtocolException("Campaign Capacity is outside protocol bounds.");
                }
                message = new CampaignChallenge(challengeSession, campaignId, capacity);
                break;
            case SLOT_CLAIM:
                String claimSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                String nickname = readString(input,
                        DirectConnectProtocol.MAX_NICKNAME_BYTES, "Nickname");
                String avatarId = readString(input,
                        DirectConnectProtocol.MAX_AVATAR_ID_BYTES, "Avatar identity");
                requireReadable(input, 1, "requested Campaign Slot");
                int requestedSlot = input.readUnsignedByte();
                if(requestedSlot > 4) {
                    throw new ProtocolException("Requested Campaign Slot is outside protocol bounds.");
                }
                message = new SlotClaim(claimSession, nickname, avatarId, requestedSlot,
                        readString(input, DirectConnectProtocol.MAX_RECONNECT_TOKEN_BYTES,
                                "reconnect credential"));
                break;
            case SLOT_PENDING:
                message = new SlotPending(readString(input,
                                DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity"),
                        readString(input, DirectConnectProtocol.MAX_REASON_BYTES,
                                "pending approval reason"));
                break;
            case SERVER_ACCEPTED:
                String acceptedSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 8, "UDP token");
                long acceptedUdpToken = input.readLong();
                String acceptedCampaign = readString(input,
                        DirectConnectProtocol.MAX_CAMPAIGN_ID_BYTES, "campaign identity");
                requireReadable(input, 1, "Campaign Slot number");
                int acceptedSlot = input.readUnsignedByte();
                if(acceptedSlot < 1 || acceptedSlot > 4) {
                    throw new ProtocolException("Campaign Slot number is outside protocol bounds.");
                }
                message = new ServerAccepted(acceptedSession, acceptedUdpToken,
                        acceptedCampaign, acceptedSlot,
                        readString(input, DirectConnectProtocol.MAX_RECONNECT_TOKEN_BYTES,
                                "reconnect credential"));
                break;
            case SERVER_REJECTED:
                requireReadable(input, 1, "rejection code");
                RejectCode code = RejectCode.fromId(input.readUnsignedByte());
                message = new ServerRejected(code,
                        readString(input, DirectConnectProtocol.MAX_REASON_BYTES,
                                "rejection reason"));
                break;
            case UDP_REGISTER:
                String registerSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 8, "UDP token");
                message = new UdpRegister(registerSession, input.readLong());
                break;
            case UDP_REGISTERED:
                String registeredSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 8, "UDP token");
                message = new UdpRegistered(registeredSession, input.readLong());
                break;
            case SESSION_READY:
                String readySession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 1, "Participant count");
                int participantCount = input.readUnsignedByte();
                if(participantCount < 1 || participantCount > 4) {
                    throw new ProtocolException("Participant count is outside protocol bounds.");
                }
                requireReadable(input, 8, "Combat request ID floor");
                long nextCombatRequestId = input.readLong();
                if(nextCombatRequestId < 1L) {
                    throw new ProtocolException(
                            "Combat request ID floor is outside protocol bounds.");
                }
                message = new SessionReady(readySession, participantCount,
                        nextCombatRequestId,
                        readString(input, DirectConnectProtocol.MAX_FLOOR_ID_BYTES,
                                "floor identity"));
                break;
            case ENTITY_SPAWN:
                String spawnSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 17, "Entity spawn identity");
                long spawnSequence = input.readLong();
                long spawnEntityId = input.readLong();
                String spawnParticipant = readString(input,
                        DirectConnectProtocol.MAX_PARTICIPANT_ID_BYTES,
                        "Participant identity");
                int spawnSlot = input.readUnsignedByte();
                String spawnNickname = readString(input,
                        DirectConnectProtocol.MAX_NICKNAME_BYTES, "Nickname");
                String spawnAvatar = readString(input,
                        DirectConnectProtocol.MAX_AVATAR_ID_BYTES, "Avatar identity");
                try {
                    message = new EntitySpawn(spawnSession, new MovementEntityDescriptor(
                            spawnSequence, new NetworkEntityId(spawnEntityId),
                            new ParticipantId(spawnParticipant), spawnSlot,
                            spawnNickname, spawnAvatar));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed Entity spawn: " + ex.getMessage(), ex);
                }
                break;
            case ENTITY_DESPAWN:
                String despawnSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 16, "Entity despawn identity");
                long despawnSequence = input.readLong();
                long despawnEntityId = input.readLong();
                try {
                    message = new EntityDespawn(despawnSession, despawnSequence,
                            new NetworkEntityId(despawnEntityId));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed Entity despawn: " + ex.getMessage(), ex);
                }
                break;
            case MOVEMENT_INPUTS:
                String inputSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 9, "Movement input bundle header");
                long inputToken = input.readLong();
                int inputCount = input.readUnsignedByte();
                if(inputCount < 1 || inputCount > DirectConnectProtocol.MAX_INPUT_FRAMES) {
                    throw new ProtocolException("Movement input bundle count is outside protocol bounds.");
                }
                List<MovementInputFrame> inputFrames = new ArrayList<MovementInputFrame>();
                for(int i = 0; i < inputCount; i++) {
                    requireReadable(input, 21, "Movement input frame");
                    long inputTick = input.readLong();
                    float forward = input.readFloat();
                    float strafe = input.readFloat();
                    float rotation = input.readFloat();
                    boolean jump = input.readBoolean();
                    try {
                        inputFrames.add(new MovementInputFrame(inputTick, forward,
                                strafe, rotation, jump));
                    }
                    catch(IllegalArgumentException ex) {
                        throw new ProtocolException("Malformed movement input: "
                                + ex.getMessage(), ex);
                    }
                }
                message = new MovementInputs(inputSession, inputToken, inputFrames);
                break;
            case MOVEMENT_SNAPSHOT:
                String snapshotSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 17, "Movement snapshot header");
                long snapshotSequence = input.readLong();
                long snapshotHostTick = input.readLong();
                int entityCount = input.readUnsignedByte();
                if(entityCount > DirectConnectProtocol.MAX_MOVEMENT_ENTITIES) {
                    throw new ProtocolException("Movement snapshot entity count is outside protocol bounds.");
                }
                List<MovementEntityState> movementEntities =
                        new ArrayList<MovementEntityState>();
                for(int i = 0; i < entityCount; i++) {
                    requireReadable(input, 53, "Movement snapshot entity");
                    long entityId = input.readLong();
                    long lifecycleSequence = input.readLong();
                    long acknowledgedInput = input.readLong();
                    float x = input.readFloat();
                    float y = input.readFloat();
                    float z = input.readFloat();
                    float velocityX = input.readFloat();
                    float velocityY = input.readFloat();
                    float velocityZ = input.readFloat();
                    float rotation = input.readFloat();
                    int movementState = input.readUnsignedByte();
                    try {
                        movementEntities.add(new MovementEntityState(
                                new NetworkEntityId(entityId), lifecycleSequence,
                                acknowledgedInput, x, y, z, velocityX, velocityY,
                                velocityZ, rotation, MovementState.fromWireId(movementState)));
                    }
                    catch(IllegalArgumentException ex) {
                        throw new ProtocolException("Malformed movement snapshot: "
                                + ex.getMessage(), ex);
                    }
                }
                try {
                    message = new MovementSnapshotMessage(snapshotSession,
                            new MovementSnapshot(snapshotSequence, snapshotHostTick,
                                    movementEntities));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed movement snapshot: "
                            + ex.getMessage(), ex);
                }
                break;
            case COMBAT_ACTION_REQUEST:
                String combatRequestSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 10, "combat action request");
                long combatRequestId = input.readLong();
                int combatAction = input.readUnsignedByte();
                boolean directedCombat = input.readBoolean();
                try {
                    if(directedCombat) {
                        requireReadable(input, 16, "directed combat aim and attack power");
                        message = new CombatActionRequestMessage(combatRequestSession,
                                combatRequestId, CombatAction.fromWireId(combatAction),
                                input.readFloat(), input.readFloat(), input.readFloat(),
                                input.readFloat());
                    }
                    else {
                        String combatTarget = readString(input,
                                DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                                "combat target identity");
                        message = new CombatActionRequestMessage(combatRequestSession,
                                combatRequestId, CombatAction.fromWireId(combatAction),
                                combatTarget);
                    }
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed combat action request: "
                            + ex.getMessage(), ex);
                }
                break;
            case COMBAT_STATE:
                String combatStateSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 17, "combat state header");
                long combatStateSequence = input.readLong();
                long combatStateTick = input.readLong();
                int monsterCount = input.readUnsignedByte();
                if(monsterCount > DirectConnectProtocol.MAX_MONSTERS) {
                    throw new ProtocolException("Combat state monster count is outside protocol bounds.");
                }
                List<MonsterSnapshot> monsters = new ArrayList<MonsterSnapshot>();
                for(int i = 0; i < monsterCount; i++) {
                    String monsterId = readString(input,
                            DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                            "combat monster identity");
                    String combatTargetId = readString(input,
                            DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                            "combat monster target");
                    requireReadable(input, 13, "combat monster transform and corpse state");
                    float combatMonsterX = input.readFloat();
                    float combatMonsterY = input.readFloat();
                    float combatMonsterZ = input.readFloat();
                    boolean combatMonsterGibbed = input.readBoolean();
                    try {
                        monsters.add(new MonsterSnapshot(monsterId, combatTargetId,
                                combatMonsterX, combatMonsterY, combatMonsterZ,
                                combatMonsterGibbed));
                    }
                    catch(IllegalArgumentException ex) {
                        throw new ProtocolException("Malformed combat monster state: "
                                + ex.getMessage(), ex);
                    }
                }
                requireReadable(input, 1, "combatant count");
                int combatantCount = input.readUnsignedByte();
                if(combatantCount < 1 || combatantCount > DirectConnectProtocol.MAX_COMBATANTS) {
                    throw new ProtocolException("Combat state combatant count is outside protocol bounds.");
                }
                List<CombatantSnapshot> combatants = new ArrayList<CombatantSnapshot>();
                for(int i = 0; i < combatantCount; i++) {
                    String combatantId = readString(input,
                            DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES, "combatant identity");
                    requireReadable(input, 9, "combatant state");
                    int combatantKind = input.readUnsignedByte();
                    int combatantHealth = input.readInt();
                    int combatantMaximumHealth = input.readInt();
                    try {
                        combatants.add(new CombatantSnapshot(combatantId,
                                CombatantKind.fromWireId(combatantKind), combatantHealth,
                                combatantMaximumHealth));
                    }
                    catch(IllegalArgumentException ex) {
                        throw new ProtocolException("Malformed combatant state: "
                                + ex.getMessage(), ex);
                    }
                }
                try {
                    message = new CombatStateMessage(combatStateSession,
                            new CombatSnapshot(combatStateSequence, combatStateTick,
                                    monsters, combatants));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed combat state: "
                            + ex.getMessage(), ex);
                }
                break;
            case COMBAT_PRESENTATION:
                String presentationSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 16, "combat presentation identity");
                long presentationSequence = input.readLong();
                long presentationTick = input.readLong();
                String presentationSource = readString(input,
                        DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                        "combat source identity");
                String presentationTarget = readString(input,
                        DirectConnectProtocol.MAX_COMBAT_TARGET_ID_BYTES,
                        "combat target identity");
                requireReadable(input, 27, "combat presentation phase, action and transform");
                int presentationPhase = input.readUnsignedByte();
                int presentationAction = input.readUnsignedByte();
                float presentationOriginX = input.readFloat();
                float presentationOriginY = input.readFloat();
                float presentationOriginZ = input.readFloat();
                float presentationImpactX = input.readFloat();
                float presentationImpactY = input.readFloat();
                float presentationImpactZ = input.readFloat();
                boolean presentationChanged = input.readBoolean();
                try {
                    message = new CombatPresentationMessage(presentationSession,
                            new CombatPresentationEvent(presentationSequence,
                                    presentationTick, presentationSource,
                                    presentationTarget,
                                    CombatAction.fromWireId(presentationAction),
                                    CombatPresentationPhase.fromWireId(presentationPhase),
                                    presentationOriginX, presentationOriginY,
                                    presentationOriginZ, presentationImpactX,
                                    presentationImpactY, presentationImpactZ,
                                    presentationChanged));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed combat presentation: "
                            + ex.getMessage(), ex);
                }
                break;
            case PARTY_STATUS:
                String partySession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 9, "Party status header");
                long partySequence = input.readLong();
                int partyMemberCount = input.readUnsignedByte();
                if(partyMemberCount < 1
                        || partyMemberCount > DirectConnectProtocol.MAX_PARTY_MEMBERS) {
                    throw new ProtocolException(
                            "Party status member count is outside protocol bounds.");
                }
                List<PartyMemberStatus> partyMembers =
                        new ArrayList<PartyMemberStatus>();
                for(int i = 0; i < partyMemberCount; i++) {
                    requireReadable(input, 2, "Party member identity");
                    int partySlot = input.readUnsignedByte();
                    boolean hasPartyEntity = input.readBoolean();
                    Long partyEntityValue = null;
                    if(hasPartyEntity) {
                        requireReadable(input, 8, "Party member Entity identity");
                        partyEntityValue = input.readLong();
                    }
                    String partyNickname = readString(input,
                            DirectConnectProtocol.MAX_NICKNAME_BYTES, "Nickname");
                    String partyAvatar = readString(input,
                            DirectConnectProtocol.MAX_AVATAR_ID_BYTES, "Avatar identity");
                    requireReadable(input, 10, "Party member status");
                    int partyHealth = input.readInt();
                    int partyMaximumHealth = input.readInt();
                    int partyLives = input.readUnsignedByte();
                    int partyState = input.readUnsignedByte();
                    try {
                        NetworkEntityId partyEntity = partyEntityValue == null
                                ? null : new NetworkEntityId(partyEntityValue);
                        partyMembers.add(new PartyMemberStatus(partySlot, partyEntity,
                                partyNickname, partyAvatar, partyHealth,
                                partyMaximumHealth, partyLives,
                                PartyMemberState.fromWireId(partyState)));
                    }
                    catch(IllegalArgumentException ex) {
                        throw new ProtocolException("Malformed Party status: "
                                + ex.getMessage(), ex);
                    }
                }
                try {
                    message = new PartyStatusMessage(partySession,
                            new PartyStatusSnapshot(partySequence, partyMembers));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed Party status: "
                            + ex.getMessage(), ex);
                }
                break;
            case PARTY_CHAT_SUBMIT:
                message = new PartyChatSubmit(readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity"),
                        requireChat(readString(input,
                                DirectConnectProtocol.MAX_PARTY_CHAT_BYTES, "Party chat")));
                break;
            case PARTY_CHAT_DELIVERY:
                String chatSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 9, "Party chat delivery identity");
                long chatSequence = input.readLong();
                int chatSlot = input.readUnsignedByte();
                String chatNickname = readString(input,
                        DirectConnectProtocol.MAX_NICKNAME_BYTES, "Nickname");
                String chatText = requireChat(readString(input,
                        DirectConnectProtocol.MAX_PARTY_CHAT_BYTES, "Party chat"));
                try {
                    message = new PartyChatDelivery(chatSession,
                            new PartyChatMessage(chatSequence, chatSlot, chatNickname, chatText));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed Party chat: " + ex.getMessage(), ex);
                }
                break;
            case PAUSE_REQUEST:
                message = new PauseRequestMessage(readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity"));
                break;
            case PAUSE_REQUESTED:
                String pauseRequestSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 9, "Pause request identity");
                long pauseRequestSequence = input.readLong();
                int pauseRequestSlot = input.readUnsignedByte();
                String pauseRequestNickname = readString(input,
                        DirectConnectProtocol.MAX_NICKNAME_BYTES, "Nickname");
                try {
                    message = new PauseRequestedMessage(pauseRequestSession,
                            new PauseRequest(pauseRequestSequence, pauseRequestSlot,
                                    pauseRequestNickname));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed Pause request: " + ex.getMessage(), ex);
                }
                break;
            case PAUSE_SESSION_STATE:
                String pauseSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 9, "Pause Session state");
                try {
                    message = new PauseSessionStateMessage(pauseSession,
                            new PauseSessionState(input.readLong(), input.readBoolean()));
                }
                catch(IllegalArgumentException ex) {
                    throw new ProtocolException("Malformed Pause Session state: "
                            + ex.getMessage(), ex);
                }
                break;
            case CLIENT_DISCONNECT:
                message = new ClientDisconnect(readString(input,
                        DirectConnectProtocol.MAX_REASON_BYTES, "disconnect reason"));
                break;
            case SERVER_DISCONNECT:
                message = new ServerDisconnect(readString(input,
                        DirectConnectProtocol.MAX_REASON_BYTES, "disconnect reason"));
                break;
            default:
                throw new ProtocolException("Unknown Direct Connect message type: " + type);
        }

        if(input.isReadable()) {
            throw new ProtocolException("Wire message contained trailing bytes.");
        }
        return message;
    }

    private static void writeString(ByteBuf output, String value, int maximumBytes, String label)
            throws ProtocolException {
        if(value == null) throw new ProtocolException(label + " cannot be null.");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if(encoded.length > maximumBytes) {
            throw new ProtocolException(label + " exceeded " + maximumBytes + " bytes.");
        }
        output.writeShort(encoded.length);
        output.writeBytes(encoded);
    }

    private static String readString(ByteBuf input, int maximumBytes, String label)
            throws ProtocolException {
        requireReadable(input, 2, label + " length");
        int length = input.readUnsignedShort();
        if(length > maximumBytes) {
            throw new ProtocolException(label + " exceeded " + maximumBytes + " bytes.");
        }
        requireReadable(input, length, label);
        ByteBuffer bytes = input.readSlice(length).nioBuffer();
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes);
            return decoded.toString();
        }
        catch(CharacterCodingException ex) {
            throw new ProtocolException(label + " was not valid UTF-8.", ex);
        }
    }

    private static void requireReadable(ByteBuf input, int bytes, String label)
            throws ProtocolException {
        if(bytes < 0 || input.readableBytes() < bytes) {
            throw new ProtocolException("Wire message ended inside " + label + ".");
        }
    }

    private static String requireChat(String value) throws ProtocolException {
        try {
            return PartyCommunicationText.requireChat(value);
        }
        catch(IllegalArgumentException ex) {
            throw new ProtocolException("Malformed Party chat: " + ex.getMessage(), ex);
        }
    }

    interface Message { }

    static final class DiscoveryProbe implements Message {
        final long nonce;
        final int protocolVersion;
        final String buildId;
        final String contentFormat;
        final String contentSha256;

        DiscoveryProbe(long nonce, int protocolVersion, String buildId,
                String contentFormat, String contentSha256) {
            this.nonce = nonce;
            this.protocolVersion = protocolVersion;
            this.buildId = buildId;
            this.contentFormat = contentFormat;
            this.contentSha256 = contentSha256;
        }
    }

    static final class DiscoveryAnnouncement implements Message {
        final long nonce;
        final int protocolVersion;
        final String buildId;
        final String contentFormat;
        final String contentSha256;
        final String sessionId;
        final String campaignId;
        final int port;
        final int capacity;
        final int claimedSlots;
        final boolean lobbyOpen;

        DiscoveryAnnouncement(long nonce, int protocolVersion, String buildId,
                String contentFormat, String contentSha256, String sessionId,
                String campaignId, int port, int capacity, int claimedSlots,
                boolean lobbyOpen) {
            this.nonce = nonce;
            this.protocolVersion = protocolVersion;
            this.buildId = buildId;
            this.contentFormat = contentFormat;
            this.contentSha256 = contentSha256;
            this.sessionId = sessionId;
            this.campaignId = campaignId;
            this.port = port;
            this.capacity = capacity;
            this.claimedSlots = claimedSlots;
            this.lobbyOpen = lobbyOpen;
        }
    }

    static final class ClientHello implements Message {
        final int protocolVersion;
        final String buildId;
        final String contentFormat;
        final String contentSha256;
        final String launcherIdentity;

        ClientHello(int protocolVersion, String buildId, String contentFormat,
                String contentSha256, String launcherIdentity) {
            this.protocolVersion = protocolVersion;
            this.buildId = buildId;
            this.contentFormat = contentFormat;
            this.contentSha256 = contentSha256;
            this.launcherIdentity = launcherIdentity;
        }
    }

    static final class CampaignChallenge implements Message {
        final String sessionId;
        final String campaignId;
        final int capacity;

        CampaignChallenge(String sessionId, String campaignId, int capacity) {
            this.sessionId = sessionId;
            this.campaignId = campaignId;
            this.capacity = capacity;
        }
    }

    static final class SlotClaim implements Message {
        final String sessionId;
        final String nickname;
        final String avatarId;
        final int requestedSlot;
        final String reconnectToken;

        SlotClaim(String sessionId, String nickname, String avatarId, int requestedSlot,
                String reconnectToken) {
            this.sessionId = sessionId;
            this.nickname = nickname;
            this.avatarId = avatarId;
            this.requestedSlot = requestedSlot;
            this.reconnectToken = reconnectToken == null ? "" : reconnectToken;
        }
    }

    static final class SlotPending implements Message {
        final String sessionId;
        final String reason;

        SlotPending(String sessionId, String reason) {
            this.sessionId = sessionId;
            this.reason = reason;
        }
    }

    static final class ServerAccepted implements Message {
        final String sessionId;
        final long udpToken;
        final String campaignId;
        final int slotNumber;
        final String reconnectToken;

        ServerAccepted(String sessionId, long udpToken, String campaignId, int slotNumber,
                String reconnectToken) {
            this.sessionId = sessionId;
            this.udpToken = udpToken;
            this.campaignId = campaignId;
            this.slotNumber = slotNumber;
            this.reconnectToken = reconnectToken;
        }
    }

    enum RejectCode {
        PROTOCOL_MISMATCH(1),
        BUILD_MISMATCH(2),
        CONTENT_MISMATCH(3),
        MALFORMED_HANDSHAKE(4),
        SESSION_FULL(5),
        CAMPAIGN_FULL(6),
        SLOT_OCCUPIED(7),
        RECONNECT_DENIED(8),
        NICKNAME_TAKEN(9),
        AVATAR_UNAVAILABLE(10),
        APPROVAL_DECLINED(11),
        IDENTITY_IN_USE(12),
        NOT_IN_LOBBY(13);

        final int id;

        RejectCode(int id) {
            this.id = id;
        }

        static RejectCode fromId(int id) throws ProtocolException {
            for(RejectCode code : values()) {
                if(code.id == id) return code;
            }
            throw new ProtocolException("Unknown handshake rejection code: " + id);
        }
    }

    static final class ServerRejected implements Message {
        final RejectCode code;
        final String reason;

        ServerRejected(RejectCode code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

    static final class UdpRegister implements Message {
        final String sessionId;
        final long udpToken;

        UdpRegister(String sessionId, long udpToken) {
            this.sessionId = sessionId;
            this.udpToken = udpToken;
        }
    }

    static final class UdpRegistered implements Message {
        final String sessionId;
        final long udpToken;

        UdpRegistered(String sessionId, long udpToken) {
            this.sessionId = sessionId;
            this.udpToken = udpToken;
        }
    }

    static final class SessionReady implements Message {
        final String sessionId;
        final int participantCount;
        final long nextCombatRequestId;
        final String floorId;

        SessionReady(String sessionId, int participantCount, long nextCombatRequestId,
                String floorId) {
            if(nextCombatRequestId < 1L) {
                throw new IllegalArgumentException("Combat request ID floor must be positive.");
            }
            this.sessionId = sessionId;
            this.participantCount = participantCount;
            this.nextCombatRequestId = nextCombatRequestId;
            this.floorId = floorId;
        }
    }

    static final class EntitySpawn implements Message {
        final String sessionId;
        final MovementEntityDescriptor descriptor;

        EntitySpawn(String sessionId, MovementEntityDescriptor descriptor) {
            this.sessionId = sessionId;
            this.descriptor = descriptor;
        }
    }

    static final class EntityDespawn implements Message {
        final String sessionId;
        final long lifecycleSequence;
        final NetworkEntityId entityId;

        EntityDespawn(String sessionId, long lifecycleSequence, NetworkEntityId entityId) {
            if(lifecycleSequence <= 0L) {
                throw new IllegalArgumentException("Entity lifecycle sequence must be positive.");
            }
            if(entityId == null) throw new IllegalArgumentException("Network Entity ID cannot be null.");
            this.sessionId = sessionId;
            this.lifecycleSequence = lifecycleSequence;
            this.entityId = entityId;
        }
    }

    static final class MovementInputs implements Message {
        final String sessionId;
        final long udpToken;
        final List<MovementInputFrame> inputs;

        MovementInputs(String sessionId, long udpToken, List<MovementInputFrame> inputs) {
            if(inputs == null) throw new IllegalArgumentException("Movement inputs cannot be null.");
            this.sessionId = sessionId;
            this.udpToken = udpToken;
            this.inputs = Collections.unmodifiableList(
                    new ArrayList<MovementInputFrame>(inputs));
        }
    }

    static final class MovementSnapshotMessage implements Message {
        final String sessionId;
        final MovementSnapshot snapshot;

        MovementSnapshotMessage(String sessionId, MovementSnapshot snapshot) {
            if(snapshot == null) throw new IllegalArgumentException("Movement snapshot cannot be null.");
            this.sessionId = sessionId;
            this.snapshot = snapshot;
        }
    }

    static final class CombatActionRequestMessage implements Message {
        final String sessionId;
        final long requestId;
        final CombatAction action;
        final String targetId;
        final boolean directed;
        final float aimX;
        final float aimY;
        final float aimZ;
        final float attackPower;

        CombatActionRequestMessage(String sessionId, long requestId,
                CombatAction action, String targetId) {
            if(!CombatRequest.isValidRequestId(requestId) || action == null
                    || !action.allowsTargetedRequest()
                    || targetId == null || targetId.trim().isEmpty()) {
                throw new IllegalArgumentException("Combat action request is invalid.");
            }
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.action = action;
            this.targetId = targetId;
            directed = false;
            aimX = 0f;
            aimY = 0f;
            aimZ = 0f;
            attackPower = 1f;
        }

        CombatActionRequestMessage(String sessionId, long requestId,
                CombatAction action, float aimX, float aimY, float aimZ) {
            this(sessionId, requestId, action, aimX, aimY, aimZ, 1f);
        }

        CombatActionRequestMessage(String sessionId, long requestId,
                CombatAction action, float aimX, float aimY, float aimZ,
                float attackPower) {
            float aimLengthSquared = aimX * aimX + aimY * aimY + aimZ * aimZ;
            if(!CombatRequest.isValidRequestId(requestId) || action == null
                    || !action.isDirected() || !isFinite(aimX) || !isFinite(aimY)
                    || !isFinite(aimZ) || !isFinite(aimLengthSquared)
                    || aimLengthSquared < 0.000001f || aimLengthSquared > 3.01f
                    || !isFinite(attackPower) || attackPower < 0f
                    || attackPower > CombatRequest.MAX_ATTACK_POWER) {
                throw new IllegalArgumentException("Directed combat action request is invalid.");
            }
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.action = action;
            targetId = "";
            directed = true;
            this.aimX = aimX;
            this.aimY = aimY;
            this.aimZ = aimZ;
            this.attackPower = attackPower;
        }

        private static boolean isFinite(float value) {
            return !Float.isNaN(value) && !Float.isInfinite(value);
        }
    }

    static final class CombatStateMessage implements Message {
        final String sessionId;
        final CombatSnapshot snapshot;

        CombatStateMessage(String sessionId, CombatSnapshot snapshot) {
            if(snapshot == null) throw new IllegalArgumentException("Combat snapshot cannot be null.");
            this.sessionId = sessionId;
            this.snapshot = snapshot;
        }
    }

    static final class CombatPresentationMessage implements Message {
        final String sessionId;
        final CombatPresentationEvent event;

        CombatPresentationMessage(String sessionId, CombatPresentationEvent event) {
            if(event == null) {
                throw new IllegalArgumentException("Combat presentation cannot be null.");
            }
            this.sessionId = sessionId;
            this.event = event;
        }
    }

    static final class PartyStatusMessage implements Message {
        final String sessionId;
        final PartyStatusSnapshot snapshot;

        PartyStatusMessage(String sessionId, PartyStatusSnapshot snapshot) {
            if(snapshot == null) {
                throw new IllegalArgumentException("Party status snapshot cannot be null.");
            }
            this.sessionId = sessionId;
            this.snapshot = snapshot;
        }
    }

    static final class PartyChatSubmit implements Message {
        final String sessionId;
        final String text;

        PartyChatSubmit(String sessionId, String text) {
            this.sessionId = sessionId;
            this.text = PartyCommunicationText.requireChat(text);
        }
    }

    static final class PartyChatDelivery implements Message {
        final String sessionId;
        final PartyChatMessage message;

        PartyChatDelivery(String sessionId, PartyChatMessage message) {
            if(message == null) throw new IllegalArgumentException("Party chat cannot be null.");
            this.sessionId = sessionId;
            this.message = message;
        }
    }

    static final class PauseRequestMessage implements Message {
        final String sessionId;

        PauseRequestMessage(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    static final class PauseRequestedMessage implements Message {
        final String sessionId;
        final PauseRequest request;

        PauseRequestedMessage(String sessionId, PauseRequest request) {
            if(request == null) throw new IllegalArgumentException("Pause request cannot be null.");
            this.sessionId = sessionId;
            this.request = request;
        }
    }

    static final class PauseSessionStateMessage implements Message {
        final String sessionId;
        final PauseSessionState state;

        PauseSessionStateMessage(String sessionId, PauseSessionState state) {
            if(state == null) throw new IllegalArgumentException("Pause Session state cannot be null.");
            this.sessionId = sessionId;
            this.state = state;
        }
    }

    static final class ClientDisconnect implements Message {
        final String reason;

        ClientDisconnect(String reason) {
            this.reason = reason;
        }
    }

    static final class ServerDisconnect implements Message {
        final String reason;

        ServerDisconnect(String reason) {
            this.reason = reason;
        }
    }

    static final class ProtocolException extends Exception {
        ProtocolException(String message) {
            super(message);
        }

        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class TcpMessageDecoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(io.netty.channel.ChannelHandlerContext context, ByteBuf input,
                List<Object> output) throws Exception {
            output.add(DirectConnectWire.decode(input));
        }
    }

    private static final class TcpMessageEncoder extends MessageToByteEncoder<Message> {
        @Override
        protected void encode(io.netty.channel.ChannelHandlerContext context, Message message,
                ByteBuf output) throws Exception {
            DirectConnectWire.encode(message, output);
            if(output.readableBytes() > DirectConnectProtocol.MAX_TCP_FRAME_BYTES) {
                throw new ProtocolException("TCP frame exceeded protocol size bound.");
            }
        }
    }
}
