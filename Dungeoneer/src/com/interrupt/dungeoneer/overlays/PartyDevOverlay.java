package com.interrupt.dungeoneer.overlays;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Spikes;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.FusedBomb;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.statuseffects.BurningEffect;
import com.interrupt.managers.ItemManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-only dev tools for a Direct Connect session (K key). Everything runs through Host
 * authority, so clients converge: damage of every cause on any Party member, lit bombs,
 * catalogue Monsters and items placed on the shared floor, ledger gold. A client only sees a note.
 */
public final class PartyDevOverlay extends WindowOverlay {
    private static final int LETHAL = 999;

    private final Player player;
    private final DirectConnectPeer peer;
    private final DirectConnectHost host;
    private final DirectConnectCombatController combat;
    private final DirectConnectMovementController movement;
    private final com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController items;
    private static int targetSlot = 1;

    public PartyDevOverlay(Player player) {
        this.player = player;
        GameApplication application = GameApplication.instance;
        peer = application == null ? null : application.getDirectConnectPeer();
        host = peer instanceof DirectConnectHost ? (DirectConnectHost)peer : null;
        combat = application == null ? null : application.getDirectConnectCombatController();
        movement = application == null ? null : application.getDirectConnectMovementController();
        items = application == null ? null : application.getDirectConnectItemController();
        pausesGame = false;
        dimScreen = true;
        animateBackground = false;
    }

    @Override
    public Table makeContent() {
        return host == null ? clientNote() : mainMenu();
    }

    private Table clientNote() {
        Table table = new Table();
        table.add(new Label("PARTY DEV", skin.get(LabelStyle.class))).padBottom(4f).row();
        table.add(new Label("Dev tools run on the Host only.", skin.get(LabelStyle.class))).row();
        table.add(new Label("Press K on the Host instance.", skin.get(LabelStyle.class))).row();
        addAction(table, "CLOSE", () -> OverlayManager.instance.remove(PartyDevOverlay.this));
        return table;
    }

    private Table mainMenu() {
        buttonOrder.clear();
        Table table = new Table();
        table.add(new Label("PARTY DEV (HOST)", skin.get(LabelStyle.class))).padBottom(4f).row();
        addAction(table, "TARGET: " + targetName(), () -> {
            cycleTarget();
            makeLayout(mainMenu());
        });
        addAction(table, "DAMAGE 3", () -> hurt(3, DamageType.PHYSICAL, null));
        addAction(table, "DOWN (COMBAT)", () -> hurt(LETHAL, DamageType.PHYSICAL, null));
        addAction(table, "DOWN (LAVA)", () -> hurt(LETHAL, DamageType.FIRE, null));
        addAction(table, "DOWN (BURNING)", () -> {
            com.interrupt.dungeoneer.entities.Actor actor = targetActor();
            if(actor != null) {
                boolean authority = actor.hasStatusEffectAuthority();
                actor.setStatusEffectAuthority(true);
                actor.addStatusEffect(new BurningEffect());
                actor.setStatusEffectAuthority(authority);
            }
            hurt(LETHAL, DamageType.PHYSICAL, null);
        });
        addAction(table, "DOWN (SPIKES)", () -> hurt(LETHAL, DamageType.PHYSICAL, new Spikes()));
        addAction(table, "DOWN (FROST)", () -> hurt(LETHAL, DamageType.ICE, null));
        addAction(table, "BOMB AT TARGET", () -> bomb(3f));
        addAction(table, "BIG BOMB AT TARGET", () -> bomb(LETHAL));
        addAction(table, "RESPAWN TARGET NOW", () -> {
            ParticipantId participant = targetParticipant();
            if(participant != null) host.devRespawn(participant, false);
        });
        addAction(table, "+1 LIFE (AND STAND UP)", () -> {
            ParticipantId participant = targetParticipant();
            if(participant != null) host.devRespawn(participant, true);
        });
        addAction(table, "GOLD +100", () -> {
            ParticipantId participant = targetParticipant();
            if(participant != null) host.devGrantGold(participant, 100);
        });
        addAction(table, "SPAWN MONSTER...", () -> makeLayout(monsterThemes()));
        addAction(table, "SPAWN ITEM...", () -> makeLayout(itemCategories()));
        addAction(table, (player.godMode ? "GODMODE: ON" : "GODMODE: OFF"), () -> {
            player.godMode = !player.godMode;
            makeLayout(mainMenu());
        });
        addAction(table, "CLOSE", () -> OverlayManager.instance.remove(PartyDevOverlay.this));
        return table;
    }

