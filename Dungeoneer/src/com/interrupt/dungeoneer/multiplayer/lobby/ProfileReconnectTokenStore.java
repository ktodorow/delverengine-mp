package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Private participant-local reconnect credentials. Values are never presentation identities. */
public final class ProfileReconnectTokenStore implements ReconnectTokenStore {
    private static final String CREDENTIALS_PATH = "identities/campaign-reconnect.properties";
    private static final String FORMAT = "1";

    private final File file;

    public ProfileReconnectTokenStore() {
        if(!MultiplayerProfile.isInitialized()) {
            throw new IllegalStateException("Multiplayer profile is not initialized.");
        }
        file = MultiplayerProfile.resolveWritableFile(CREDENTIALS_PATH).file();
    }

    ProfileReconnectTokenStore(File file) {
        this.file = file;
    }

    @Override
    public synchronized String load(String campaignId) {
        CampaignRoster.requireCampaignId(campaignId);
        if(!file.exists()) return null;
        Properties properties = AtomicProperties.load(file, "campaign reconnect credentials");
        if(!FORMAT.equals(properties.getProperty("format"))) {
            throw new IllegalStateException("Reconnect credential file has unsupported format: " + file);
        }
        String token = properties.getProperty("campaign." + campaignId);
        if(token == null) return null;
        if(!PrivateToken.isValid(token)) {
            throw new IllegalStateException("Reconnect credential file is corrupt: " + file);
        }
        return token;
    }

    @Override
    public synchronized void save(String campaignId, String reconnectToken) {
        CampaignRoster.requireCampaignId(campaignId);
        if(!PrivateToken.isValid(reconnectToken)) {
            throw new IllegalArgumentException("Reconnect token has invalid format.");
        }
        Properties properties = file.exists()
                ? AtomicProperties.load(file, "campaign reconnect credentials")
                : new Properties();
        String format = properties.getProperty("format");
        if(format != null && !FORMAT.equals(format)) {
            throw new IllegalStateException("Reconnect credential file has unsupported format: " + file);
        }
        properties.setProperty("format", FORMAT);
        properties.setProperty("campaign." + campaignId, reconnectToken);
        AtomicProperties.store(file, properties,
                "Delver Multiplayer private per-campaign reconnect credentials");
    }

    synchronized Map<String, String> snapshot() {
        if(!file.exists()) return Collections.emptyMap();
        Properties properties = loadValidated();
        Map<String, String> credentials = new LinkedHashMap<String, String>();
        for(String key : properties.stringPropertyNames()) {
            if(key.equals("format")) continue;
            if(!key.startsWith("campaign.")) {
                throw new IllegalStateException("Reconnect credential file is corrupt: " + file);
            }
            String campaignId = key.substring("campaign.".length());
            CampaignRoster.requireCampaignId(campaignId);
            String token = properties.getProperty(key);
            if(!PrivateToken.isValid(token)) {
                throw new IllegalStateException("Reconnect credential file is corrupt: " + file);
            }
            credentials.put(campaignId, token);
        }
        return Collections.unmodifiableMap(credentials);
    }

    synchronized void replaceAll(Map<String, String> credentials) {
        if(credentials == null) throw new IllegalArgumentException("Reconnect credentials cannot be null.");
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        for(Map.Entry<String, String> credential : credentials.entrySet()) {
            CampaignRoster.requireCampaignId(credential.getKey());
            if(!PrivateToken.isValid(credential.getValue())) {
                throw new IllegalArgumentException("Reconnect token has invalid format.");
            }
            properties.setProperty("campaign." + credential.getKey(), credential.getValue());
        }
        AtomicProperties.store(file, properties,
                "Delver Multiplayer private per-campaign reconnect credentials");
    }

    private Properties loadValidated() {
        Properties properties = AtomicProperties.load(file, "campaign reconnect credentials");
        if(!FORMAT.equals(properties.getProperty("format"))) {
            throw new IllegalStateException("Reconnect credential file has unsupported format: " + file);
        }
        return properties;
    }
}
