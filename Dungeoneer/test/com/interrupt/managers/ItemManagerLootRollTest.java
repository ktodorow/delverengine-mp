package com.interrupt.managers;

import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.Food;
import com.interrupt.dungeoneer.entities.items.Gold;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.entities.items.Scroll;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.rpg.Stats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.util.HashMap;

import static org.junit.Assert.*;

public class ItemManagerLootRollTest {
    private Game previousGame;
    private HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings;
    private MarkerItemManager manager;

    private static final class MarkerItemManager extends ItemManager {
        final Sword melee = new Sword();
        final Armor armor = new Armor();
        final Food food = new Food();
        int selections;
        int selectedLevel;
        ItemManager.LootRoll selected;

        MarkerItemManager() {
            setLootRollSelector(() -> { selections++; return selected; });
        }

        @Override public Sword GetRandomWeapon(Integer level) {
            selectedLevel = level;
            return melee;
        }
        @Override public Item GetRandomRangedWeapon(Integer level) { return null; }
        @Override public Armor GetRandomArmor(Integer level) {
            selectedLevel = level;
            return armor;
        }
        @Override public Wand GetRandomWand() { return null; }
        @Override public Scroll GetRandomScroll() { return null; }
        @Override public Food GetRandomFood() { return food; }
        @Override public Potion GetRandomPotion() { return null; }
        @Override public Item GetRandomJunk() { return null; }
        @Override public Item GetUniqueItem(Integer level, com.interrupt.dungeoneer.game.Progression progression) {
            return null;
        }
    }

    @Before
    public void setup() {
        previousGame = Game.instance;
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<>();
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.player.stats = new Stats(100, 0, 0, 0, 0, 0);
        game.player.level = 9;
        manager = new MarkerItemManager();
        game.itemManager = manager;
        Game.instance = game;
    }

    @After
    public void restore() {
        Game.instance = previousGame;
        StringManager.localizedStrings = previousStrings;
    }

    @Test
    public void statBiasedRollUsesSelectedParticipantInsteadOfHostPlayer() {
        manager.selected = new ItemManager.LootRoll(new Stats(0, 100, 0, 0, 0, 0), 2);

        for(int i = 0; i < 25; i++) assertSame(manager.armor, manager.GetLevelLoot(1));

        assertEquals(25, manager.selections);
    }

    @Test
    public void originalFormulaStillSplitsAcrossSelectedStats() {
        manager.selected = new ItemManager.LootRoll(new Stats(50, 0, 0, 0, 0, 50), 2);
        boolean sawMelee = false, sawFood = false;

        for(int i = 0; i < 200; i++) {
            Item loot = manager.GetLevelLoot(1);
            assertTrue(loot == manager.melee || loot == manager.food);
            sawMelee |= loot == manager.melee;
            sawFood |= loot == manager.food;
        }

        assertTrue(sawMelee && sawFood);
    }

    @Test
    public void noSelectedParticipantFallsBackToLocalPlayerFormula() {
        manager.selected = null;

        ItemManager.LootRoll roll = manager.selectLootRoll();

        assertSame(Game.instance.player.stats, roll.stats);
        assertEquals(9, roll.level);
        assertSame(manager.melee, manager.GetLevelLoot(1));
    }

    @Test
    public void goldLootDoesNotSelectParticipantAndOneRollSelectsAtMostOnce() {
        manager.selected = new ItemManager.LootRoll(new Stats(0, 100, 0, 0, 0, 0), 2);
        int gold = 0, rolled = 0;

        for(int i = 0; i < 400; i++) {
            int before = manager.selections;
            Item loot = manager.GetMonsterLoot(1, true);
            int used = manager.selections - before;
            if(loot instanceof Gold) {
                gold++;
                assertEquals(0, used);
            }
            else {
                rolled++;
                assertEquals(1, used);
                assertSame(manager.armor, loot);
            }
        }

        assertTrue(gold > 0 && rolled > 0);
    }

    @Test
    public void breakableRollDefersSelectionAndUsesSelectedParticipantLevelAndStats() {
        manager.selected = new ItemManager.LootRoll(new Stats(100, 0, 0, 0, 0, 0), 3);

        assertSame(manager.melee, manager.GetMonsterLootForParticipant(false));
        assertEquals(1, manager.selections);
        assertEquals(3, manager.selectedLevel);
    }

    @Test
    public void breakableGoldKeepsVanillaPreselectionPath() {
        manager.selected = new ItemManager.LootRoll(new Stats(0, 100, 0, 0, 0, 0), 2);
        int gold = 0, rolled = 0;

        for(int i = 0; i < 400; i++) {
            int before = manager.selections;
            Item loot = manager.GetMonsterLootForParticipant(true);
            if(loot instanceof Gold) {
                gold++;
                assertEquals(0, manager.selections - before);
            }
            else {
                rolled++;
                assertEquals(1, manager.selections - before);
                assertSame(manager.armor, loot);
                assertEquals(2, manager.selectedLevel);
            }
        }

        assertTrue(gold > 0 && rolled > 0);
    }
}
