package com.interrupt.dungeoneer.multiplayer.lives;

import com.interrupt.dungeoneer.multiplayer.combat.DeathCause;
import com.interrupt.dungeoneer.multiplayer.items.ItemKind;
import com.interrupt.dungeoneer.multiplayer.lives.DeathDropRules.Fate;
import com.interrupt.dungeoneer.multiplayer.lives.DeathDropRules.Verdict;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeathDropRulesTest {
    private static final List<ItemKind> EVERY_KIND = Arrays.asList(ItemKind.WEAPON, ItemKind.ARMOR,
            ItemKind.POTION, ItemKind.SCROLL, ItemKind.FOOD, ItemKind.OTHER);

    private static List<Integer> conditions(int value, int count) {
        List<Integer> result = new ArrayList<Integer>();
        for(int i = 0; i < count; i++) result.add(value);
        return result;
    }

    private static int[] tally(DeathCause cause, ItemKind kind, int trials) {
        int destroyed = 0, conditionLoss = 0;
        Random random = new Random(7L);
        for(int trial = 0; trial < trials; trial++) {
            Verdict verdict = DeathDropRules.judge(cause, Arrays.asList(kind), Arrays.asList(4), random).get(0);
            if(verdict.fate == Fate.DESTROYED) destroyed++;
            else conditionLoss += 4 - verdict.condition;
        }
        return new int[] { destroyed, conditionLoss };
    }

    @Test
    public void combatPoisonAndDrowningKeepEveryItemIntact() {
        for(DeathCause cause : new DeathCause[] { DeathCause.COMBAT, DeathCause.DROWNING, null }) {
            for(Verdict verdict : DeathDropRules.judge(cause, EVERY_KIND, conditions(3, 6), new Random(1L))) {
                assertEquals(Fate.DROPPED, verdict.fate);
                assertEquals(3, verdict.condition);
            }
        }
        assertEquals(DeathDropRules.SCATTER_RADIUS, DeathDropRules.scatterRadius(DeathCause.COMBAT), 0f);
        assertEquals(DeathDropRules.DROWNING_SCATTER_RADIUS, DeathDropRules.scatterRadius(DeathCause.DROWNING), 0f);
    }

    @Test
    public void lavaDestroysEverything() {
        for(Verdict verdict : DeathDropRules.judge(DeathCause.LAVA, EVERY_KIND, conditions(4, 6), new Random(3L))) {
            assertEquals(Fate.DESTROYED, verdict.fate);
        }
    }

    @Test
    public void explosionDestroysAboutAThirdAndScuffsSurvivorsOneToThreeSteps() {
        int[] outcome = tally(DeathCause.EXPLOSION, ItemKind.OTHER, 20000);
        double destroyedRate = outcome[0] / 20000.0;
        // 35 percent per item plus 3 percent full loss lands near 37 percent overall.
        assertTrue("destroyed rate " + destroyedRate, destroyedRate > 0.33 && destroyedRate < 0.41);
        double averageLoss = outcome[1] / (double)(20000 - outcome[0]);
        assertTrue("average condition loss " + averageLoss, averageLoss > 1.8 && averageLoss < 2.2);
        Random random = new Random(11L);
        for(int trial = 0; trial < 5000; trial++) {
            Verdict verdict = DeathDropRules.judge(DeathCause.EXPLOSION, Arrays.asList(ItemKind.WEAPON),
                    Arrays.asList(4), random).get(0);
            if(verdict.fate == Fate.DROPPED) assertTrue(verdict.condition >= 1 && verdict.condition <= 3);
        }
    }

    @Test
    public void explosionOccasionallyTakesTheWholeScatterAtOnce() {
        Random random = new Random(5L);
        int wholeLosses = 0;
        for(int trial = 0; trial < 20000; trial++) {
            List<Verdict> verdicts = DeathDropRules.judge(DeathCause.EXPLOSION, EVERY_KIND, conditions(4, 6), random);
            boolean all = true;
            for(Verdict verdict : verdicts) all &= verdict.fate == Fate.DESTROYED;
            if(all) wholeLosses++;
        }
        // 3 percent outright plus 0.35^6 independent losses stays a rare event.
        assertTrue("whole losses " + wholeLosses, wholeLosses > 400 && wholeLosses < 900);
    }

    @Test
    public void burningTakesScrollsFoodAndHalfOfPotionsOnly() {
        assertEquals(20000, tally(DeathCause.BURNING, ItemKind.SCROLL, 20000)[0]);
        assertEquals(20000, tally(DeathCause.BURNING, ItemKind.FOOD, 20000)[0]);
        int potions = tally(DeathCause.BURNING, ItemKind.POTION, 20000)[0];
        assertTrue("potions " + potions, potions > 9500 && potions < 10500);
        assertEquals(0, tally(DeathCause.BURNING, ItemKind.WEAPON, 2000)[0]);
        assertEquals(0, tally(DeathCause.BURNING, ItemKind.WEAPON, 2000)[1]);
    }

    @Test
    public void fallShattersHalfOfPotionsAndDentsGearOnce() {
        int potions = tally(DeathCause.FALL, ItemKind.POTION, 20000)[0];
        assertTrue("potions " + potions, potions > 9500 && potions < 10500);
        assertEquals(2000, tally(DeathCause.FALL, ItemKind.WEAPON, 2000)[1]);
        assertEquals(2000, tally(DeathCause.FALL, ItemKind.ARMOR, 2000)[1]);
        assertEquals(0, tally(DeathCause.FALL, ItemKind.SCROLL, 2000)[1]);
    }

    @Test
    public void trapsDentGearTwiceAndFrostShattersSomePotions() {
        assertEquals(4000, tally(DeathCause.TRAP, ItemKind.WEAPON, 2000)[1]);
        assertEquals(4000, tally(DeathCause.TRAP, ItemKind.ARMOR, 2000)[1]);
        assertEquals(0, tally(DeathCause.TRAP, ItemKind.POTION, 2000)[0]);
        int potions = tally(DeathCause.FROST, ItemKind.POTION, 20000)[0];
        assertTrue("potions " + potions, potions > 5500 && potions < 6500);
        assertEquals(0, tally(DeathCause.FROST, ItemKind.WEAPON, 2000)[0]);
    }

    @Test
    public void conditionNeverDropsBelowBroken() {
        Verdict verdict = DeathDropRules.judge(DeathCause.TRAP, Arrays.asList(ItemKind.ARMOR),
                Arrays.asList(1), new Random(1L)).get(0);
        assertEquals(0, verdict.condition);
    }

    @Test
    public void goldPenaltyStartsAtEightPercentHardensPerDeathAndSoftensPerDestroyedItem() {
        assertEquals(8, DeathDropRules.goldLoss(100, 0, 0));
        assertEquals(12, DeathDropRules.goldLoss(100, 1, 0));
        assertEquals(25, DeathDropRules.goldLoss(100, 10, 0));
        assertEquals(4, DeathDropRules.goldLoss(100, 0, 2));
        assertEquals(0, DeathDropRules.goldLoss(100, 0, 4));
        assertEquals(0, DeathDropRules.goldLoss(100, 0, 9));
        assertEquals(10, DeathDropRules.goldLoss(100, 2, 3));
        assertEquals(0, DeathDropRules.goldLoss(0, 3, 0));
        assertEquals(0, DeathDropRules.goldLoss(7, 0, 0));
        assertEquals(250000000, DeathDropRules.goldLoss(1000000000, 9, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void kindsAndConditionsMustPairUp() {
        DeathDropRules.judge(DeathCause.COMBAT, EVERY_KIND, conditions(2, 5), new Random(1L));
    }

    @Test
    public void exhaustedScatterLastsSixUnpausedMinutes() {
        assertEquals(21600L, DeathDropRules.EXHAUSTED_DROP_TICKS);
    }
}
