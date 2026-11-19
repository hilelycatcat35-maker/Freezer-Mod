package com.freezer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.SnowGolemRenderer;

public class FreezerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(FreezerMod.FREEZER, SnowGolemRenderer::new);
    }
}
