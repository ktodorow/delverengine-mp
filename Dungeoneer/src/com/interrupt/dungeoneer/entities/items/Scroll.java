package com.interrupt.dungeoneer.entities.items;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.spells.Spell;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.managers.StringManager;

public class Scroll extends Item {
    /** Spell to cast when consumed. */
	public Spell spell;
	
	public void Read(Player player) {
		Vector3 direction = Game.camera.direction.cpy();
		if(player.requestItemConsume(this, direction)) return;
		applyNativeEffect(player, direction);
		presentRead(player);
	}

	/** Original scroll spell rule, executed once by Host after ownership acceptance. */
	public void applyNativeEffect(Actor owner, Vector3 direction) {
		if(spell != null) spell.zap(owner, direction);
	}

	/** Personal inventory/history feedback for consuming Participant only. */
	public void presentRead(Player player) {
		presentRead(player, false);
	}

	public void presentRead(Player player, boolean playSpellPresentation) {
		player.history.usedScroll(this);
		if(spell == null) Game.ShowMessage(StringManager.get("items.Scroll.nothingHappensText"), 1);
		else if(playSpellPresentation) spell.playZapPresentation(player,
				new Vector3(player.x, player.y, player.z));
		int location = player.inventory.indexOf(this, true);
		if(location >= 0) player.inventory.set(location, null);
		Game.RefreshUI();
	}

	public boolean inventoryUse(Player player){
		Read(player);
        return true;
	}

	@Override
	public void doPickup(Player player) {
		super.doPickup(player);
		Read(player);
	}
}
