package com.interrupt.dungeoneer;

import java.io.PrintStream;

final class DesktopCrashHandler implements Thread.UncaughtExceptionHandler {
    interface Exit {
        void exit(int status);
    }

    private final PrintStream error;
    private final Exit exit;

    DesktopCrashHandler(PrintStream error, Exit exit) {
        this.error = error;
        this.exit = exit;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        error.println("Unhandled exception in " + thread.getName() + ":");
        failure.printStackTrace(error);
        error.flush();
        exit.exit(1);
    }
}
