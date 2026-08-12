package com.interrupt.dungeoneer;

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
    String participantId = "client";

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
            else if(argument.regionMatches(true, 0, "--participant-id=", 0,
                    "--participant-id=".length())) {
                options.participantId = requireValue(
                        argument.substring("--participant-id=".length()),
                        "Participant identity cannot be empty.");
            }
            else if(argument.equalsIgnoreCase("--participant-id")) {
                if(i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(
                            "--participant-id requires an identity.");
                }
                options.participantId = requireValue(args[++i],
                        "Participant identity cannot be empty.");
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
        if(options.directConnectAddress != null) {
            new com.interrupt.dungeoneer.multiplayer.participant.ParticipantId(
                    options.participantId);
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

    private static String requireValue(String value, String message) {
        if(value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
