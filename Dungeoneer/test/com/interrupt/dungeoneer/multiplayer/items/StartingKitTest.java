package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.ItemSpawner;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.game.LocalizedString;
import com.interrupt.managers.StringManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class StartingKitTest {
    private HashMap<String, LocalizedString> previousStrings;

    @Before public void strings() {
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
    }

    @After public void restore() {
        StringManager.localizedStrings = previousStrings;
    }

    @Test public void eachCharacterRollsItsOwnRandomItemsAndSharesTheFixedOnes() {
        RandomDraw wands = new RandomDraw("Wand of fire", "Wand of ice");

        DirectConnectItemController.StartingKit first =
                DirectConnectItemController.rollStartingKit(template(wands));
        DirectConnectItemController.StartingKit second =
                DirectConnectItemController.rollStartingKit(template(wands));

        assertEquals("Leather armor", first.worn.get(0).name);
        assertEquals("Leather armor", second.worn.get(0).name);
        assertEquals("Iron dagger", first.carried.get(0).name);
        assertEquals("Iron dagger", second.carried.get(0).name);
        assertNotEquals("Random entries roll again for every character",
                first.carried.get(1).name, second.carried.get(1).name);
    }

    /** Fresh template per character, as Host loads player.dat for each one. */
    private static Player template(RandomDraw random) {
        Player template = new Player();
        template.startingInventory.clear();
        Sword dagger = new Sword();
        dagger.name = "Iron dagger";
        Armor leather = new Armor();
        leather.name = "Leather armor";
        leather.equipLoc = "ARMOR";
        template.startingInventory.add(dagger);
        template.startingInventory.add(leather);
        template.startingInventory.add(random);
        return template;
    }

    /** Stands in for ItemManager's random potion/wand/food draws. */
    private static final class RandomDraw extends ItemSpawner {
        private final String[] names;
        private int draw;

        RandomDraw(String... names) { this.names = names; }

        @Override public Entity getItem() {
            Sword item = new Sword();
            item.name = names[draw++ % names.length];
            return item;
        }
    }
}
