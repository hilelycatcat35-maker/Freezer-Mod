package com.freezer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public class FreezerMod implements ModInitializer {
    public static final String ID = "freezerboss";

    public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(
            Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(ID, "the_freezer"));

    public static final EntityType<FreezerBoss> FREEZER = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, KEY,
            EntityType.Builder.of(FreezerBoss::new, MobCategory.MONSTER)
                    .sized(0.7f, 1.9f)
                    .fireImmune()
                    .clientTrackingRange(10)
                    .build(KEY));

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(FREEZER, FreezerBoss.createBossAttributes());
    }
}