    private Table monsterThemes() {
        buttonOrder.clear();
        Table table = new Table();
        table.add(new Label("MONSTER THEME", skin.get(LabelStyle.class))).padBottom(4f).row();
        if(Game.instance != null && Game.instance.monsterManager != null
                && Game.instance.monsterManager.monsters != null) {
            for(final String theme : Game.instance.monsterManager.monsters.keySet()) {
                addAction(table, theme, () -> makeLayout(monsterList(theme)));
            }
        }
        addAction(table, "BACK", () -> makeLayout(mainMenu()));
        return table;
    }

    private Table monsterList(final String theme) {
        buttonOrder.clear();
        Table table = new Table();
        table.add(new Label(theme.toUpperCase(), skin.get(LabelStyle.class))).padBottom(4f).row();
        Array<Monster> list = Game.instance.monsterManager.monsters.get(theme);
        int column = 0;
        Table grid = new Table();
        if(list != null) for(final Monster monster : list) {
            if(monster == null || monster.name == null) continue;
            addAction(grid, monster.name, () -> spawnMonster(theme, monster.name), false);
            if(++column % 3 == 0) grid.row();
        }
        table.add(grid).row();
        addAction(table, "BACK", () -> makeLayout(monsterThemes()));
        return table;
    }

    private Table itemCategories() {
        buttonOrder.clear();
        Table table = new Table();
        table.add(new Label("ITEMS", skin.get(LabelStyle.class))).padBottom(4f).row();
        for(final Map.Entry<String, Array<Item>> entry : itemCatalogue().entrySet()) {
            addAction(table, entry.getKey(), () -> makeLayout(itemList(entry.getKey(), entry.getValue())));
        }
        addAction(table, "BACK", () -> makeLayout(mainMenu()));
        return table;
    }

    private Table itemList(final String category, Array<Item> items) {
        buttonOrder.clear();
        Table table = new Table();
        table.add(new Label(category.toUpperCase(), skin.get(LabelStyle.class))).padBottom(4f).row();
        Table grid = new Table();
        int column = 0;
        for(final Item item : items) {
            if(item == null) continue;
            String name = item.name == null || item.name.isEmpty() ? item.getClass().getSimpleName() : item.name;
            addAction(grid, name, () -> spawnItem(item), false);
            if(++column % 3 == 0) grid.row();
        }
        table.add(grid).row();
        addAction(table, "BACK", () -> makeLayout(itemCategories()));
        return table;
    }

    /** Only catalogued templates are spawnable: clients materialize by class, name and texture. */
    private Map<String, Array<Item>> itemCatalogue() {
        Map<String, Array<Item>> catalogue = new LinkedHashMap<String, Array<Item>>();
        ItemManager manager = Game.instance == null ? null : Game.instance.itemManager;
        if(manager == null) return catalogue;
        Array<Item> bombs = new Array<Item>();
        if(items != null) for(Item template : items.catalogueTemplates(FusedBomb.class)) bombs.add(template);
        if(bombs.size > 0) catalogue.put("bombs", bombs);
        if(manager.melee != null) for(Map.Entry<String, Array<Sword>> entry : manager.melee.entrySet()) {
            Array<Item> items = new Array<Item>();
            items.addAll(entry.getValue());
            catalogue.put("melee/" + entry.getKey(), items);
        }
        if(manager.ranged != null) for(Map.Entry<String, Array<Item>> entry : manager.ranged.entrySet()) {
            catalogue.put("ranged/" + entry.getKey(), entry.getValue());
        }
        if(manager.armor != null) for(Map.Entry<String, Array<Armor>> entry : manager.armor.entrySet()) {
            Array<Item> items = new Array<Item>();
            items.addAll(entry.getValue());
            catalogue.put("armor/" + entry.getKey(), items);
        }
        put(catalogue, "wands", manager.wands);
        put(catalogue, "potions", manager.potions);
        put(catalogue, "food", manager.food);
        put(catalogue, "scrolls", manager.scrolls);
        put(catalogue, "uniques", manager.unique);
        put(catalogue, "junk", manager.junk);
        return catalogue;
    }

    private static void put(Map<String, Array<Item>> catalogue, String name, Array<? extends Item> items) {
        if(items == null || items.size == 0) return;
        Array<Item> copy = new Array<Item>();
        copy.addAll(items);
        catalogue.put(name, copy);
    }

    // ---- actions ----

