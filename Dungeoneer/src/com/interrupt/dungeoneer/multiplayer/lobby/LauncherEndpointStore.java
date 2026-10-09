package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import java.io.File;
import java.util.Properties;

/** Last direct endpoint is editable profile preference, never identity or ownership. */
public final class LauncherEndpointStore {
    private static final String PATH = "settings/multiplayer-endpoint.properties";
    private LauncherEndpointStore() { }

    public static final class Endpoint {
        private final String address;
        private final int port;

        public Endpoint(String address, int port) {
            if(port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1-65535.");
            if(address == null) throw new IllegalArgumentException("Enter Host address.");
            String value = address.trim();
            if(value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
            if(value.isEmpty() || value.length() > 253) throw new IllegalArgumentException("Enter Host address (maximum 253 characters).");
            for(int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if(Character.isWhitespace(c) || Character.isISOControl(c) || "/\\@?#[]".indexOf(c) >= 0)
                    throw new IllegalArgumentException("Enter hostname or IP address; use separate Port field.");
            }
            if(value.indexOf(':') >= 0 && value.indexOf(':') == value.lastIndexOf(':'))
                throw new IllegalArgumentException("Enter hostname or IP address; use separate Port field.");
            this.address = value;
            this.port = port;
        }

        public String getAddress() { return address; }
        public int getPort() { return port; }
    }

    public static Endpoint load() {
        Endpoint defaults = new Endpoint("127.0.0.1", DirectConnectProtocol.DEFAULT_PORT);
        if(!MultiplayerProfile.isInitialized()) return defaults;
        File file = MultiplayerProfile.resolveWritableFile(PATH).file();
        if(!file.isFile()) return defaults;
        try {
            Properties values = AtomicProperties.load(file, "multiplayer endpoint defaults");
            return new Endpoint(values.getProperty("address"), Integer.parseInt(values.getProperty("port", "")));
        }
        catch(IllegalArgumentException | IllegalStateException invalid) { return defaults; }
    }

    public static void save(String address, int port) {
        Endpoint endpoint = new Endpoint(address, port);
        if(!MultiplayerProfile.isInitialized()) throw new IllegalStateException("Multiplayer profile is not initialized.");
        Properties values = new Properties();
        values.setProperty("address", endpoint.getAddress());
        values.setProperty("port", Integer.toString(endpoint.getPort()));
        AtomicProperties.store(MultiplayerProfile.resolveWritableFile(PATH).file(), values,
                "Delver Multiplayer editable direct endpoint");
    }
}
