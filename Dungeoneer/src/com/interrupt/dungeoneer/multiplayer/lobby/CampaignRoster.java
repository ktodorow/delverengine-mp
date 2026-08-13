package com.interrupt.dungeoneer.multiplayer.lobby;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Host-owned Campaign Slot roster. Connections never become ownership records implicitly. */
public final class CampaignRoster {
    public enum ClaimStatus {
        ADMITTED,
        NEEDS_APPROVAL,
        CAMPAIGN_FULL,
        SLOT_OCCUPIED,
        RECONNECT_DENIED,
        NICKNAME_TAKEN,
        AVATAR_UNAVAILABLE
    }

    public static final class ClaimOutcome {
        private final ClaimStatus status;
        private final CampaignSlot slot;
        private final String reason;

        private ClaimOutcome(ClaimStatus status, CampaignSlot slot, String reason) {
            this.status = status;
            this.slot = slot;
            this.reason = reason;
        }

        public ClaimStatus getStatus() {
            return status;
        }

        public CampaignSlot getSlot() {
            return slot;
        }

        public String getReason() {
            return reason;
        }
    }

    private final String campaignId;
    private final int capacity;
    private final AvatarCatalog avatarCatalog;
    private final List<CampaignSlot> slots;

    private CampaignRoster(String campaignId, int capacity, AvatarCatalog avatarCatalog,
            List<CampaignSlot> slots) {
        this.campaignId = requireCampaignId(campaignId);
        if(capacity < 2 || capacity > 4) {
            throw new IllegalArgumentException("Campaign Capacity must be two, three, or four.");
        }
        if(avatarCatalog == null) throw new IllegalArgumentException("Avatar catalog cannot be null.");
        this.capacity = capacity;
        this.avatarCatalog = avatarCatalog;
        this.slots = new ArrayList<CampaignSlot>(slots);
        validateRestoredState();
    }

    public static CampaignRoster create(String campaignId, int capacity,
            AvatarCatalog avatarCatalog, LauncherIdentity hostIdentity,
            SlotPresentation hostPresentation, SecureRandom random) {
        if(random == null) throw new IllegalArgumentException("Secure random source cannot be null.");
        List<CampaignSlot> slots = new ArrayList<CampaignSlot>();
        slots.add(new CampaignSlot(1, hostIdentity, PrivateToken.randomHex(random), hostPresentation));
        return new CampaignRoster(campaignId, capacity, avatarCatalog, slots);
    }

    static CampaignRoster restore(String campaignId, int capacity, AvatarCatalog avatarCatalog,
            List<CampaignSlot> slots) {
        return new CampaignRoster(campaignId, capacity, avatarCatalog, slots);
    }

    public synchronized ClaimOutcome submit(SlotClaimRequest request) {
        CampaignSlot existing = findSlot(request.getLauncherIdentity());
        if(existing != null) {
            if(!tokensEqual(existing.getReconnectToken(), request.getReconnectToken())) {
                return outcome(ClaimStatus.RECONNECT_DENIED, null,
                        "Launcher Identity owns a Campaign Slot, but reconnect credential is invalid.");
            }
            if(request.getRequestedSlot() != 0
                    && request.getRequestedSlot() != existing.getNumber()) {
                return outcome(ClaimStatus.SLOT_OCCUPIED, null,
                        "Launcher Identity already owns Campaign Slot " + existing.getNumber() + ".");
            }
            ClaimOutcome presentation = validatePresentation(request.getPresentation(), existing);
            if(presentation != null) return presentation;
            CampaignSlot updated = existing.withPresentation(request.getPresentation());
            replace(existing, updated);
            return outcome(ClaimStatus.ADMITTED, updated,
                    "Returning Launcher Identity reclaimed Campaign Slot " + updated.getNumber() + ".");
        }

        if(slots.size() >= capacity) {
            return outcome(ClaimStatus.CAMPAIGN_FULL, null,
                    "Campaign Roster is full; unknown Launcher Identity cannot claim a slot.");
        }

        int availableSlot = resolveAvailableSlot(request.getRequestedSlot());
        if(availableSlot == 0) {
            return outcome(ClaimStatus.SLOT_OCCUPIED, null,
                    "Requested Campaign Slot is occupied and will not be reassigned.");
        }
        ClaimOutcome presentation = validatePresentation(request.getPresentation(), null);
        if(presentation != null) return presentation;
        return outcome(ClaimStatus.NEEDS_APPROVAL, null,
                "New Launcher Identity requires Host approval for Campaign Slot " + availableSlot + ".");
    }

    public synchronized ClaimOutcome approve(SlotClaimRequest request, SecureRandom random) {
        ClaimOutcome current = submit(request);
        if(current.getStatus() != ClaimStatus.NEEDS_APPROVAL) return current;
        int slotNumber = resolveAvailableSlot(request.getRequestedSlot());
        if(slotNumber == 0) {
            return outcome(ClaimStatus.SLOT_OCCUPIED, null,
                    "Requested Campaign Slot became occupied before Host approval.");
        }
        CampaignSlot approved = new CampaignSlot(slotNumber, request.getLauncherIdentity(),
                PrivateToken.randomHex(random), request.getPresentation());
        slots.add(approved);
        sortSlots();
        return outcome(ClaimStatus.ADMITTED, approved,
                "Host approved Campaign Slot " + approved.getNumber() + ".");
    }

