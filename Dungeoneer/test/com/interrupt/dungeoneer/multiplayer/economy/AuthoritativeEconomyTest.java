package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AuthoritativeEconomyTest {
    private static final long SHOP = 1000004L;
    private final ParticipantId alpha = new ParticipantId("campaign-slot-1");
    private final ParticipantId beta = new ParticipantId("campaign-slot-2");
    private final ParticipantId gamma = new ParticipantId("campaign-slot-3");

    @Test
    public void acquiredGoldIsDividedWithoutMintingAndRemainderStartsWithAcquirer() {
        AuthoritativeEconomy economy = economy(0, alpha, beta, gamma);
        List<ParticipantId> active = Arrays.asList(alpha, beta, gamma);

        Map<ParticipantId, Integer> shares = economy.divideGold(beta, 10, active);

        assertEquals(Integer.valueOf(4), shares.get(beta));
        assertEquals(Integer.valueOf(3), shares.get(gamma));
        assertEquals(Integer.valueOf(3), shares.get(alpha));
        assertEquals(10, economy.get(alpha).gold + economy.get(beta).gold + economy.get(gamma).gold);

        economy.divideGold(gamma, 1, active);
        assertEquals(4, economy.get(gamma).gold);
        assertEquals(4, economy.get(beta).gold);
        assertEquals(3, economy.get(alpha).gold);
    }

    @Test
    public void inactiveParticipantsReceiveNoGoldAndInvalidPilesAreRejected() {
        AuthoritativeEconomy economy = economy(0, alpha, beta);

        economy.divideGold(alpha, 7, Collections.singletonList(alpha));

        assertEquals(7, economy.get(alpha).gold);
        assertEquals(0, economy.get(beta).gold);
        assertNull(economy.divideGold(alpha, 0, Arrays.asList(alpha, beta)));
        assertNull(economy.divideGold(new ParticipantId("unknown"), 5, Arrays.asList(alpha, beta)));
        assertEquals(7, economy.get(alpha).gold);
    }

    @Test
    public void goldOverflowRejectsWholePileInsteadOfPartiallyPaying() {
        AuthoritativeEconomy economy = new AuthoritativeEconomy();
        economy.registerParticipant(progress(alpha, ParticipantProgress.MAX_GOLD));
        economy.registerParticipant(progress(beta, 0));

        assertNull(economy.divideGold(beta, 3, Arrays.asList(alpha, beta)));
        assertEquals(0, economy.get(beta).gold);
    }

    @Test
    public void registrationNeverReplacesExistingCampaignSlotProgress() {
        AuthoritativeEconomy economy = economy(5, alpha);
        economy.divideGold(alpha, 10, Collections.singletonList(alpha));

        ParticipantProgress registered = economy.registerParticipant(progress(alpha, 1000));

        assertEquals(15, registered.gold);
        assertEquals(15, economy.get(alpha).gold);
    }

    @Test
    public void experienceAwardsFullNativeValueToEachRecipientAndOneLevelPerAward() {
        AuthoritativeEconomy economy = economy(0, alpha, beta, gamma);

        List<ParticipantId> leveled = economy.awardExperience(Arrays.asList(alpha, beta), 8);

        assertEquals(Arrays.asList(alpha, beta), leveled);
        assertEquals(8, economy.get(alpha).experience);
        assertEquals(8, economy.get(beta).experience);
        assertEquals(0, economy.get(gamma).experience);
        assertEquals(2, economy.get(alpha).level);
        assertEquals(1, economy.get(alpha).pendingStatChoices);

        economy.awardExperience(Collections.singletonList(alpha), 1000);
        assertEquals(3, economy.get(alpha).level);
        assertEquals(2, economy.get(alpha).pendingStatChoices);
    }

    @Test
    public void statChoiceRequiresPendingChoiceAndAppliesNativeMaximumHealth() {
        AuthoritativeEconomy economy = economy(0, alpha);
        assertFalse(economy.chooseStat(alpha, CharacterStat.HEALTH));

        economy.awardExperience(Collections.singletonList(alpha), 8);
        assertTrue(economy.chooseStat(alpha, CharacterStat.HEALTH));

        ParticipantProgress progress = economy.get(alpha);
        assertEquals(5, progress.endurance);
        assertEquals(0, progress.pendingStatChoices);
        assertEquals(12, progress.maximumHealth);
        assertEquals(ParticipantProgress.nativeMaximumHealth(5, 2), progress.maximumHealth);
        assertFalse(economy.chooseStat(alpha, CharacterStat.ATTACK));
        assertEquals(4, economy.get(alpha).attack);
    }

    @Test
    public void elixerGrantsStatChoiceWithoutLevel() {
        AuthoritativeEconomy economy = economy(0, alpha);

        assertTrue(economy.grantStatChoice(alpha));

        assertEquals(1, economy.get(alpha).level);
        assertEquals(1, economy.get(alpha).pendingStatChoices);
        assertTrue(economy.chooseStat(alpha, CharacterStat.MAGIC));
        assertEquals(5, economy.get(alpha).magic);
    }

    @Test
    public void firstAcceptedPurchaseWinsAndSpendsOnlyBuyerGold() {
        AuthoritativeEconomy economy = economy(100, alpha, beta);
        ShopEntryState entry = economy.offer(SHOP, "sword", ItemProperties.DEFAULT, 40, false);

        assertEquals(AuthoritativeEconomy.PurchaseOutcome.ACCEPTED, economy.purchase(beta, SHOP, entry.entryId));
        assertEquals(AuthoritativeEconomy.PurchaseOutcome.SOLD, economy.purchase(alpha, SHOP, entry.entryId));

        assertEquals(60, economy.get(beta).gold);
        assertEquals(100, economy.get(alpha).gold);
        assertTrue(economy.entry(SHOP, entry.entryId).sold);
        assertTrue(economy.entry(SHOP, entry.entryId).revision > entry.revision);
    }

    @Test
    public void insufficientGoldAndUnknownEntriesLeaveStockUntouched() {
        AuthoritativeEconomy economy = economy(10, alpha);
        ShopEntryState entry = economy.offer(SHOP, "armor", ItemProperties.DEFAULT, 40, false);

        assertEquals(AuthoritativeEconomy.PurchaseOutcome.INSUFFICIENT_GOLD,
                economy.purchase(alpha, SHOP, entry.entryId));
        assertEquals(AuthoritativeEconomy.PurchaseOutcome.UNKNOWN_ENTRY,
                economy.purchase(alpha, SHOP, entry.entryId + 1));

        assertFalse(economy.entry(SHOP, entry.entryId).sold);
        assertEquals(10, economy.get(alpha).gold);
    }

    @Test
    public void sharedStockIsGeneratedOnceAndBounded() {
        AuthoritativeEconomy economy = economy(0, alpha);
        assertFalse(economy.hasStock(SHOP));

        economy.openStock(SHOP);

        assertTrue(economy.hasStock(SHOP));
        assertTrue(economy.stock(SHOP).isEmpty());
        for(int i = 0; i < AuthoritativeEconomy.MAX_ENTRIES_PER_SHOP; i++) {
            assertNotNull(economy.offer(SHOP, "scroll", ItemProperties.DEFAULT, 1, false));
        }
        assertNull(economy.offer(SHOP, "scroll", ItemProperties.DEFAULT, 1, false));
    }

    @Test
    public void personalSoulboundOffersAreRestrictedAndReplacedOnReopen() {
        AuthoritativeEconomy economy = economy(200, alpha, beta);
        ShopEntryState first = economy.offerPersonal(SHOP, alpha, 0, "bag", ItemProperties.DEFAULT, 30, true);

        assertEquals(AuthoritativeEconomy.PurchaseOutcome.NOT_OFFERED,
                economy.purchase(beta, SHOP, first.entryId));

        ShopEntryState reopened = economy.offerPersonal(SHOP, alpha, 0, "bag", ItemProperties.DEFAULT, 52, true);
        assertEquals(first.entryId, reopened.entryId);
        assertTrue(reopened.revision > first.revision);
        assertEquals(1, economy.stock(SHOP).size());
        assertEquals(52, economy.entry(SHOP, first.entryId).cost);

        ShopEntryState betaOffer = economy.offerPersonal(SHOP, beta, 0, "bag", ItemProperties.DEFAULT, 30, true);
        assertNotEquals(first.entryId, betaOffer.entryId);

        economy.withdrawPersonalOffers(SHOP, alpha, 0);
        assertTrue(economy.entry(SHOP, first.entryId).sold);
        assertFalse(economy.entry(SHOP, betaOffer.entryId).sold);
    }

    @Test
    public void inventoryExpansionFollowsNativeBagAndBeltLimits() {
        AuthoritativeEconomy economy = new AuthoritativeEconomy();
        economy.registerParticipant(new ParticipantProgress(alpha, 0L, 0, 0, 1, 4, 4, 4, 4, 4, 4,
                0, 8, 45, 9));

        assertTrue(economy.expandInventory(alpha, true));
        assertEquals(10, economy.get(alpha).hotbarSize);
        assertEquals(46, economy.get(alpha).inventorySize);
        assertFalse(economy.canExpandInventory(alpha, true));
        assertFalse(economy.canExpandInventory(alpha, false));
        assertFalse(economy.expandInventory(alpha, false));
    }

    @Test
    public void newWorldGenerationClearsShopStockButKeepsCampaignSlotProgress() {
        AuthoritativeEconomy economy = economy(50, alpha);
        ShopEntryState entry = economy.offer(SHOP, "wand", ItemProperties.DEFAULT, 5, false);

        economy.beginWorld(2L);

        assertNull(economy.entry(SHOP, entry.entryId));
        assertFalse(economy.hasStock(SHOP));
        assertEquals(50, economy.get(alpha).gold);
        assertEquals(2L, economy.offer(SHOP, "wand", ItemProperties.DEFAULT, 5, false).generation);
    }

    private AuthoritativeEconomy economy(int gold, ParticipantId... participants) {
        AuthoritativeEconomy economy = new AuthoritativeEconomy();
        for(ParticipantId participant : participants) economy.registerParticipant(progress(participant, gold));
        return economy;
    }

    private static ParticipantProgress progress(ParticipantId participant, int gold) {
        return new ParticipantProgress(participant, 0L, gold, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 24, 6);
    }
}