    private List<PartyMemberStatus> presentMembers() {
        List<PartyMemberStatus> members = new ArrayList<PartyMemberStatus>();
        PartyStatusSnapshot status = peer == null ? null : peer.getPartyStatus();
        if(status != null) for(PartyMemberStatus member : status.getMembers()) {
            if(member.getEntityId() != null) members.add(member);
        }
        return members;
    }

    private PartyMemberStatus targetMember() {
        List<PartyMemberStatus> members = presentMembers();
        for(PartyMemberStatus member : members) if(member.getCampaignSlot() == targetSlot) return member;
        return members.isEmpty() ? null : members.get(0);
    }

    private void cycleTarget() {
        List<PartyMemberStatus> members = presentMembers();
        if(members.isEmpty()) return;
        int index = 0;
        for(int i = 0; i < members.size(); i++) if(members.get(i).getCampaignSlot() == targetSlot) index = i;
        targetSlot = members.get((index + 1) % members.size()).getCampaignSlot();
    }

    private String targetName() {
        PartyMemberStatus member = targetMember();
        return member == null ? "nobody" : member.getNickname() + " (slot " + member.getCampaignSlot() + ")";
    }

    private ParticipantId targetParticipant() {
        PartyMemberStatus member = targetMember();
        return member == null ? null : new ParticipantId("campaign-slot-" + member.getCampaignSlot());
    }

    private com.interrupt.dungeoneer.entities.Actor targetActor() {
        PartyMemberStatus member = targetMember();
        if(member == null) return null;
        if(member.getEntityId().equals(peer.getLocalMovementEntityId())) return player;
        return movement == null ? null : movement.getRemoteAvatar(targetParticipant());
    }

    private void hurt(int amount, DamageType type, Entity instigator) {
        com.interrupt.dungeoneer.entities.Actor actor = targetActor();
        if(actor == null) return;
        actor.hit(0f, 0f, amount, 0f, type, instigator);
    }

    private void bomb(float damage) {
        com.interrupt.dungeoneer.entities.Actor actor = targetActor();
        if(actor == null || Game.instance == null || Game.instance.level == null) return;
        List<Item> templates = items == null ? new ArrayList<Item>() : items.catalogueTemplates(FusedBomb.class);
        if(templates.isEmpty()) {
            Game.ShowMessage("No bomb template in loaded content", 2, 1f);
            return;
        }
        FusedBomb bomb = (FusedBomb)templates.get(0);
        bomb.wasSpawned = true;
        bomb.isLit = true;
        bomb.isDud = false;
        bomb.chanceIsDud = 0f;
        bomb.countdownTimer = 60f;
        bomb.randomCountdownTimer = 0f;
        bomb.explosionDamage = damage;
        bomb.x = actor.x;
        bomb.y = actor.y;
        bomb.z = actor.z + 0.3f;
        bomb.isActive = true;
        bomb.isDynamic = true;
        Game.instance.level.SpawnEntity(bomb);
    }

    private void spawnItem(Item template) {
        if(Game.instance == null || Game.instance.level == null) return;
        Item item = ItemManager.Copy(template.getClass(), template);
        if(item == null) return;
        float dirX = (float)Math.sin(player.rot), dirY = (float)Math.cos(player.rot);
        item.x = player.x + dirX;
        item.y = player.y + dirY;
        item.z = player.z + 0.5f;
        item.isActive = true;
        item.isDynamic = true;
        item.spawnChance = 1f;
        if(item instanceof FusedBomb) ((FusedBomb)item).wasSpawned = true;
        Game.instance.level.SpawnEntity(item);
    }

    private void spawnMonster(String theme, String name) {
        if(combat == null || Game.instance == null) return;
        float dirX = (float)Math.sin(player.rot), dirY = (float)Math.cos(player.rot);
        boolean placed = combat.spawnNativeMonster(Game.instance, theme, name,
                player.x + dirX * 2f, player.y + dirY * 2f, player.z);
        if(!placed) Game.ShowMessage("Blocked: no room to spawn " + name, 2, 1f);
    }

    // ---- widgets ----

    private void addAction(Table table, String text, Runnable action) {
        addAction(table, text, action, true);
    }

    private void addAction(Table table, String text, final Runnable action, boolean row) {
        final Label label = new Label(text.toUpperCase(), skin.get("input", LabelStyle.class));
        label.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { action.run(); }
            @Override public void enter(InputEvent event, float x, float y, int pointer, Actor from) {
                label.setStyle(skin.get("inputover", LabelStyle.class));
            }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor to) {
                label.setStyle(skin.get("input", LabelStyle.class));
            }
        });
        buttonOrder.add(label);
        table.add(label).align(Align.left).padRight(8f);
        if(row) table.row();
    }
}
