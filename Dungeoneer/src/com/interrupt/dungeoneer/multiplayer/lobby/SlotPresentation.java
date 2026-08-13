package com.interrupt.dungeoneer.multiplayer.lobby;

import java.nio.charset.StandardCharsets;

/** Lobby-editable presentation kept separate from Campaign Slot ownership. */
public final class SlotPresentation {
    public static final int MAX_NICKNAME_CODE_POINTS = 32;
    public static final int MAX_NICKNAME_BYTES = 64;
    public static final int MAX_AVATAR_ID_BYTES = 32;

    private final String nickname;
    private final String avatarId;

    public SlotPresentation(String nickname, String avatarId) {
        this.nickname = validateNickname(nickname);
        this.avatarId = validateAvatarId(avatarId);
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarId() {
        return avatarId;
    }

    private static String validateNickname(String nickname) {
        if(nickname == null) throw new IllegalArgumentException("Nickname cannot be null.");
        String trimmed = nickname.trim();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if(codePoints < 1 || codePoints > MAX_NICKNAME_CODE_POINTS
                || trimmed.getBytes(StandardCharsets.UTF_8).length > MAX_NICKNAME_BYTES) {
            throw new IllegalArgumentException("Nickname must be 1-32 characters and at most 64 UTF-8 bytes.");
        }
        for(int offset = 0; offset < trimmed.length();) {
            int codePoint = trimmed.codePointAt(offset);
            if(Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("Nickname cannot contain control characters.");
            }
            offset += Character.charCount(codePoint);
        }
        return trimmed;
    }

    private static String validateAvatarId(String avatarId) {
        if(avatarId == null || !avatarId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
                || avatarId.getBytes(StandardCharsets.UTF_8).length > MAX_AVATAR_ID_BYTES) {
            throw new IllegalArgumentException(
                    "Avatar identity must be 1-32 letters, numbers, dots, underscores, or hyphens.");
        }
        return avatarId;
    }

    @Override
    public boolean equals(Object other) {
        if(this == other) return true;
        if(!(other instanceof SlotPresentation)) return false;
        SlotPresentation that = (SlotPresentation)other;
        return nickname.equals(that.nickname) && avatarId.equals(that.avatarId);
    }

    @Override
    public int hashCode() {
        return 31 * nickname.hashCode() + avatarId.hashCode();
    }
}
