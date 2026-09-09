package com.interrupt.dungeoneer.entities;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.gfx.drawables.DrawableMesh;
import com.interrupt.helpers.AnimationHelper;

public class Spikes extends Model {
    public interface MultiplayerTrapListener {
        void addMultiplayerTargets(Spikes spikes, Array<Entity> colliding);
        boolean hasNearbyMovingMultiplayerTarget(Spikes spikes, Vector3 sensorSize);
        void onTrapActivated(Spikes spikes);
    }

    protected AnimationHelper animation = null;
    private transient MultiplayerTrapListener multiplayerTrapListener;
    private transient boolean networkReplica = false;
    private transient float networkReplicaReverseTimer = 0f;

    protected enum SpikeType { STATIC, LOOPING, PROXIMITY, TRIGGERED }

    @Deprecated
    protected boolean doesMovement = true;

    @EditorProperty
    protected SpikeType spikeType = SpikeType.LOOPING;

    @EditorProperty
    protected float startDelay = 80f;

    @EditorProperty
    protected float reverseDelay = 80f;

    @EditorProperty
    protected float startOffset = 0f;

    @EditorProperty
    protected Vector3 proximitySensorSize = new Vector3(2f, 2f, 2f);

    protected float reverseTimer = 0f;
    protected transient Array<Entity> hitAlready = new Array<Entity>();
    protected transient Vector3 upDirection = new Vector3();

    public Spikes() { meshFile = "meshes/spikes.obj"; collision.set(0.5f, 0.5f, 0.5f); }

    @Override
    public void tick(Level level, float delta) {

        if(networkReplica) {
            tickNetworkReplica(level, delta);
            return;
        }

        if(spikeType != SpikeType.STATIC) {
            ensureAnimation();

            animation.tickAnimation(delta);

            Array<Entity> colliding = level.getEntitiesColliding(this);
            if(multiplayerTrapListener != null) {
                multiplayerTrapListener.addMultiplayerTargets(this, colliding);
            }

            // remove entities that we're no longer colliding with from the ignore list
            for (int i = 0; i < hitAlready.size; i++) {
                Entity e = hitAlready.get(i);
                if (!colliding.contains(e, true)) hitAlready.removeValue(e, true);
            }

            // hit the things that we're colliding now, if they're not in the ignore list yet
            for (int i = 0; i < colliding.size; i++) {
                Entity e = colliding.get(i);

                if (!hitAlready.contains(e, true) && animation.getAnimationPosition() >= 0.95f) {
                    hitAlready.add(e);

                    if(e instanceof Actor || (!animation.isDonePlaying() && !animation.isReversed())) {
                        // Do not apply velocity to non-dynamic Entities!
                        if (e.isDynamic) {
                            Vector3 dir = getSpikeDirection();

                            float moveAmount = (e instanceof Player) ? 0.04f : 0.03f;
                            e.xa = dir.x * moveAmount;
                            e.ya = dir.y * moveAmount;
                            e.za = dir.z * moveAmount;

                            e.physicsSleeping = false;
                        }
                    }

                    e.hit(0, 0, 2, 0f, Weapon.DamageType.PHYSICAL, this);
                }
            }

            if (animation.isDonePlaying()) {
                reverseTimer += delta;

                if ((!animation.isReversed() && reverseTimer > reverseDelay)) {
                    animation.reverse();
                    reverseTimer = 0f;
                }
                else if(animation.isReversed() && reverseTimer > startDelay) {
                    if((spikeType == SpikeType.PROXIMITY && entitiesNearby(level)) ||
                            spikeType == SpikeType.LOOPING) {
                        activateTrap();
                    }
                }
            }

            // hide the spikes when they're retracted
            hidden = animation.getAnimationPosition() <= 0f;
        }
        else {
            // these spikes are just static
            Array<Entity> colliding = level.getEntitiesColliding(this);
            if(multiplayerTrapListener != null) {
                multiplayerTrapListener.addMultiplayerTargets(this, colliding);
            }

            // remove entities that we're no longer colliding with from the ignore list
            for (int i = 0; i < hitAlready.size; i++) {
                Entity e = hitAlready.get(i);
                if (!colliding.contains(e, true)) hitAlready.removeValue(e, true);
            }

            // hit the things that we're colliding now, if they're not in the ignore list yet
            for (int i = 0; i < colliding.size; i++) {
                Entity e = colliding.get(i);

                if (!hitAlready.contains(e, true)) {
                    hitAlready.add(e);

                    if(e instanceof Actor) {
                        Vector3 dir = getSpikeDirection();
                        e.xa = dir.x * 0.05f;
                        e.ya = dir.y * 0.05f;
                        e.za = dir.z * 0.05f;
                        e.physicsSleeping = false;
                    }

                    e.hit(0, 0, 2, 0f, Weapon.DamageType.PHYSICAL, this);
                }
            }
        }
    }

