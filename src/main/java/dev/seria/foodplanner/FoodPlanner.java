package dev.seria.foodplanner;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.lwjgl.glfw.GLFW;

@Mod(FoodPlanner.MOD_ID)
public class FoodPlanner {
    public static final String MOD_ID = "foodplanner";

    static final KeyMapping OPEN_KEY = new KeyMapping("key.foodplanner.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.misc");

    public FoodPlanner() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_KEY));
        MinecraftForge.EVENT_BUS.register(ContainerMemory.class);
        MinecraftForge.EVENT_BUS.addListener(FoodPlanner::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new FoodPlannerScreen());
        }
    }
}
