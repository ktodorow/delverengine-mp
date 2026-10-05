package com.interrupt.dungeoneer.entities.triggers;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.api.steam.SteamApi;
import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.game.Game;

public class TriggeredAchievement extends Trigger {

	public TriggeredAchievement() { hidden = true; spriteAtlas = "editor"; tex = 11; triggerResets = false; triggerType = TriggerType.PLAYER_TOUCHED; }

	@EditorProperty
	public String achievementName;
	
	@Override
	public void presentToActivator(String value, boolean continuesChain) {
		SteamApi.api.achieve(achievementName);
		super.presentToActivator(value, continuesChain);
	}
}
