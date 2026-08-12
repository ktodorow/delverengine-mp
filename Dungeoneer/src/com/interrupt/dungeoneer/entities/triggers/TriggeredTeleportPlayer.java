package com.interrupt.dungeoneer.entities.triggers;

import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.entities.DynamicLight;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Particle;
import com.interrupt.dungeoneer.entities.PositionedSound;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;

public class TriggeredTeleportPlayer extends Trigger {
	public TriggeredTeleportPlayer() { hidden = true; spriteAtlas = "editor"; tex = 11; }
	
	@EditorProperty
	boolean doEffects=false;
	
	@EditorProperty
	boolean useOffsets=false;

	@EditorProperty
	public String toWarpMarkerId = null;
	
	@Override
	public void doTriggerEvent(String value) {
		ParticipantContext participant = getTriggeringParticipantContext();
		if(participant == null) participant = LocalPlayerCompatibilityAdapter.fromGame();
		teleportParticipant(participant, Game.GetLevel());
	}

	void teleportParticipant(ParticipantContext participant, Level level) {
		ParticipantCharacter character = participant.getCharacter();

		float xTarget = 0;
		float yTarget = 0;
		
		if (useOffsets){
			xTarget = Math.round(character.getX()) - character.getX();
			yTarget = Math.round(character.getY()) - character.getY();
		}

		if(toWarpMarkerId != null) {
			float participantRotation = character.getRotation();
			putParticipantAtWarpMarker(character, toWarpMarkerId, level);
			if(useOffsets) {
				character.setRotation(participantRotation);
			}
		}

		if (doEffects) doEffect(new Vector3(character.getX() + 0.5f,
				character.getY() + 0.5f, character.getZ()), level);
		
		character.setPosition(character.getX() - xTarget,
				character.getY() - yTarget, character.getZ());
		
		if (doEffects) doEffect(new Vector3(character.getX() + 0.5f,
				character.getY() + 0.5f, character.getZ()), level);
	}

	private void putParticipantAtWarpMarker(ParticipantCharacter participant, String warpMarkerId,
			Level level) {
		if(warpMarkerId == null || level == null) return;

		com.badlogic.gdx.utils.Array<Entity> found = level.getEntitiesById(warpMarkerId);
		if(found.size == 0) found = level.getEntitiesLikeId(warpMarkerId);
		if(found.size == 0) return;

		Entity warpTo = found.first();
		participant.setPosition(warpTo.x, warpTo.y, warpTo.z);
		participant.setRotation((float)Math.toRadians(warpTo.getRotation().z + 90f));
	}
	
	public void doEffect(Vector3 pos, Level level) {
		Random r = Game.rand;
		int particleCount = 3;
		particleCount *= Options.instance.gfxQuality;
		if(particleCount <= 0) particleCount = 1;
		
		for(int i = 0; i < particleCount; i++)
		{
			int speed = r.nextInt(35) + 20;
			Particle part = new Particle(pos.x + r.nextFloat() * 0.4f - 0.2f, pos.y + r.nextFloat() * 0.4f - 0.2f, pos.z, 0f, 0f, 0f, 0, Color.ORANGE, true);
			part.floating = true;
			part.playAnimation(8, 13, speed);
			level.SpawnNonCollidingEntity(part);
		}
		
		level.SpawnNonCollidingEntity( new DynamicLight(pos.x,pos.y,pos.z, new Vector3(Color.ORANGE.r * 2f, Color.ORANGE.g * 2f, Color.ORANGE.b * 2f)).startLerp(new Vector3(0,0,0), 40, true) );
		level.SpawnNonCollidingEntity( new PositionedSound(pos.x, pos.y, pos.z, Audio.spell, 0.9f, 12f, 200));
	}

	@Override
	public void makeEntityIdUnique(String idPrefix) {
		super.makeEntityIdUnique(idPrefix);
		toWarpMarkerId = makeUniqueIdentifier(toWarpMarkerId, idPrefix);
	}
}
