package com.interrupt.dungeoneer.owned;

public class OwnedGameCopyValidationException extends Exception {
    public OwnedGameCopyValidationException(String message) {
        super(message);
    }

    public OwnedGameCopyValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
