package com.interrupt.dungeoneer.multiplayer.network;

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
            if(output.readableBytes() > DirectConnectProtocol.MAX_TCP_FRAME_BYTES) {
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
        if(input.readableBytes() > DirectConnectProtocol.MAX_TCP_FRAME_BYTES) {
            throw new ProtocolException("Datagram exceeded protocol size bound.");
        }
        return decode(input);
    }

    private static void encode(Message message, ByteBuf output) throws ProtocolException {
        if(message == null) throw new ProtocolException("Wire message cannot be null.");
        output.writeInt(DirectConnectProtocol.MAGIC);

        if(message instanceof ClientHello) {
            ClientHello hello = (ClientHello)message;
            output.writeByte(CLIENT_HELLO);
            output.writeInt(hello.protocolVersion);
            writeString(output, hello.buildId, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                    "build identity");
            writeString(output, hello.contentFormat,
                    DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES, "content format");
            writeString(output, hello.contentSha256,
                    DirectConnectProtocol.MAX_CONTENT_HASH_BYTES, "content hash");
            writeString(output, hello.participantId,
                    DirectConnectProtocol.MAX_PARTICIPANT_ID_BYTES, "Participant identity");
        }
        else if(message instanceof ServerAccepted) {
            ServerAccepted accepted = (ServerAccepted)message;
            output.writeByte(SERVER_ACCEPTED);
            writeString(output, accepted.sessionId, DirectConnectProtocol.MAX_SESSION_ID_BYTES,
                    "session identity");
            output.writeLong(accepted.udpToken);
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
            writeString(output, ready.floorId, DirectConnectProtocol.MAX_FLOOR_ID_BYTES,
                    "floor identity");
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
            case CLIENT_HELLO:
                requireReadable(input, 4, "protocol version");
                message = new ClientHello(input.readInt(),
                        readString(input, DirectConnectProtocol.MAX_BUILD_ID_BYTES,
                                "build identity"),
                        readString(input, DirectConnectProtocol.MAX_CONTENT_FORMAT_BYTES,
                                "content format"),
                        readString(input, DirectConnectProtocol.MAX_CONTENT_HASH_BYTES,
                                "content hash"),
                        readString(input, DirectConnectProtocol.MAX_PARTICIPANT_ID_BYTES,
                                "Participant identity"));
                break;
            case SERVER_ACCEPTED:
                String acceptedSession = readString(input,
                        DirectConnectProtocol.MAX_SESSION_ID_BYTES, "session identity");
                requireReadable(input, 8, "UDP token");
                message = new ServerAccepted(acceptedSession, input.readLong());
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
                message = new SessionReady(readySession, participantCount,
                        readString(input, DirectConnectProtocol.MAX_FLOOR_ID_BYTES,
                                "floor identity"));
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

    interface Message { }

    static final class ClientHello implements Message {
        final int protocolVersion;
        final String buildId;
        final String contentFormat;
        final String contentSha256;
        final String participantId;

        ClientHello(int protocolVersion, String buildId, String contentFormat,
                String contentSha256, String participantId) {
            this.protocolVersion = protocolVersion;
            this.buildId = buildId;
            this.contentFormat = contentFormat;
            this.contentSha256 = contentSha256;
            this.participantId = participantId;
        }
    }

    static final class ServerAccepted implements Message {
        final String sessionId;
        final long udpToken;

        ServerAccepted(String sessionId, long udpToken) {
            this.sessionId = sessionId;
            this.udpToken = udpToken;
        }
    }

    enum RejectCode {
        PROTOCOL_MISMATCH(1),
        BUILD_MISMATCH(2),
        CONTENT_MISMATCH(3),
        MALFORMED_HANDSHAKE(4),
        SESSION_FULL(5);

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
        final String floorId;

        SessionReady(String sessionId, int participantCount, String floorId) {
            this.sessionId = sessionId;
            this.participantCount = participantCount;
            this.floorId = floorId;
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
