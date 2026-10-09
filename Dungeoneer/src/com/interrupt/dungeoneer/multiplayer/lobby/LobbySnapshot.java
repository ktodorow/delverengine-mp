package com.interrupt.dungeoneer.multiplayer.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable public lobby facts. Ownership identities and reconnect credentials never enter this projection. */
public final class LobbySnapshot {
    public static final class Slot {
        private final int number;
        private final SlotPresentation presentation;
        private final boolean connected, authenticated, synchronizedState, playerReady;

        public Slot(int number, SlotPresentation presentation, boolean connected,
                boolean authenticated, boolean synchronizedState) {
            this(number, presentation, connected, authenticated, synchronizedState, false);
        }

        public Slot(int number, SlotPresentation presentation, boolean connected,
                boolean authenticated, boolean synchronizedState, boolean playerReady) {
            if(number < 1 || number > 4 || connected && presentation == null
                    || authenticated && !connected || synchronizedState && !authenticated
                    || playerReady && !synchronizedState)
                throw new IllegalArgumentException("Invalid lobby slot state.");
            if(presentation != null && !AvatarCatalog.ownedV108Humanoids().contains(presentation.getAvatarId()))
                throw new IllegalArgumentException("Unknown lobby Avatar.");
            this.number = number;
            this.presentation = presentation;
            this.connected = connected;
            this.authenticated = authenticated;
            this.synchronizedState = synchronizedState;
            this.playerReady = playerReady;
        }

        public int getNumber() { return number; }
        public SlotPresentation getPresentation() { return presentation; }
        public boolean isClaimed() { return presentation != null; }
        public boolean isConnected() { return connected; }
        public boolean isAuthenticated() { return authenticated; }
        public boolean isSynchronized() { return synchronizedState; }
        public boolean isPlayerReady() { return playerReady; }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Slot)) return false;
            Slot slot = (Slot)other;
            return number == slot.number && Objects.equals(presentation, slot.presentation)
                    && connected == slot.connected && authenticated == slot.authenticated
                    && synchronizedState == slot.synchronizedState && playerReady == slot.playerReady;
        }
        @Override public int hashCode() { return Objects.hash(number, presentation, connected, authenticated, synchronizedState, playerReady); }
    }

    private final long sequence;
    private final String campaignName;
    private final int capacity, startingLives, hostSlot;
    private final List<Slot> slots;

    public LobbySnapshot(long sequence, String campaignName, int capacity, int startingLives,
            int hostSlot, List<Slot> slots) {
        if(sequence < 1 || capacity < 2 || capacity > 4 || startingLives < 1 || startingLives > 5
                || hostSlot != 1 || slots == null || slots.size() != capacity)
            throw new IllegalArgumentException("Invalid lobby settings.");
        this.campaignName = CampaignRoster.requireCampaignName(campaignName);
        for(int index = 0; index < capacity; index++) {
            Slot slot = slots.get(index);
            if(slot == null || slot.getNumber() != index + 1)
                throw new IllegalArgumentException("Lobby slots must cover ordered capacity exactly.");
        }
        if(!slots.get(hostSlot - 1).isSynchronized())
            throw new IllegalArgumentException("Lobby Host must be connected and synchronized.");
        java.util.Set<String> nicknames = new java.util.HashSet<>();
        java.util.Set<String> avatars = new java.util.HashSet<>();
        for(Slot slot : slots) if(slot.isClaimed()) {
            if(!nicknames.add(slot.getPresentation().getNickname().toLowerCase(java.util.Locale.ROOT))
                    || !avatars.add(slot.getPresentation().getAvatarId()))
                throw new IllegalArgumentException("Lobby presentation must be unique.");
        }
        this.sequence = sequence;
        this.capacity = capacity;
        this.startingLives = startingLives;
        this.hostSlot = hostSlot;
        this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
    }

    public long getSequence() { return sequence; }
    public String getCampaignName() { return campaignName; }
    public int getCapacity() { return capacity; }
    public int getStartingLives() { return startingLives; }
    public int getHostSlot() { return hostSlot; }
    public List<Slot> getSlots() { return slots; }
    public Slot getSlot(int number) { return number < 1 || number > capacity ? null : slots.get(number - 1); }
    public int getClaimedCount() { int count = 0; for(Slot slot : slots) if(slot.isClaimed()) count++; return count; }
    public int getConnectedCount() { int count = 0; for(Slot slot : slots) if(slot.isConnected()) count++; return count; }
    public int getReservedCount() { return getClaimedCount() - getConnectedCount(); }
    public int getEmptyCount() { return capacity - getClaimedCount(); }
    @Override public boolean equals(Object other) {
        if(!(other instanceof LobbySnapshot)) return false;
        LobbySnapshot lobby = (LobbySnapshot)other;
        return sequence == lobby.sequence && campaignName.equals(lobby.campaignName)
                && capacity == lobby.capacity && startingLives == lobby.startingLives
                && hostSlot == lobby.hostSlot && slots.equals(lobby.slots);
    }
    @Override public int hashCode() { return Objects.hash(sequence, campaignName, capacity, startingLives, hostSlot, slots); }
}
