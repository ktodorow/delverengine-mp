package com.interrupt.dungeoneer.ui;

import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure Party HUD projection from authoritative status and movement snapshots. */
public final class PartyHudModel {
    private PartyHudModel() { }

    public static List<Row> build(PartyStatusSnapshot partyStatus,
            MovementSnapshot movement, NetworkEntityId localEntityId,
            float localX, float localY, float localRotation) {
        if(partyStatus == null || localEntityId == null) return Collections.emptyList();
        List<Row> rows = new ArrayList<Row>();
        for(PartyMemberStatus member : partyStatus.getMembers()) {
            boolean local = localEntityId.equals(member.getEntityId());
            Float distance = null;
            String direction = "--";
            if(local) {
                distance = 0f;
                direction = "HERE";
            }
            else if(member.getEntityId() != null && movement != null) {
                MovementEntityState remote = movement.getEntity(member.getEntityId());
                if(remote != null) {
                    float dx = remote.getX() - localX;
                    float dy = remote.getY() - localY;
                    distance = (float)Math.sqrt(dx * dx + dy * dy);
                    direction = relativeDirection(dx, dy, localRotation);
                }
            }
            rows.add(new Row(member, local, distance, direction));
        }
        return Collections.unmodifiableList(rows);
    }

    static String relativeDirection(float dx, float dy, float localRotation) {
        if(dx * dx + dy * dy < 0.0001f) return "HERE";
        double relative = Math.atan2(dx, dy) - localRotation;
        while(relative <= -Math.PI) relative += Math.PI * 2.0;
        while(relative > Math.PI) relative -= Math.PI * 2.0;
        double quarter = Math.PI * 0.25;
        if(relative >= -quarter && relative <= quarter) return "AHEAD";
        if(relative > quarter && relative < Math.PI - quarter) return "RIGHT";
        if(relative < -quarter && relative > -Math.PI + quarter) return "LEFT";
        return "BEHIND";
    }

    public static final class Row {
        private final PartyMemberStatus member;
        private final boolean local;
        private final Float distance;
        private final String direction;

        private Row(PartyMemberStatus member, boolean local, Float distance,
                String direction) {
            this.member = member;
            this.local = local;
            this.distance = distance;
            this.direction = direction;
        }

        public PartyMemberStatus getMember() {
            return member;
        }

        public boolean isLocal() {
            return local;
        }

        public Float getDistance() {
            return distance;
        }

        public String getDirection() {
            return direction;
        }

        public String getDisplayText() {
            String location = distance == null ? "--"
                    : String.format(Locale.ROOT, "%.1fm %s", distance, direction);
            String lives = member.getRemainingLives() == 1 ? "Life" : "Lives";
            return member.getNickname() + "  " + member.getHealth() + "/"
                    + member.getMaximumHealth() + " HP  " + member.getRemainingLives()
                    + " " + lives + "  " + location + "  "
                    + member.getState().getDisplayName();
        }

        public PartyMemberState getState() {
            return member.getState();
        }
    }
}
