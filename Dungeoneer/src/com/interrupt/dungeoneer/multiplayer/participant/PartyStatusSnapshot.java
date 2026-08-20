package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable reliable Party status revision, sorted by persistent Campaign Slot. */
public final class PartyStatusSnapshot {
    private final long sequence;
    private final List<PartyMemberStatus> members;

    public PartyStatusSnapshot(long sequence, List<PartyMemberStatus> members) {
        if(sequence <= 0L) throw new IllegalArgumentException("Party status sequence must be positive.");
        if(members == null || members.isEmpty() || members.size() > 4) {
            throw new IllegalArgumentException("Party status must contain 1-4 Campaign Slots.");
        }
        List<PartyMemberStatus> copy = new ArrayList<PartyMemberStatus>(members);
        Set<Integer> slots = new HashSet<Integer>();
        Set<NetworkEntityId> entities = new HashSet<NetworkEntityId>();
        Set<String> nicknames = new HashSet<String>();
        for(PartyMemberStatus member : copy) {
            if(member == null) throw new IllegalArgumentException("Party member status cannot be null.");
            if(!slots.add(member.getCampaignSlot())) {
                throw new IllegalArgumentException("Party status contains duplicate Campaign Slot.");
            }
            if(member.getEntityId() != null && !entities.add(member.getEntityId())) {
                throw new IllegalArgumentException("Party status contains duplicate Network Entity ID.");
            }
            if(!nicknames.add(member.getNickname().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Party status contains duplicate Nickname.");
            }
        }
        Collections.sort(copy, new Comparator<PartyMemberStatus>() {
            @Override
            public int compare(PartyMemberStatus first, PartyMemberStatus second) {
                return first.getCampaignSlot() - second.getCampaignSlot();
            }
        });
        this.sequence = sequence;
        this.members = Collections.unmodifiableList(copy);
    }

    public long getSequence() {
        return sequence;
    }

    public List<PartyMemberStatus> getMembers() {
        return members;
    }

    public PartyMemberStatus getMember(int campaignSlot) {
        for(PartyMemberStatus member : members) {
            if(member.getCampaignSlot() == campaignSlot) return member;
        }
        return null;
    }

    public PartyMemberStatus getMember(NetworkEntityId entityId) {
        if(entityId == null) return null;
        for(PartyMemberStatus member : members) {
            if(entityId.equals(member.getEntityId())) return member;
        }
        return null;
    }
}
