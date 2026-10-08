package com.interrupt.dungeoneer.multiplayer.floor;

/** Scalar native content recipe; each peer loads assets locally, never an engine graph. */
public final class PartyDestination {
    public final long generation, seed;
    public final String areaKey, floorId, recipe;
    public final float[] arrival;
    public PartyDestination(long generation, String areaKey, String floorId, long seed) {
        this(generation, areaKey, floorId, seed, "", null);
    }
    public PartyDestination(long generation, String areaKey, String floorId, long seed, String recipe, float[] arrival) {
        if(recipe == null || recipe.length() > NativeFloorRecipe.MAX_BYTES || arrival != null && arrival.length != 4)
            throw new IllegalArgumentException("Invalid destination recipe/arrival.");
        if(!recipe.isEmpty()) NativeFloorRecipe.decode(recipe);
        if(arrival != null) for(float value : arrival) if(!Float.isFinite(value)) throw new IllegalArgumentException("Invalid arrival.");
        this.recipe = recipe; this.arrival = arrival == null ? null : arrival.clone();
        CampaignFloorState.requireAreaKey(areaKey); CampaignFloorState.requireAreaKey(floorId);
        if(generation < 1L || seed == 0L) throw new IllegalArgumentException("Invalid destination generation/seed.");
        this.generation = generation; this.areaKey = areaKey; this.floorId = floorId; this.seed = seed;
    }
}
