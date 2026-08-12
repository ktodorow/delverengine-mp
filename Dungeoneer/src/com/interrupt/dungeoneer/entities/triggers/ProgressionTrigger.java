package com.interrupt.dungeoneer.entities.triggers;

import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.PartyProgression;

public class ProgressionTrigger extends Trigger {
	
	@EditorProperty
	public String progressionKey = "TO_CHECK";

	@EditorProperty
	public String newProgressionValue = "did_trigger";

	@EditorProperty
	public String checkProgressionKey = null;

	@EditorProperty
	public String checkProgressionValue = null;

	@EditorProperty
	public ProgressionType progressionType = ProgressionType.ONCE;

	@EditorProperty
	public String triggersOnFail = null;

	@EditorProperty
	public ProgressionPersistance persistance = ProgressionPersistance.FOREVER;

	public enum ProgressionType { ONCE, UNLIMITED }

	public enum ProgressionPersistance { FOREVER, UNTIL_DEATH }

	@Override
	public void doTriggerEvent(String value) {
		ParticipantContext participant = getTriggeringParticipantContext();
		if(participant == null) participant = LocalPlayerCompatibilityAdapter.fromGame();
		PartyProgression progression = participant.getPartyProgression();

		if(progressionKey != null) {
			String pv = getMyProgressionValue(progression);

			if(progressionType == ProgressionType.ONCE) {
				if (pv == null || pv.isEmpty()) {
					if(checkProgressionValue(progression)) {
						super.doTriggerEvent(value);
						updateProgressionValue(progression);
					}
					else {
						triggerOnFail();
					}
				}
			}
			else  {
				if(checkProgressionValue(progression)) {
					super.doTriggerEvent(value);
					updateProgressionValue(progression);
				}
				else {
					triggerOnFail();
				}
			}
		}
	}

	public void triggerOnFail() {
		if(triggersOnFail != null && !triggersOnFail.isEmpty())
			Game.instance.level.trigger(this, triggersOnFail, null,
					getTriggeringParticipantContext());
	}

	public boolean checkProgressionValue() {
		ParticipantContext participant = getTriggeringParticipantContext();
		if(participant == null) participant = LocalPlayerCompatibilityAdapter.fromGame();
		return checkProgressionValue(participant.getPartyProgression());
	}

	private boolean checkProgressionValue(PartyProgression progression) {
		// Make sure this can actually trigger
		if (checkProgressionValue == null || checkProgressionValue.isEmpty()) {
			return true;
		}

		if (checkProgressionKey == null || checkProgressionKey.isEmpty()) {
			return true;
		}

		String pv = getOtherProgressionValue(progression);

		return pv != null && pv.equals(checkProgressionValue);

	}

	private String getProgressionValue(PartyProgression progression, String key) {
		// check forever progression first
		String value = progression.getPersistent(key);
		if(value != null && !value.isEmpty()) {
			return value;
		}

		// fall back to transient progression
		return progression.getUntilDeath(key);
	}

	private String getMyProgressionValue(PartyProgression progression) {
		return getProgressionValue(progression, progressionKey);
	}

	private String getOtherProgressionValue(PartyProgression progression) {
		return getProgressionValue(progression, checkProgressionKey);
	}

	private void updateProgressionValue(PartyProgression progression) {
		if(persistance == ProgressionPersistance.FOREVER) {
			progression.putPersistent(progressionKey, newProgressionValue);
		}
		else {
			progression.putUntilDeath(progressionKey, newProgressionValue);
		}
	}
}
