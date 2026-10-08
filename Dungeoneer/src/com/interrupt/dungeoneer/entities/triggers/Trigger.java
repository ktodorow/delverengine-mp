package com.interrupt.dungeoneer.entities.triggers;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.input.Actions;
import com.interrupt.dungeoneer.input.ReadableKeys;
import com.interrupt.dungeoneer.input.Actions.Action;
import com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.helpers.PlayerHistory;
import com.interrupt.managers.StringManager;

import java.text.MessageFormat;

public class Trigger extends Entity {
	/** Does this count as a secret? */
	@EditorProperty( group = "Trigger" )
	public boolean isSecret = false;

	public enum TriggerStatus {WAITING, TRIGGERED, RESETTING, DESTROYED}
	public enum TriggerType {USE, PLAYER_TOUCHED, ACTOR_TOUCHED, ANY_TOUCHED}
	public enum GameTime {WHENEVER, DESCENT, ESCAPE}

	/** Kind of action that causes trigger. */
	@EditorProperty( group = "Trigger" )
	public TriggerType triggerType = TriggerType.USE;

	/** Entity to send trigger event when triggered. */
	@EditorProperty( group = "Trigger" )
	public String triggersId = "";

	/** Reset after being triggered? */
	@EditorProperty( group = "Trigger" )
	public boolean triggerResets = true;

	/** Only allow trigger events from Entities with specified id. */
    @EditorProperty( group = "Trigger" )
    public String onlyTriggeredById = null;

    /** Time to wait before performing trigger action after receiving trigger event. */
	@EditorProperty( group = "Trigger" )
	public float triggerDelay = 0f;

	/** Time to wait to reset after performing trigger action. */
	@EditorProperty( group = "Trigger" )
	public float triggerResetTime = 0f;

	/** Trigger event value. */
	@EditorProperty( group = "Trigger" )
	public String triggerValue = "";

	/** Pass trigger event value to targeted trigger? */
	@EditorProperty( group = "Trigger" )
	public boolean triggerPropogates = true;

	/** Text to show for interaction prompt. */
	@EditorProperty( group = "Trigger" )
	public String useVerb = StringManager.get("entities.Trigger.defaultUseVerb");

	/** Message to display when triggered. */
	@EditorProperty( group = "Trigger" )
	public String message = "";

	/** Duration to display message in seconds. */
	@EditorProperty( group = "Trigger" )
	public float messageTime = 5f;

	/** Size of displayed message. */
	@EditorProperty( group = "Trigger" )
	public float messageSize = 1f;

	/** Filepath of sound to play when triggered. */
	@EditorProperty( group = "Trigger" )
	public String triggerSound = null;

	/** Which phase of the game to permit triggering. */
	@EditorProperty( group = "Trigger" )
	public GameTime triggersDuring = GameTime.WHENEVER;

	/** Does this appear during end game? */
	@EditorProperty( group = "End Game" )
	public boolean appearsDuringEndgame = true;
	
	protected boolean selfDestructs = true;
	
	protected TriggerStatus triggerStatus=TriggerStatus.WAITING;
	private float triggerTime = 0;
	private transient ParticipantContext triggeringParticipant;
    private com.interrupt.dungeoneer.multiplayer.participant.PendingTriggerParticipant pendingTriggerParticipant;
	private transient ParticipantContext propagatedParticipant;
	
	public Trigger() {
		hidden = true; spriteAtlas = "editor"; tex = 11;
	}
	
	public Trigger(String triggers) {
		this.artType = ArtType.hidden;
		this.triggersId = triggers;
	}
	
	public Trigger(String triggers, float delay) {
		this.artType = ArtType.hidden;
		this.triggersId = triggers;
		this.triggerDelay = delay;
	}

	@Override
	public void init(Level level, Level.Source source) {
		if(Game.instance != null && Game.instance.player != null) {
			// Might need to despawn this during the endgame
			if(Game.instance.player.isHoldingOrb) {
				if(!appearsDuringEndgame) {
					isActive = false;
				}
			}
		}
	}
	
