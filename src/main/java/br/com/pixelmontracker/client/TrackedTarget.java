package br.com.pixelmontracker.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record TrackedTarget(
        String id,
        TargetType type,
        String label,
        Vec3 position,
        int color,
        String dimension,
        int level,
        boolean shiny,
        boolean mega,
        boolean legendary,
        boolean boss,
        int bossPriority,
        boolean stale,
        ResourceLocation sprite
) {
    public TrackedTarget withStale(boolean value) {
        return new TrackedTarget(id, type, label, position, color, dimension, level,
                shiny, mega, legendary, boss, bossPriority, value, sprite);
    }

    public enum TargetType {
        POKEMON,
        POKELOOT
    }
}