    @Override
    public void onTrigger(Entity instigator, String value) {
        if(networkReplica) return;
        ensureAnimation();
        if(animation.isReversed() && reverseTimer > startDelay) {
            activateTrap();
        }
    }

    public boolean entitiesNearby(Level level) {
        Array<Entity> colliding = level.getEntitiesColliding(x, y, z - proximitySensorSize.z * 0.5f, proximitySensorSize, this);
        for(Entity e : colliding) {
            if(e.isDynamic) {
                // Only trigger when moving
                if(Math.abs(e.xa) > 0.01f || Math.abs(e.ya) > 0.01f)
                    return true;
            }
        }
        return multiplayerTrapListener != null
                && multiplayerTrapListener.hasNearbyMovingMultiplayerTarget(
                        this, proximitySensorSize);
    }

    public void setMultiplayerTrapListener(MultiplayerTrapListener listener) {
        multiplayerTrapListener = listener;
    }

    public void clearMultiplayerTrapListener(MultiplayerTrapListener listener) {
        if(multiplayerTrapListener == listener) multiplayerTrapListener = null;
    }

    public void setNetworkReplica(boolean networkReplica) {
        this.networkReplica = networkReplica;
        multiplayerTrapListener = null;
        hitAlready.clear();
        if(networkReplica && spikeType != SpikeType.STATIC) {
            ensureAnimation();
            animation.setAnimationPosition(0f);
            if(!animation.isReversed()) animation.reverse();
            hidden = true;
            networkReplicaReverseTimer = 0f;
        }
    }

    public boolean isNetworkReplica() { return networkReplica; }

    public void playNetworkActivation() {
        if(!networkReplica || spikeType == SpikeType.STATIC) return;
        ensureAnimation();
        if(animation.isReversed()) {
            hitAlready.clear();
            animation.reverse();
            networkReplicaReverseTimer = 0f;
            Audio.playPositionedSound("trap/trap_spike.mp3", new Vector3(x,y,z),
                    0.5f - (Game.rand.nextFloat() * 0.05f), 8f);
        }
    }

    private void ensureAnimation() {
        if(animation != null) return;
        animation = new AnimationHelper(new Vector3(), new Vector3(),
                new Vector3(0, 0, 0.5f), new Vector3(), 10f);
        animation.setAnimationPosition(0f);
        animation.reverse();
        reverseTimer = startOffset;
    }

    private void activateTrap() {
        hitAlready.clear();
        animation.reverse();
        reverseTimer = 0f;
        Audio.playPositionedSound("trap/trap_spike.mp3", new Vector3(x,y,z),
                0.5f - (Game.rand.nextFloat() * 0.05f), 8f);
        if(multiplayerTrapListener != null) {
            multiplayerTrapListener.onTrapActivated(this);
        }
    }

    private void tickNetworkReplica(Level level, float delta) {
        if(spikeType == SpikeType.STATIC) {
            tickAttached(level, delta);
            return;
        }
        ensureAnimation();
        animation.tickAnimation(delta);
        if(animation.isDonePlaying() && !animation.isReversed()) {
            networkReplicaReverseTimer += delta;
            if(networkReplicaReverseTimer > reverseDelay) {
                animation.reverse();
                networkReplicaReverseTimer = 0f;
            }
        }
        hidden = animation.getAnimationPosition() <= 0f;
        tickAttached(level, delta);
    }

    public Vector3 getSpikeDirection() {
        Vector3 dir = upDirection.set(1,0,0);
        dir.rotate(Vector3.Y, -rotation.y - 90f);
        dir.rotate(Vector3.X, -rotation.x);
        dir.rotate(Vector3.Z, -rotation.z);
        return dir;
    }

    @Override
    public void updateDrawable() {
        super.updateDrawable();

        if(!hidden && drawable != null && animation != null) {
            DrawableMesh drbl = (DrawableMesh)drawable;
            drbl.isStaticMesh = false;
            if(drbl.drawOffset == null) drbl.drawOffset = new Vector3();
            drbl.drawOffset.set(getSpikeDirection()).scl(animation.getCurrentPosition().z - 0.5f);

            drawable.update(this);
        }
    }
}
