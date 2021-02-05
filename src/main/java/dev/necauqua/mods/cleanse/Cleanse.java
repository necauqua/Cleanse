package dev.necauqua.mods.cleanse;

import com.google.common.collect.ForwardingList;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.config.ModConfig.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
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
import static net.minecraftforge.fml.network.FMLNetworkConstants.IGNORESERVERONLY;

@Mod(Cleanse.ID)
public final class Cleanse {

    public static final String ID = "cleanse";

    private static final Logger logger = LogManager.getLogger();

    private static final Set<String> vanillaLangKeys = new HashSet<>();

    static {
        vanillaLangKeys.addAll(loadKeys("/assets/minecraft/lang/en_us.json"));
        vanillaLangKeys.addAll(loadKeys("/assets/realms/lang/en_us.json"));
        vanillaLangKeys.addAll(loadKeys("/assets/forge/lang/en_us.json"));
    }

    // all of those fields are non-local because they are mutable lol
    private static int timeout = 20;

    private static int timer = 0;
    private static boolean checkWorldEnter = true;

    private static List<ChatLine> originalChatLines;
    private static List<ChatLine> originalDrawnChatLines;
    private static List<ChatLine> filteredChatLines;
    private static List<ChatLine> filteredDrawnChatLines;

    private static List<ITextComponent> allowedLines = emptyList();

    private static ForgeConfigSpec.IntValue timeoutProp;

    public Cleanse() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            logger.warn("This mod is clientside and was loaded on the dedicated server - it does absolutely nothing on the server, remove it");
            return;
        }

        setupConfig();

        // allow connecting to vanilla servers
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> IGNORESERVERONLY, (a, b) -> true));

        // GuiOpenEvent is (sadly) the best way to detect when events that we need happen:
        // entering the world after it was loaded and closing said world,
        // and also coincidentally the closest event to the ingameGUI field instantiation
        EVENT_BUS.addListener((GuiOpenEvent e) -> {

            // this check allows to run code exactly at the moment
            // after the world finished loading and is rendered
            if (e.getGui() == null && checkWorldEnter) {
                checkWorldEnter = false;
                timer = timeout;
                logger.info("Started the timer to reenable adding new chat lines, waiting for {} ticks", timeout);
                return;
            }

            if (!(e.getGui() instanceof GuiMainMenu) && !(e.getGui() instanceof GuiMultiplayer)) {
                return;
            }
            // ^ and then this serves as the indication that the world got closed,
            // so we can prepare for the next time it is opened again..

            GuiNewChat chat = Minecraft.getInstance().ingameGUI.getChatGUI();

            // ..and also when the game is first started so we can prepare our dirty injections
            if (filteredChatLines == null) {
                filteredChatLines = new FilteredAddList<>(originalChatLines = chat.chatLines, line -> isVanilla(line.getChatComponent()));
                filteredDrawnChatLines = new FilteredAddList<>(originalDrawnChatLines = chat.drawnChatLines, line -> {
                    if (allowedLines.isEmpty() || !line.getChatComponent().getFormattedText().equals(allowedLines.get(0).getFormattedText())) {
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

            if (chat.chatLines == filteredChatLines && chat.drawnChatLines == filteredDrawnChatLines) {
                // to avoid running below code (including log spam) unnecessarily
                // in case someone opens and closes multiplayer gui or similar
                return;
            }

            sanityCheck(chat);
            chat.chatLines = filteredChatLines;
            chat.drawnChatLines = filteredDrawnChatLines;
            logger.info("Disabled adding new chat lines");
        });

        // make the timer tick, literally
        EVENT_BUS.addListener((ClientTickEvent e) -> {
            if (e.phase != Phase.END || timer == 0 || --timer != 0) {
                return;
            }
            // reset the filters after it runs out
            GuiNewChat chat = Minecraft.getInstance().ingameGUI.getChatGUI();
            sanityCheck(chat);
            chat.chatLines = originalChatLines;
            chat.drawnChatLines = originalDrawnChatLines;
            logger.info("Reenabled adding new chat lines");
        });

        // get the unsplit text elements that chat receives and if they are vanilla,
        // split them and add the result to allowed lines
        EVENT_BUS.addListener((ClientChatReceivedEvent e) -> {
            if (!isVanilla(e.getMessage())) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            GuiNewChat chat = mc.ingameGUI.getChatGUI();

            // copying vanilla behaviour from the GuiNewChat#setChatLine here
            int i = MathHelper.floor((float) chat.getChatWidth() / chat.getScale());
            allowedLines = GuiUtilRenderComponents.splitText(e.getMessage(), i, mc.fontRenderer, false, false);
        });
    }

    // idk maybe somebody else is also changing those exact
    // two private final fields, scream about it in logs
    private static void sanityCheck(GuiNewChat chat) {
        if (chat.chatLines != originalChatLines && chat.chatLines != filteredChatLines) {
            logger.error("SOME OTHER MOD DID THE SAME DIRTY HACK WE DID, " +
                    "THE `chatLines` FIELD WAS REPLACED WITH SOMETHING ELSE, THIS WILL BREAK THEIR THINGS");
        }
        if (chat.drawnChatLines != originalDrawnChatLines && chat.drawnChatLines != filteredDrawnChatLines) {
            logger.error("SOME OTHER MOD DID THE SAME DIRTY HACK WE DID, " +
                    "THE `drawnChatLines` FIELD WAS REPLACED WITH SOMETHING ELSE, THIS WILL BREAK THEIR THINGS");
        }
    }

    // the best heuristic to check if a message is vanilla - to check if it's
    // a translation message and that its lang key is present in vanilla (and well, forge) lang files
    // does not work for /tellraw though, but oh well, good enough
    private static boolean isVanilla(ITextComponent text) {
        return text instanceof TextComponentTranslation && vanillaLangKeys.contains(((TextComponentTranslation) text).getKey());
    }

    // idk looked too messy so moved to a separate method
    private static void setupConfig() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        timeoutProp = builder
                .translation("config.cleanse:delay.name")
                .comment("Time in ticks that determines the duration of the chat suppression after you enter a world")
                .defineInRange("delay", timeout, 1, Integer.MAX_VALUE);

        ModLoadingContext.get().registerConfig(CLIENT, builder.build(), ID + ".toml");

        FMLJavaModLoadingContext.get().getModEventBus().addListener((ModConfigEvent e) -> {
            if (ID.equals(e.getConfig().getModId())) {
                timeout = timeoutProp.get();
                logger.debug("Loaded the timeout config property");
            }
        });
    }

    // just read the set of keys from given resource in exact (almost,
    // json parsing is nicer here) same way vanilla does it in LanguageMap
    private static Set<String> loadKeys(String path) {
        try (InputStream is = Cleanse.class.getResourceAsStream(path)) {
            if (is == null) {
                return emptySet();
            }
            return JsonUtils.getJsonObject(new JsonParser().parse(new InputStreamReader(is)), "root")
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
