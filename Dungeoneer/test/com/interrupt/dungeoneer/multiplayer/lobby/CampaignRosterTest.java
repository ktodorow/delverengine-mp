package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster.ClaimOutcome;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster.ClaimStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CampaignRosterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void hostChoosesCapacityAndUnknownIdentityNeedsApproval() {
        CampaignRoster roster = roster(3);
        SlotClaimRequest request = claim('2', "Friend", AvatarCatalog.HUMANOID_2, 0, null);

        ClaimOutcome pending = roster.submit(request);
        ClaimOutcome approved = roster.approve(request, new SecureRandom());

        assertEquals(3, roster.getCapacity());
        assertEquals(ClaimStatus.NEEDS_APPROVAL, pending.getStatus());
        assertEquals(ClaimStatus.ADMITTED, approved.getStatus());
        assertEquals(2, approved.getSlot().getNumber());
        assertNotNull(approved.getSlot().getReconnectToken());
    }

    @Test
    public void validReconnectKeepsSlotWhileNicknameAndAvatarChange() {
        CampaignRoster roster = roster(3);
        SlotClaimRequest initial = claim('2', "Friend", AvatarCatalog.HUMANOID_2, 0, null);
        CampaignSlot approved = roster.approve(initial, new SecureRandom()).getSlot();

        SlotClaimRequest returning = claim('2', "Renamed", AvatarCatalog.HUMANOID_3,
                approved.getNumber(), approved.getReconnectToken());
        ClaimOutcome reclaimed = roster.submit(returning);

        assertEquals(ClaimStatus.ADMITTED, reclaimed.getStatus());
        assertEquals(approved.getNumber(), reclaimed.getSlot().getNumber());
        assertEquals("Renamed", reclaimed.getSlot().getPresentation().getNickname());
        assertEquals(AvatarCatalog.HUMANOID_3,
                reclaimed.getSlot().getPresentation().getAvatarId());
        assertEquals(identity('2'), reclaimed.getSlot().getLauncherIdentity());
    }

    @Test
    public void nicknameIpOrRequestedOccupiedSlotCannotReplaceOwner() {
        CampaignRoster roster = roster(4);
        CampaignSlot owner = roster.approve(
                claim('2', "Owner", AvatarCatalog.HUMANOID_2, 2, null),
                new SecureRandom()).getSlot();

        ClaimOutcome sameNickname = roster.submit(
                claim('3', "owner", AvatarCatalog.HUMANOID_3, 0, null));
        ClaimOutcome occupied = roster.submit(
                claim('3', "Different", AvatarCatalog.HUMANOID_3, 2, null));
        ClaimOutcome wrongCredential = roster.submit(
                claim('2', "Owner", AvatarCatalog.HUMANOID_2, 2, token('f')));

        assertEquals(ClaimStatus.NICKNAME_TAKEN, sameNickname.getStatus());
        assertEquals(ClaimStatus.RELINK_REQUIRED, occupied.getStatus());
        assertEquals(ClaimStatus.RECONNECT_DENIED, wrongCredential.getStatus());
        assertEquals(owner.getLauncherIdentity(), roster.getSlot(2).getLauncherIdentity());
    }

    @Test
    public void trustedHostRelinkRotatesLostIdentityWithoutMovingItsCampaignSlot() {
        CampaignRoster roster = roster(3);
        CampaignSlot owner = roster.approve(
                claim('2', "Owner", AvatarCatalog.HUMANOID_2, 2, null),
                new SecureRandom()).getSlot();

        ClaimOutcome untrusted = roster.submit(
                claim('3', "Recovered", AvatarCatalog.HUMANOID_3, 2, null));
        ClaimOutcome relinked = roster.relink(
                claim('3', "Recovered", AvatarCatalog.HUMANOID_3, 2, null),
                new SecureRandom());
        ClaimOutcome oldCredential = roster.submit(
                claim('2', "Owner", AvatarCatalog.HUMANOID_2, 2,
                        owner.getReconnectToken()));
        ClaimOutcome hostRelink = roster.relink(
                claim('4', "Not Host", AvatarCatalog.HUMANOID_4, 1, null),
                new SecureRandom());

        assertEquals(ClaimStatus.RELINK_REQUIRED, untrusted.getStatus());
        assertEquals(owner.getNumber(), roster.getSlot(2).getNumber());
        assertEquals(ClaimStatus.ADMITTED, relinked.getStatus());
        assertEquals(identity('3'), relinked.getSlot().getLauncherIdentity());
        assertNotEquals(owner.getReconnectToken(), relinked.getSlot().getReconnectToken());
        assertEquals(ClaimStatus.SLOT_OCCUPIED, oldCredential.getStatus());
        assertEquals(ClaimStatus.RELINK_DENIED, hostRelink.getStatus());
        assertEquals(identity('1'), roster.getSlot(1).getLauncherIdentity());
    }

    @Test
    public void unavailableOrDuplicateAvatarIsRejectedWhileAlternativeExists() {
        CampaignRoster roster = roster(4);

        ClaimOutcome duplicate = roster.submit(
                claim('2', "Friend", AvatarCatalog.HUMANOID_1, 0, null));
        ClaimOutcome unavailable = roster.submit(
                claim('2', "Friend", "commercial-path.png", 0, null));

        assertEquals(ClaimStatus.AVATAR_UNAVAILABLE, duplicate.getStatus());
        assertEquals(ClaimStatus.AVATAR_UNAVAILABLE, unavailable.getStatus());
    }

    @Test
    public void fullRosterRejectsUnknownIdentityAndPreservesEveryOwner() {
        CampaignRoster roster = roster(2);
        roster.approve(claim('2', "Friend", AvatarCatalog.HUMANOID_2, 0, null),
                new SecureRandom());

        ClaimOutcome full = roster.submit(
                claim('3', "Late", AvatarCatalog.HUMANOID_3, 0, null));

        assertEquals(ClaimStatus.CAMPAIGN_FULL, full.getStatus());
        assertEquals(identity('1'), roster.getSlot(1).getLauncherIdentity());
        assertEquals(identity('2'), roster.getSlot(2).getLauncherIdentity());
        assertNull(roster.findSlot(identity('3')));
    }

    @Test
    public void hostStoreRoundTripPreservesCapacityIdentityPresentationAndToken() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(
                temporaryFolder.newFolder("campaigns"), new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 4,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        CampaignSlot friend = roster.approve(
                claim('2', "Friend", AvatarCatalog.HUMANOID_2, 3, null),
                new SecureRandom()).getSlot();
        store.save(roster);

        CampaignRoster loaded = store.load("friends", AvatarCatalog.ownedV108Humanoids());
        CampaignSlot loadedFriend = loaded.findSlot(identity('2'));

        assertEquals(4, loaded.getCapacity());
        assertEquals(3, loadedFriend.getNumber());
        assertEquals("Friend", loadedFriend.getPresentation().getNickname());
        assertEquals(friend.getReconnectToken(), loadedFriend.getReconnectToken());
    }

    private CampaignRoster roster(int capacity) {
        return CampaignRoster.create("test-campaign", capacity,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1),
                new SecureRandom());
    }

    private SlotClaimRequest claim(char identity, String nickname, String avatar,
            int requestedSlot, String reconnectToken) {
        return new SlotClaimRequest(identity(identity),
                new SlotPresentation(nickname, avatar), requestedSlot, reconnectToken);
    }

    private LauncherIdentity identity(char value) {
        return new LauncherIdentity(repeat(value));
    }

    private String token(char value) {
        return repeat(value);
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return result.toString();
    }
}
