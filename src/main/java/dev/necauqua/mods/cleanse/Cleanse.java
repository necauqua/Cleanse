/*
 * Copyright (c) 2017-2021 Anton Bulakh <self@necauqua.dev>
 * Licensed under MIT, see the LICENSE file for details.
 */

package dev.necauqua.mods.cleanse;

import com.google.common.collect.ForwardingList;
import com.google.gson.JsonParser;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.fml.IExtensionPoint.DisplayTest;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static net.minecraftforge.common.MinecraftForge.EVENT_BUS;
import static net.minecraftforge.fml.config.ModConfig.Type.CLIENT;
import static net.minecraftforge.fmllegacy.network.FMLNetworkConstants.IGNORESERVERONLY;

@Mod("cleanse")
public final class Cleanse {

    private static final Logger logger = LogManager.getLogger();

    private static final Set<String> vanillaLangKeys = new HashSet<>();

    static {
        vanillaLangKeys.addAll(loadKeys("/assets/minecraft/lang/en_us.json"));
        vanillaLangKeys.addAll(loadKeys("/assets/realms/lang/en_us.json"));
        vanillaLangKeys.addAll(loadKeys("/assets/forge/lang/en_us.json"));
    }

    // all of those fields are non-local because they are mutable lol
    private static int timer = 0;
    private static boolean checkWorldEnter = true;

    private static List<GuiMessage<Component>> originalChatLines;
    private static List<GuiMessage<FormattedCharSequence>> originalDrawnChatLines;
    private static List<GuiMessage<Component>> filteredChatLines;
    private static List<GuiMessage<FormattedCharSequence>> filteredDrawnChatLines;

    private static List<FormattedCharSequence> allowedLines = emptyList();