	@Override
	public void tick(Level level, float delta) {
        getTriggeringParticipantContext();
        if(level != null && level.nativeTriggerReplica) return;
		
		// check for touch events
		if(triggerType != TriggerType.USE) {
            Array<Entity> encroaching = level.getEntitiesColliding(x, y, z, this);
            for (int i = 0; i < encroaching.size; i++) {
                Entity touching = encroaching.get(i);

                // skip entities that don't match the entity ID filter
                if(onlyTriggeredById != null && !onlyTriggeredById.isEmpty()) {
                    if(touching.id == null || !onlyTriggeredById.equals(touching.id)) continue;
                }

                if (touching instanceof Player && triggerType == TriggerType.PLAYER_TOUCHED) {
                    fire(localParticipant((Player)touching), null);
                }
                else if (touching instanceof Actor && triggerType == TriggerType.ACTOR_TOUCHED) fire(null);
                else if (triggerType == TriggerType.ANY_TOUCHED) fire(null);
            }
		}
		
		if (triggerStatus==TriggerStatus.DESTROYED && selfDestructs){
			this.isActive=false;
		}
		if (triggerStatus==TriggerStatus.RESETTING){
			triggerTime-=delta;
			if (triggerTime<=0){
				triggerStatus=TriggerStatus.WAITING;
				triggerTime=triggerDelay;
			}
		}
		if (triggerStatus==TriggerStatus.TRIGGERED){
			triggerTime-=delta;
			if (triggerTime<=0){
				doTriggerEvent(triggerValue); // fire!
				triggeringParticipant = null;
                pendingTriggerParticipant = null;
				if (triggerResets){
					triggerStatus=TriggerStatus.RESETTING;
					triggerTime=triggerResetTime;
				} else {
					triggerStatus=TriggerStatus.DESTROYED;
				}
			}
		}
		
		if(Game.isMobile && triggerType == TriggerType.USE && Math.abs(Game.instance.player.x - x) < 0.8f && Math.abs(Game.instance.player.y - y) < 0.8f) {
			String useText = ReadableKeys.keyNames.get(Actions.keyBindings.get(Action.USE));
			if(Game.isMobile) useText = StringManager.get("entities.Trigger.mobileUseText");
			Game.ShowUseMessage(MessageFormat.format(StringManager.get("entities.Trigger.mobileUseText"), useText, this.getUseVerb()));
		}
	}
    public void use(ParticipantContext participant) {
        fire(participant, null);
    }

    @Override
    public void use(Player p, float projx, float projy) {
		fire(localParticipant(p), null);
	}

	public String getUseVerb() {
		String useVerbLocalized = StringManager.get("triggers.Trigger.useVerbs." + useVerb);
		if (useVerbLocalized.startsWith("triggers.")) {
			useVerbLocalized = useVerb;
		}

		return useVerbLocalized;
	}

	public void fire(String value) {
		fire(propagatedParticipant != null ? propagatedParticipant : localParticipant(null), value);
	}

	public void fire(ParticipantContext participant, String value) {
        if(Game.instance != null && Game.instance.level != null && Game.instance.level.nativeTriggerReplica) return;

		// Check if we can actually fire now
		if(triggersDuring != GameTime.WHENEVER) {
			if(participant != null || (Game.instance != null && Game.instance.player != null)) {
				boolean endgame = participant != null
						? participant.getCharacter().isHoldingOrb()
						: Game.instance.player.isHoldingOrb;
				if(triggersDuring == GameTime.DESCENT && endgame) {
					return;
				}
				else if(triggersDuring == GameTime.ESCAPE && !endgame) {
					return;
				}
			}
		}

		// Track secrets
		if(isSecret) {
			isSecret = false;
			Game.instance.level.recordSecretDiscovery(this);
		}

		// Triggering an already triggered trigger will do nothing
		if (triggerStatus==TriggerStatus.WAITING){
			triggerStatus=TriggerStatus.TRIGGERED;
			triggerTime=triggerDelay;
			triggeringParticipant = participant;
            pendingTriggerParticipant = com.interrupt.dungeoneer.multiplayer.participant.PendingTriggerParticipant.capture(participant);
			
			// update the value if one was given
			if(value != null && !value.equals(""))
				triggerValue=value;
		}
	}
	
