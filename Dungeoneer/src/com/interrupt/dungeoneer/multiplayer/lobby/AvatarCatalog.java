package com.interrupt.dungeoneer.multiplayer.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Allowlist of symbolic Avatar identities backed by locally owned humanoid sprite sets. */
public final class AvatarCatalog {
    public static final String HUMANOID_1 = "humanoid-1";
    public static final String HUMANOID_2 = "humanoid-2";
    public static final String HUMANOID_3 = "humanoid-3";
    public static final String HUMANOID_4 = "humanoid-4";

    private final List<String> avatarIds;
    private final Set<String> lookup;

    public AvatarCatalog(List<String> avatarIds) {
        if(avatarIds == null || avatarIds.isEmpty() || avatarIds.size() > 4) {
            throw new IllegalArgumentException("Avatar catalog must contain one to four choices.");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for(String avatarId : avatarIds) {
            String validated = new SlotPresentation("validation", avatarId).getAvatarId();
            if(!unique.add(validated)) {
                throw new IllegalArgumentException("Avatar catalog contains duplicate identity: " + avatarId);
            }
        }
        this.avatarIds = Collections.unmodifiableList(new ArrayList<String>(unique));
        this.lookup = Collections.unmodifiableSet(unique);
    }

    /** Four usable humanoid choices certified by compatible v1.08 Owned Game Copy content. */
    public static AvatarCatalog ownedV108Humanoids() {
        return new AvatarCatalog(Arrays.asList(
                HUMANOID_1, HUMANOID_2, HUMANOID_3, HUMANOID_4));
    }

    public boolean contains(String avatarId) {
        return lookup.contains(avatarId);
    }

    public List<String> getAvatarIds() {
        return avatarIds;
    }
}