    public Cleanse() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            logger.warn("This mod is clientside and was loaded on the dedicated server - it does absolutely nothing on the server, remove it");
            return;
        }

        var configBuilder = new ForgeConfigSpec.Builder();
        var timeout = configBuilder
            .translation("config.cleanse:delay.name")
            .comment("Time in ticks that determines the duration of the chat suppression after you enter a world")
            .defineInRange("delay", 20, 1, Integer.MAX_VALUE);
        ModLoadingContext.get().registerConfig(CLIENT, configBuilder.build(), "cleanse.toml");

        // allow connecting to vanilla servers
        ModLoadingContext.get().registerExtensionPoint(DisplayTest.class, () -> new DisplayTest(() -> IGNORESERVERONLY, (a, b) -> true));

        // GuiOpenEvent is (sadly) the best way to detect when events that we need happen:
        // entering the world after it was loaded and closing said world,
        // and also coincidentally the closest event to the mc.gui field instantiation
        EVENT_BUS.addListener((GuiOpenEvent e) -> {

            // this check allows running code exactly at the moment
            // after the world finished loading and is rendered
            if (e.getGui() == null && checkWorldEnter) {
                checkWorldEnter = false;
                timer = timeout.get();
                logger.info("Started the timer to reenable adding new chat lines, waiting for {} ticks", timer);
                return;
            }

            if (!(e.getGui() instanceof TitleScreen) && !(e.getGui() instanceof JoinMultiplayerScreen)) {
                return;
            }
            // ^ and then this serves as the indication that the world got closed,
            // so we can prepare for the next time it is opened again..

            var chat = Minecraft.getInstance().gui.getChat();

            // ..and also when the game is first started, so we can prepare our dirty injections
            if (filteredChatLines == null) {
                filteredChatLines = new FilteredAddList<>(originalChatLines = chat.allMessages, line -> isVanilla(line.getMessage()));
                filteredDrawnChatLines = new FilteredAddList<>(originalDrawnChatLines = chat.trimmedMessages, line -> {
                    if (allowedLines.isEmpty() || !extractString(line.getMessage()).equals(extractString(allowedLines.get(0)))) {
                        return false;
                    }
                    allowedLines.remove(0);
                    return true;
                });
            }

            // allow entering the world to be checked again (if we already were in a world)
            checkWorldEnter = true;

            // if someone closed the world before the timer runs out (e.g. someone set a giant timer)
            if (timer != 0) {
                logger.info("Disabled the timer to reenable adding new chat lines, was {} ticks left", timer);
                timer = 0;
                // just disable the timer and the below code is not needed since the timer didn't yet reset the filters
                return;
            }

            if (chat.allMessages == filteredChatLines && chat.trimmedMessages == filteredDrawnChatLines) {
                // to avoid running below code (including log spam) unnecessarily
                // in case someone opens and closes multiplayer gui or similar
                return;
            }

            sanityCheck(chat);
            chat.allMessages = filteredChatLines;
            chat.trimmedMessages = filteredDrawnChatLines;
            logger.info("Disabled adding new chat lines");
        });

        // make the timer tick, literally
        EVENT_BUS.addListener((ClientTickEvent e) -> {
            if (e.phase != Phase.END || timer == 0 || --timer != 0) {
                return;
            }
            // reset the filters after it runs out
            var chat = Minecraft.getInstance().gui.getChat();
            sanityCheck(chat);
            chat.allMessages = originalChatLines;
            chat.trimmedMessages = originalDrawnChatLines;
            logger.info("Reenabled adding new chat lines");
        });

        // get the unsplit text elements that chat receives and if they are vanilla,
        // split them and add the result to allowed lines
        EVENT_BUS.addListener((ClientChatReceivedEvent e) -> {
            if (!isVanilla(e.getMessage())) {
                return;
            }
            var mc = Minecraft.getInstance();
            var chat = mc.gui.getChat();

            // copying vanilla behaviour from the ChatComponent#setChatLine here
            var i = Mth.floor((double) chat.getWidth() / chat.getScale());
            allowedLines = ComponentRenderUtils.wrapComponents(e.getMessage(), i, mc.font);
        });
    }

    // idk maybe somebody else is also changing those exact
    // two private final fields, scream about it in logs
    private static void sanityCheck(ChatComponent chat) {
        if (chat.allMessages != originalChatLines && chat.allMessages != filteredChatLines) {
            logger.error("SOME OTHER MOD DID THE SAME DIRTY HACK WE DID, " +
                "THE `allMessages` FIELD WAS REPLACED WITH SOMETHING ELSE, THIS WILL BREAK THEIR THINGS");
        }
        if (chat.trimmedMessages != originalDrawnChatLines && chat.trimmedMessages != filteredDrawnChatLines) {
            logger.error("SOME OTHER MOD DID THE SAME DIRTY HACK WE DID, " +
                "THE `trimmedMessages` FIELD WAS REPLACED WITH SOMETHING ELSE, THIS WILL BREAK THEIR THINGS");
        }
    }

    // the best heuristic to check if a message is vanilla - to check if it's
    // a translation message and that its lang key is present in vanilla (and well, forge) lang files
    // does not work for /tellraw though, but oh well, good enough
    private static boolean isVanilla(Component text) {
        return text instanceof TranslatableComponent && vanillaLangKeys.contains(((TranslatableComponent) text).getKey());
    }

    // meh
    private static String extractString(FormattedCharSequence reorderingProcessor) {
        var sb = new StringBuilder();
        reorderingProcessor.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    // just read the set of keys from given resource in exact (almost,
    // json parsing is nicer here) same way vanilla does it in LanguageMap
    private static Set<String> loadKeys(String path) {
        try (var is = Cleanse.class.getResourceAsStream(path)) {
            if (is == null) {
                return emptySet();
            }
            return GsonHelper.convertToJsonObject(new JsonParser().parse(new InputStreamReader(is)), "root")
                .entrySet()
                .stream()
                .map(Map.Entry::getKey)
                .collect(toSet());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the localization file: " + path);
        }
    }

    private static final class FilteredAddList<T> extends ForwardingList<T> {

        private final List<T> original;
        private final Predicate<T> filter;

        public FilteredAddList(List<T> original, Predicate<T> filter) {
            this.original = original;
            this.filter = filter;
        }

        @Override
        public void add(int index, T element) {
            if (filter.test(element)) {
                super.add(index, element);
            }
        }

        @Override
        protected List<T> delegate() {
            return original;
        }
    }
}