	@Override
	public void onTrigger(Entity instigator, String value) {
		if(triggerPropogates) {
			fire(propagatedParticipant != null ? propagatedParticipant : localParticipant(null), value);
		}
		else {
			fire(propagatedParticipant != null ? propagatedParticipant : localParticipant(null), triggerValue);
		}
	}

	@Override
	public void onTrigger(Entity instigator, String value, ParticipantContext participant) {
		propagatedParticipant = participant;
		try {
			onTrigger(instigator, value);
		}
		finally {
			propagatedParticipant = null;
		}
	}
	
	// triggers can be delayed, fire the actual trigger here
	public void doTriggerEvent(String value) {
        if(Game.instance != null && Game.instance.level != null && Game.instance.level.nativeTriggerReplica) return;
		Audio.playPositionedSound(triggerSound, new Vector3((float)x,(float)y,(float)z), 0.8f, 11f);
        Game.instance.level.publishTriggerSound(this);
		Game.instance.level.trigger(this, triggersId, triggerValue, triggeringParticipant);
		presentForActivator(value);
	}

	protected ParticipantContext getTriggeringParticipantContext() {
        if(triggeringParticipant == null && pendingTriggerParticipant != null)
            triggeringParticipant = pendingTriggerParticipant.restore();
		return triggeringParticipant;
	}

	/** This peer's own player set the chain off, or no Participant did (a Monster, a timer). */
	protected boolean activatedHere() {
		ParticipantContext participant = getTriggeringParticipantContext();
		return participant == null || LocalPlayerCompatibilityAdapter.LOCAL_PARTICIPANT_ID
				.equals(participant.getParticipantId());
	}

	/**
	 * Screen-only effects belong to the activator: shown here, handed by Host to the client that
	 * used this trigger, or skipped when that client runs its own copy (it walked into it).
	 */
	protected final void presentForActivator(String value) {
		if(activatedHere()) {
			presentToActivator(value, true);
			return;
		}
		ParticipantContext participant = getTriggeringParticipantContext();
		if(participant.isPresentedByActivator()) return;
		Level level = Game.instance == null ? null : Game.instance.level;
		if(level != null && level.nativeTriggerPresentationListener != null) {
			level.nativeTriggerPresentationListener.deliver(this, participant.getParticipantId(),
					value == null ? "" : value);
		}
	}

	/**
	 * Screen-only part of this trigger: message text here; overlays, flashes, music and
	 * achievements in subclasses. {@code continuesChain} is false on a client shown a chain
	 * Host already ran, so closing an overlay must not set anything off again.
	 */
	public void presentToActivator(String value, boolean continuesChain) {
		if(message != null && !message.equals("")) Game.ShowMessage(message, messageTime, messageSize);
	}

	protected ParticipantContext localParticipant(Player player) {
		if(Game.instance == null || Game.instance.progression == null) return null;
		Player participantPlayer = player == null ? Game.instance.player : player;
		if(participantPlayer == null) return null;
		return LocalPlayerCompatibilityAdapter.adapt(participantPlayer, Game.instance.progression);
	}

	@Override
	public void makeEntityIdUnique(String idPrefix) {
		super.makeEntityIdUnique(idPrefix);
		triggersId = makeUniqueIdentifier(triggersId, idPrefix);
	}

	public TriggerStatus getTriggerStatus() {
		return triggerStatus;
	}
}
