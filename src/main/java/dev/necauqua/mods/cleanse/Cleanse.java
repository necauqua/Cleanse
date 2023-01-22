/*
 * Copyright (c) 2017-2021 Anton Bulakh <self@necauqua.dev>
 * Licensed under MIT, see the LICENSE file for details.
 */

package dev.necauqua.mods.cleanse;

import com.google.gson.JsonParseException;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.fml.IExtensionPoint.DisplayTest;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static net.minecraftforge.common.MinecraftForge.EVENT_BUS;
import static net.minecraftforge.fml.config.ModConfig.Type.CLIENT;
import static net.minecraftforge.network.NetworkConstants.IGNORESERVERONLY;

@Mod("cleanse")
public final class Cleanse {

    private static final Logger logger = LogManager.getLogger();

    private static final Set<String> vanillaLangKeys = new HashSet<>();

    private static final ForgeConfigSpec.Builder configBuilder = new ForgeConfigSpec.Builder();

    private static final IntValue delay = configBuilder
        .translation("config.cleanse:delay.name")
        .comment("Time in ticks that determines the duration of the chat suppression after you enter a world")
        .defineInRange("delay", 20, 1, Integer.MAX_VALUE);

    private static int timer = 0;

    // the best heuristic to check if a message is vanilla - to check if it's
    // a translation message and that its lang key is present in vanilla (and well, forge) lang files
    // does not work for /tellraw though, but oh well, good enough
    private static void loadKeys(String path) {
        try (var is = Language.class.getResourceAsStream(path)) {
            if (is != null) {
                Language.loadFromJson(is, (k, v) -> Cleanse.vanillaLangKeys.add(k));
            }
        } catch (JsonParseException | IOException e) {
            logger.error("Couldn't read strings from {}", path, e);
        }
    }

    public static void enterWorld() {
        timer = delay.get();
        logger.info("Filtering chat for the next {} ticks", timer);
    }

    public static boolean filterOut(Component component) {
        return timer != 0 && !(component.getContents() instanceof TranslatableContents contents && vanillaLangKeys.contains(contents.getKey()));
    }

    public Cleanse() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            logger.warn("This mod is clientside and was loaded on the dedicated server - it does absolutely nothing on the server, remove it");
            return;
        }

        loadKeys("/assets/minecraft/lang/en_us.json");
        loadKeys("/assets/forge/lang/en_us.json");

        ModLoadingContext.get().registerConfig(CLIENT, configBuilder.build(), "cleanse.toml");

        // allow connecting to vanilla servers
        ModLoadingContext.get().registerExtensionPoint(DisplayTest.class, () -> new DisplayTest(() -> IGNORESERVERONLY, (remoteVersion, isFromServer) -> true));

        // make the timer tick, literally
        EVENT_BUS.addListener((ClientTickEvent e) -> {
            if (e.phase == Phase.END && timer != 0 && --timer == 0) {
                logger.info("Reenabled adding new chat lines");
            }
        });
    }
}
