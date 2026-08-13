package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;

import java.io.File;

final class DesktopLaunchOptions {
    boolean openSourceTestLevel;
    boolean ownedTutorial;
    boolean inspectOwnedCopy;
    boolean browseOwnedCopy;
    File ownedCopy;
    boolean directHost;
    String directConnectAddress;
    int sessionPort = com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol.DEFAULT_PORT;
    File profileRoot;
    int campaignCapacity = 2;
    String campaignId = "open-source-test";
    String nickname;
    String avatarId;
    int requestedSlot;
    private boolean campaignCapacitySpecified;
    private boolean campaignIdSpecified;

    static DesktopLaunchOptions parse(String[] args) {
        DesktopLaunchOptions options = new DesktopLaunchOptions();
        if(args == null) return options;

        for(int i = 0; i < args.length; i++) {
            String argument = args[i];
            if(argument == null) continue;

            if(argument.equalsIgnoreCase("--test-level") || argument.equalsIgnoreCase("test-level=true")) {
                options.openSourceTestLevel = true;
            }
            else if(argument.equalsIgnoreCase("--owned-tutorial")) {
                options.ownedTutorial = true;
            }
            else if(argument.equalsIgnoreCase("--browse-owned-copy")) {
                options.browseOwnedCopy = true;
                options.ownedTutorial = true;
            }
            else if(argument.regionMatches(true, 0, "--owned-copy=", 0, "--owned-copy=".length())) {
                options.ownedCopy = new File(argument.substring("--owned-copy=".length()));
                options.ownedTutorial = true;
            }
            else if(argument.equalsIgnoreCase("--owned-copy") && i + 1 < args.length) {
                options.ownedCopy = new File(args[++i]);
                options.ownedTutorial = true;
            }
            else if(argument.regionMatches(true, 0, "--inspect-owned-copy=", 0,
                    "--inspect-owned-copy=".length())) {
                options.ownedCopy = new File(argument.substring("--inspect-owned-copy=".length()));
                options.inspectOwnedCopy = true;
            }
            else if(argument.equalsIgnoreCase("--inspect-owned-copy")) {
                options.inspectOwnedCopy = true;
                if(i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.ownedCopy = new File(args[++i]);
                }
            }
            else if(argument.equalsIgnoreCase("--direct-host")) {
                options.directHost = true;
            }
            else if(argument.regionMatches(true, 0, "--direct-host=", 0,
                    "--direct-host=".length())) {
                options.directHost = true;
                options.sessionPort = parsePort(argument.substring("--direct-host=".length()));
            }
            else if(argument.regionMatches(true, 0, "--direct-connect=", 0,
                    "--direct-connect=".length())) {
                options.directConnectAddress = requireValue(
                        argument.substring("--direct-connect=".length()),
                        "Direct Connect address cannot be empty.");
            }
            else if(argument.equalsIgnoreCase("--direct-connect")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(
                            "--direct-connect requires a Host address.");
                }
                options.directConnectAddress = requireValue(args[++i],
                        "Direct Connect address cannot be empty.");
            }
            else if(argument.regionMatches(true, 0, "--session-port=", 0,
                    "--session-port=".length())) {
                options.sessionPort = parsePort(argument.substring("--session-port=".length()));
            }
            else if(argument.equalsIgnoreCase("--session-port")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--session-port requires a number.");
                }
                options.sessionPort = parsePort(args[++i]);
            }
            else if(argument.regionMatches(true, 0, "--profile-root=", 0,
                    "--profile-root=".length())) {
                options.profileRoot = new File(requireValue(
                        argument.substring("--profile-root=".length()),
                        "Profile root cannot be empty."));
            }
            else if(argument.equalsIgnoreCase("--profile-root")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--profile-root requires a directory.");
                }
                options.profileRoot = new File(requireValue(args[++i],
                        "Profile root cannot be empty."));
            }
            else if(argument.regionMatches(true, 0, "--campaign-capacity=", 0,
                    "--campaign-capacity=".length())) {
                options.campaignCapacity = parseCapacity(
                        argument.substring("--campaign-capacity=".length()));
                options.campaignCapacitySpecified = true;
            }
            else if(argument.equalsIgnoreCase("--campaign-capacity")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--campaign-capacity requires 2, 3, or 4.");
                }
                options.campaignCapacity = parseCapacity(args[++i]);
                options.campaignCapacitySpecified = true;
            }
            else if(argument.regionMatches(true, 0, "--campaign-id=", 0,
                    "--campaign-id=".length())) {
                options.campaignId = CampaignRoster.requireCampaignId(requireValue(
                        argument.substring("--campaign-id=".length()),
                        "Campaign identity cannot be empty."));
                options.campaignIdSpecified = true;
            }
            else if(argument.equalsIgnoreCase("--campaign-id")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--campaign-id requires an identity.");
                }
                options.campaignId = CampaignRoster.requireCampaignId(args[++i]);
                options.campaignIdSpecified = true;
            }
            else if(argument.regionMatches(true, 0, "--nickname=", 0,
                    "--nickname=".length())) {
                options.nickname = requireValue(argument.substring("--nickname=".length()),
                        "Nickname cannot be empty.");
            }
            else if(argument.equalsIgnoreCase("--nickname")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--nickname requires a value.");
                }
                options.nickname = requireValue(args[++i], "Nickname cannot be empty.");
            }
            else if(argument.regionMatches(true, 0, "--avatar=", 0,
                    "--avatar=".length())) {
                options.avatarId = requireValue(argument.substring("--avatar=".length()),
                        "Avatar identity cannot be empty.");
            }
            else if(argument.equalsIgnoreCase("--avatar")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--avatar requires an identity.");
                }
                options.avatarId = requireValue(args[++i], "Avatar identity cannot be empty.");
            }
            else if(argument.regionMatches(true, 0, "--campaign-slot=", 0,
                    "--campaign-slot=".length())) {
                options.requestedSlot = parseRequestedSlot(
                        argument.substring("--campaign-slot=".length()));
            }
            else if(argument.equalsIgnoreCase("--campaign-slot")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--campaign-slot requires 1, 2, 3, or 4.");
                }
                options.requestedSlot = parseRequestedSlot(args[++i]);
            }
            else if(argument.regionMatches(true, 0, "--participant-id", 0,
                    "--participant-id".length())) {
                throw new IllegalArgumentException(
                        "--participant-id is no longer accepted; Launcher Identity is private and generated automatically. Use --nickname for display.");
            }
        }

        if(options.directHost && options.directConnectAddress != null) {
            throw new IllegalArgumentException(
                    "Choose either --direct-host or --direct-connect, not both.");
        }
        if((options.directHost || options.directConnectAddress != null)
                && (options.openSourceTestLevel || options.ownedTutorial
                        || options.inspectOwnedCopy)) {
            throw new IllegalArgumentException(
                    "Direct Connect cannot be combined with another launch mode.");
        }
        if(options.directConnectAddress != null && options.campaignCapacitySpecified) {
            throw new IllegalArgumentException("Only Host chooses --campaign-capacity.");
        }
        if(options.directConnectAddress != null && options.campaignIdSpecified) {
            throw new IllegalArgumentException("Client receives Campaign identity from Host.");
        }
        if(options.directHost && options.requestedSlot != 0) {
            throw new IllegalArgumentException("Host always owns Campaign Slot 1.");
        }
        if(options.directHost || options.directConnectAddress != null) {
            if(options.nickname == null) options.nickname = options.directHost ? "Host" : "Participant";
            if(options.avatarId == null) options.avatarId = options.directHost
                    ? AvatarCatalog.HUMANOID_1 : AvatarCatalog.HUMANOID_2;
            new SlotPresentation(options.nickname, options.avatarId);
        }
        return options;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(requireValue(value, "Session port cannot be empty."));
            if(port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        }
        catch(NumberFormatException ex) {
            throw new IllegalArgumentException("Session port must be a number from 1 to 65535.");
        }
    }

    private static int parseCapacity(String value) {
        try {
            int capacity = Integer.parseInt(requireValue(value,
                    "Campaign Capacity cannot be empty."));
            if(capacity < 2 || capacity > 4) throw new NumberFormatException();
            return capacity;
        }
        catch(NumberFormatException ex) {
            throw new IllegalArgumentException("Campaign Capacity must be 2, 3, or 4.");
        }
    }

    private static int parseRequestedSlot(String value) {
        try {
            int slot = Integer.parseInt(requireValue(value,
                    "Campaign Slot cannot be empty."));
            if(slot < 1 || slot > 4) throw new NumberFormatException();
            return slot;
        }
        catch(NumberFormatException ex) {
            throw new IllegalArgumentException("Campaign Slot must be 1, 2, 3, or 4.");
        }
    }

    private static String requireValue(String value, String message) {
        if(value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
