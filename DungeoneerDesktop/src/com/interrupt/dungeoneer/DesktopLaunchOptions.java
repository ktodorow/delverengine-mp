package com.interrupt.dungeoneer;

import java.io.File;

final class DesktopLaunchOptions {
    boolean openSourceTestLevel;
    boolean ownedTutorial;
    boolean inspectOwnedCopy;
    boolean browseOwnedCopy;
    File ownedCopy;

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
        }
        return options;
    }
}
