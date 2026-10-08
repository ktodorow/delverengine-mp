package com.interrupt.dungeoneer.multiplayer.floor;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Render-thread native scene boundary. Host decides; every observer builds owned content locally. */
public final class NativePartyTravel {
    private final DirectConnectPeer peer;
    private final IntFunction<FreshCharacter> starter;
    private final Consumer<Level> install;
    private long installedGeneration;

    public NativePartyTravel(DirectConnectPeer peer, IntFunction<FreshCharacter> starter, Consumer<Level> install) {
        this.peer = peer; this.starter = starter; this.install = install;
        installedGeneration = peer.getNativeWorldGeneration();
    }
    public void update(Game game) {
        if(game == null || game.level == null) return;
        if(peer instanceof DirectConnectHost) {
            DirectConnectHost host = (DirectConnectHost)peer;
            for(Entity entity : portals(game.level)) host.registerPartyPortal(PartyPortals.key(entity), entity.x, entity.y,
                    entity instanceof Stairs ? game.level.getTile((int)entity.x, (int)entity.y).floorHeight + 0.5f : entity.z);
            if(peer.getPartyTransition().phase == PartyTransition.Phase.LOADING
                    && peer.getPartyTransition().generation == installedGeneration) {
                Entity portal = null;
                for(Entity entity : portals(game.level)) if(PartyPortals.key(entity).equals(peer.getPartyTransition().portal)) portal = entity;
                if(portal == null) throw new IllegalStateException("Party portal disappeared before destination build.");
                activate(game, host, portal);
                installedGeneration = peer.getNativeWorldGeneration();
            }
        }
        else {
            PartyDestination destination = peer.getPartyDestination();
            if(destination == null || destination.generation <= installedGeneration) return;
            if(destination.recipe.isEmpty()) throw new IllegalStateException("Destination has no native content recipe.");
            Level level = NativeFloorRecipe.decode(destination.recipe).build(destination.seed);
            level.multiplayerArrival = destination.arrival == null ? null : destination.arrival.clone();
            install.accept(level);
            installedGeneration = destination.generation;
            peer.recordSharedFloorFingerprint(level.multiplayerBuiltFingerprint);
        }
    }
    private void activate(Game game, DirectConnectHost host, Entity portal) {
        Level source = game.level;
        int characterLevel = Math.max(1, game.player.level);
        NativeFloorRecipe recipe;
        String area, floor;
        long seed = new java.security.SecureRandom().nextLong(); if(seed == 0L) seed = 1L;
        final boolean ascending = portal instanceof Stairs && ((Stairs)portal).direction == Stairs.StairDirection.up;
        TriggeredWarp warp = portal instanceof TriggeredWarp ? (TriggeredWarp)portal : null;
        boolean exiting = warp != null && warp.isExit || ascending && source.multiplayerParentArea != null;
        if(exiting) {
            if(source.multiplayerParentArea == null || source.multiplayerParentRecipe == null)
                throw new IllegalStateException("Native branch exit has no saved parent area.");
            area = source.multiplayerParentArea; floor = source.multiplayerParentFloor; seed = source.multiplayerParentSeed;
            recipe = NativeFloorRecipe.decode(source.multiplayerParentRecipe);
        }
        else if(warp != null) {
            recipe = NativeFloorRecipe.warp(warp, characterLevel);
            // Native tutorial's outward warp joins campaign's Camp instead of creating a branch.
            Level camp = Game.buildLevelLayout().first();
            if(!warp.generated && warp.levelToLoad != null && warp.levelToLoad.equals(camp.levelFileName)) {
                recipe = NativeFloorRecipe.campaign(0, characterLevel); area = "campaign:0"; floor = area;
            }
            else { area = PartyPortals.branchArea(host.getActiveAreaKey(), warp); floor = area; }
        }
        else {
            int current = source.multiplayerNativeRecipe == null ? 0 : NativeFloorRecipe.decode(source.multiplayerNativeRecipe).campaignIndex;
            if(current < 0) throw new IllegalStateException("Branch stairs have no native campaign destination.");
            int next = current + (ascending ? -1 : 1);
            if(next < 0 || next >= Game.buildLevelLayout().size) throw new IllegalStateException("Native stairs have no next campaign floor.");
            recipe = NativeFloorRecipe.campaign(next, characterLevel); area = "campaign:" + next; floor = area;
        }
        final NativeFloorRecipe destinationRecipe = recipe;
        final long destinationSeed = seed;
        final boolean returning = exiting;
        host.activatePartyDestination(area, floor, seed, () -> {
            Level level = destinationRecipe.build(destinationSeed);
            if(warp != null && !returning && destinationRecipe.campaignIndex < 0) {
                level.multiplayerParentArea = host.getActiveAreaKey(); level.multiplayerParentFloor = peer.getStatus().getFloorId();
                level.multiplayerParentSeed = peer.getSharedFloorSeed();
                level.multiplayerParentRecipe = source.multiplayerNativeRecipe;
                level.multiplayerReturnPosition = new float[] { portal.x, portal.y, portal.z, game.player.rot };
            }
            return level;
        }, level -> {
            if(returning) level.multiplayerArrival = source.multiplayerReturnPosition == null ? null : source.multiplayerReturnPosition.clone();
            else {
                Stairs arrival = ascending ? level.down : level.up;
                level.multiplayerArrival = arrival == null ? null : new float[] { arrival.x, arrival.y + 0.05f,
                        level.getTile((int)arrival.x, (int)arrival.y).floorHeight + 0.5f,
                        (float)Math.toRadians(arrival.direction == Stairs.StairDirection.up ? arrival.exitRotation + 180f : -arrival.exitRotation + 180f) };
            }
            if(warp != null && warp.toWarpMarkerId != null) {
                com.badlogic.gdx.utils.Array<Entity> markers = level.getEntitiesById(warp.toWarpMarkerId);
                if(markers.size == 0) markers = level.getEntitiesLikeId(warp.toWarpMarkerId);
                if(markers.size > 0) {
                    Entity marker = markers.first();
                    level.multiplayerArrival = new float[] { marker.x, marker.y, marker.z,
                            (float)Math.toRadians(marker.getRotation().z + 90f) };
                }
            }
        }, level -> {
            if(level.restoredCampaignFloor) level.loadForCampaign();
            install.accept(level);
        }, starter);
    }
    private static java.util.List<Entity> portals(Level level) {
        java.util.List<Entity> result = new java.util.ArrayList<>();
        for(com.badlogic.gdx.utils.Array<Entity> list : java.util.Arrays.asList(level.entities, level.static_entities, level.non_collidable_entities))
            if(list != null) for(Entity entity : list) if(entity.isActive && (entity instanceof Stairs || entity instanceof TriggeredWarp)) result.add(entity);
        return result;
    }
}