    public synchronized void updateHostPresentation(LauncherIdentity hostIdentity,
            SlotPresentation presentation) {
        CampaignSlot host = findSlot(hostIdentity);
        if(host == null || host.getNumber() != 1) {
            throw new IllegalStateException(
                    "Campaign belongs to a different Host Launcher Identity; Host ownership cannot migrate.");
        }
        ClaimOutcome validation = validatePresentation(presentation, host);
        if(validation != null) throw new IllegalArgumentException(validation.getReason());
        replace(host, host.withPresentation(presentation));
    }

    public synchronized CampaignSlot findSlot(LauncherIdentity identity) {
        if(identity == null) return null;
        for(CampaignSlot slot : slots) {
            if(slot.getLauncherIdentity().equals(identity)) return slot;
        }
        return null;
    }

    public synchronized CampaignSlot getSlot(int number) {
        for(CampaignSlot slot : slots) if(slot.getNumber() == number) return slot;
        return null;
    }

    public synchronized List<CampaignSlot> getSlots() {
        return Collections.unmodifiableList(new ArrayList<CampaignSlot>(slots));
    }

    public String getCampaignId() {
        return campaignId;
    }

    public int getCapacity() {
        return capacity;
    }

    public AvatarCatalog getAvatarCatalog() {
        return avatarCatalog;
    }

    public static String requireCampaignId(String campaignId) {
        if(campaignId == null || !campaignId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "Campaign identity must be 1-64 letters, numbers, dots, underscores, or hyphens.");
        }
        return campaignId;
    }

    private ClaimOutcome validatePresentation(SlotPresentation presentation,
            CampaignSlot excludedSlot) {
        String nicknameKey = presentation.getNickname().toLowerCase(Locale.ROOT);
        Set<String> usedAvatars = new HashSet<String>();
        for(CampaignSlot slot : slots) {
            if(slot == excludedSlot) continue;
            if(slot.getPresentation().getNickname().toLowerCase(Locale.ROOT).equals(nicknameKey)) {
                return outcome(ClaimStatus.NICKNAME_TAKEN, null,
                        "Nickname is already used by another Campaign Slot.");
            }
            usedAvatars.add(slot.getPresentation().getAvatarId());
        }

        String avatarId = presentation.getAvatarId();
        if(!avatarCatalog.contains(avatarId)) {
            return outcome(ClaimStatus.AVATAR_UNAVAILABLE, null,
                    "Avatar is not one of the available owned humanoid choices.");
        }
        if(usedAvatars.contains(avatarId) && hasUnusedAvatar(usedAvatars)) {
            return outcome(ClaimStatus.AVATAR_UNAVAILABLE, null,
                    "Avatar is already used while another owned humanoid choice remains available.");
        }
        return null;
    }

    private boolean hasUnusedAvatar(Set<String> usedAvatars) {
        for(String avatarId : avatarCatalog.getAvatarIds()) {
            if(!usedAvatars.contains(avatarId)) return true;
        }
        return false;
    }

    private int resolveAvailableSlot(int requestedSlot) {
        if(requestedSlot != 0) {
            return requestedSlot <= capacity && getSlot(requestedSlot) == null ? requestedSlot : 0;
        }
        for(int slot = 1; slot <= capacity; slot++) if(getSlot(slot) == null) return slot;
        return 0;
    }

    private void replace(CampaignSlot existing, CampaignSlot updated) {
        int index = slots.indexOf(existing);
        if(index < 0) throw new IllegalStateException("Campaign Slot disappeared during update.");
        slots.set(index, updated);
    }

    private void validateRestoredState() {
        if(slots.size() > capacity) throw new IllegalArgumentException("Campaign Roster exceeds capacity.");
        Set<Integer> numbers = new HashSet<Integer>();
        Set<LauncherIdentity> identities = new HashSet<LauncherIdentity>();
        Set<String> tokens = new HashSet<String>();
        for(CampaignSlot slot : slots) {
            if(slot.getNumber() > capacity || !numbers.add(slot.getNumber())) {
                throw new IllegalArgumentException("Campaign Roster contains duplicate or out-of-range slot.");
            }
            if(!identities.add(slot.getLauncherIdentity())) {
                throw new IllegalArgumentException("Campaign Roster contains duplicate Launcher Identity.");
            }
            if(!tokens.add(slot.getReconnectToken())) {
                throw new IllegalArgumentException("Campaign Roster contains duplicate reconnect credential.");
            }
            ClaimOutcome presentation = validatePresentation(slot.getPresentation(), slot);
            if(presentation != null) throw new IllegalArgumentException(presentation.getReason());
        }
        sortSlots();
    }

    private void sortSlots() {
        Collections.sort(slots, new java.util.Comparator<CampaignSlot>() {
            @Override
            public int compare(CampaignSlot first, CampaignSlot second) {
                return first.getNumber() - second.getNumber();
            }
        });
    }

    private static boolean tokensEqual(String expected, String provided) {
        if(expected == null || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                provided.getBytes(StandardCharsets.US_ASCII));
    }

    private static ClaimOutcome outcome(ClaimStatus status, CampaignSlot slot, String reason) {
        return new ClaimOutcome(status, slot, reason);
    }
}
