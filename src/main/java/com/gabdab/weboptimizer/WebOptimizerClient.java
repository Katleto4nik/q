package com.gabdab.weboptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public class WebOptimizerClient implements ClientModInitializer {
    private boolean menuKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        WebConfig.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WebLogic.tick(client);
            handleMenuKey(client);
        });
    }

    /** Right Shift opens the HUD (and closes it again). */
    private void handleMenuKey(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (down && !menuKeyWasDown && client.currentScreen == null && client.player != null) {
            client.setScreen(new WebScreen());
        }
        menuKeyWasDown = down;
    }
}
