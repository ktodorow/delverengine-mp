package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Explicit portable backup of one private Launcher Identity and its reconnect credentials. */
public final class IdentityRecoveryFile {
    private static final String FORMAT = "1";
    private static final String IDENTITY_KEY = "launcherIdentity";
    private static final String CAMPAIGN_PREFIX = "campaign.";

    private IdentityRecoveryFile() { }

    public static void exportCurrentProfile(File outputFile) {
        requireProfile();
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        ProfileReconnectTokenStore tokens = new ProfileReconnectTokenStore();
        exportTo(outputFile, identity, tokens.snapshot());
    }

    public static LauncherIdentity importIntoCurrentProfile(File inputFile) {
        requireProfile();
        Recovery recovery = read(inputFile);
        ProfileReconnectTokenStore tokens = new ProfileReconnectTokenStore();
        tokens.replaceAll(recovery.getReconnectTokens());
        LauncherIdentityStore.replaceCurrentProfile(recovery.getLauncherIdentity());
        return recovery.getLauncherIdentity();
    }

    static void exportTo(File outputFile, LauncherIdentity identity,
            Map<String, String> reconnectTokens) {
        if(outputFile == null) throw new IllegalArgumentException("Recovery output file cannot be null.");
        if(identity == null) throw new IllegalArgumentException("Launcher Identity cannot be null.");
        if(reconnectTokens == null) {
            throw new IllegalArgumentException("Reconnect credentials cannot be null.");
        }
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty(IDENTITY_KEY, identity.getValue());
        for(Map.Entry<String, String> credential : reconnectTokens.entrySet()) {
            CampaignRoster.requireCampaignId(credential.getKey());
            if(!PrivateToken.isValid(credential.getValue())) {
                throw new IllegalArgumentException("Reconnect token has invalid format.");
            }
            properties.setProperty(CAMPAIGN_PREFIX + credential.getKey(), credential.getValue());
        }
        AtomicProperties.store(outputFile, properties,
                "Delver Multiplayer Identity Recovery File - keep private");
    }

    static Recovery read(File inputFile) {
        if(inputFile == null || !inputFile.isFile()) {
            throw new IllegalStateException("Identity Recovery File does not exist: " + inputFile);
        }
        Properties properties = AtomicProperties.load(inputFile, "Identity Recovery File");
        if(!FORMAT.equals(properties.getProperty("format"))) {
            throw new IllegalStateException("Identity Recovery File has unsupported format: " + inputFile);
        }
        LauncherIdentity identity;
        try {
            identity = new LauncherIdentity(properties.getProperty(IDENTITY_KEY));
        }
        catch(IllegalArgumentException ex) {
            throw new IllegalStateException("Identity Recovery File has invalid Launcher Identity: "
                    + inputFile, ex);
        }
        Map<String, String> credentials = new LinkedHashMap<String, String>();
        for(String key : properties.stringPropertyNames()) {
            if(key.equals("format") || key.equals(IDENTITY_KEY)) continue;
            if(!key.startsWith(CAMPAIGN_PREFIX)) {
                throw new IllegalStateException("Identity Recovery File has unknown field: " + key);
            }
            String campaignId = key.substring(CAMPAIGN_PREFIX.length());
            String token = properties.getProperty(key);
            try {
                CampaignRoster.requireCampaignId(campaignId);
            }
            catch(IllegalArgumentException ex) {
                throw new IllegalStateException("Identity Recovery File has invalid Campaign identity: "
                        + inputFile, ex);
            }
            if(!PrivateToken.isValid(token)) {
                throw new IllegalStateException("Identity Recovery File has invalid reconnect credential: "
                        + inputFile);
            }
            credentials.put(campaignId, token);
        }
        return new Recovery(identity, credentials);
    }

    private static void requireProfile() {
        if(!MultiplayerProfile.isInitialized()) {
            throw new IllegalStateException("Multiplayer profile is not initialized.");
        }
    }

    static final class Recovery {
        private final LauncherIdentity launcherIdentity;
        private final Map<String, String> reconnectTokens;

        private Recovery(LauncherIdentity launcherIdentity, Map<String, String> reconnectTokens) {
            this.launcherIdentity = launcherIdentity;
            this.reconnectTokens = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(reconnectTokens));
        }

        LauncherIdentity getLauncherIdentity() {
            return launcherIdentity;
        }

        Map<String, String> getReconnectTokens() {
            return reconnectTokens;
        }
    }
}
