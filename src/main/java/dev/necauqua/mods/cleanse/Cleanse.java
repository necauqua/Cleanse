package dev.necauqua.mods.cleanse;

import com.google.common.collect.ForwardingList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.translation.LanguageMap;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLModDisabledEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

@Mod(modid = Cleanse.ID,
        clientSideOnly = true,
        useMetadata = true,
        updateJSON = "https://raw.githubusercontent.com/wiki/necauqua/cleanse/updates.json")
@EventBusSubscriber(Side.CLIENT)
public final class Cleanse {

    public static final String ID = "cleanse";

    private static final Logger logger = LogManager.getLogger("Cleanse");

    private static final Set<String> vanillaKeys = new HashSet<>();

    private static boolean enabled = true;
    private static int timeout = 20;

    private static int timer = 0;
    private static boolean checkWorldEnter = true;

    private static List<ChatLine> originalChatLines;
    private static List<ChatLine> originalDrawnChatLines;
    private static TheGreatFilter filteredChatLines;
    private static TheGreatFilter filteredDrawnChatLines;

    private static Configuration config;

    @EventHandler
    public static void on(FMLPreInitializationEvent e) {
        config = new Configuration(e.getSuggestedConfigurationFile());
        config.load();
        loadConfig();
        loadVanillaKeys();
    }

    private static void loadConfig() {
        timeout = config.getInt("timeout", "main", timeout, 1, 1200,
                "Time in ticks that determines the duration of the chat suppression after you enter a world");
        if (config.hasChanged()) {
            config.save();
        }
    }

    private static void loadVanillaKeys() {
        vanillaKeys.addAll(loadKeys("/assets/minecraft/lang/en_us.lang"));
        vanillaKeys.addAll(loadKeys("/assets/realms/lang/en_us.lang"));
        vanillaKeys.addAll(loadKeys("/assets/forge/lang/en_US.lang"));
    }

    @SubscribeEvent
    public static void on(OnConfigChangedEvent e) {
        if (ID.equals(e.getModID())) {
            loadConfig();
        }
    }

    @EventHandler
    public static void on(FMLModDisabledEvent e) { // idk this is not sent anywhere in 1.12 forge lol
        enabled = false;
        logger.info("The mod was disabled from the modlist");
        if (timer != 0) {
            logger.info("Disabled the timer to reenable adding new chat lines, was {} ticks left", timer);
            timer = 0;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiNewChat chat = mc.ingameGUI.getChatGUI();
        sanityCheck(chat);
        chat.chatLines = originalChatLines; // just unconditionally reset everything
        chat.drawnChatLines = originalDrawnChatLines;
    }

    private static Set<String> loadKeys(String path) {
        InputStream is = Cleanse.class.getResourceAsStream(path);
        return is != null ?
                LanguageMap.parseLangFile(is).keySet() :
                emptySet();
    }

    // the best heuristic to check if a message is vanilla - to check if it's a translation message
    // and that its lang key is present in vanilla (and well, forge) lang files
    // does not work for /tellraw though I think, but oh well, good enough
    private static boolean isVanilla(ITextComponent text) {
        return text instanceof TextComponentTranslation && vanillaKeys.contains(((TextComponentTranslation) text).getKey());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void on(GuiOpenEvent e) {
        if (!enabled) {
            return;
        }
        if (e.getGui() == null && checkWorldEnter) {
            checkWorldEnter = false;

            timer = timeout;

            logger.info("Started the timer to reenable adding new chat lines, waiting for {} ticks", timeout);

        } else if (e.getGui() instanceof GuiMainMenu || e.getGui() instanceof GuiMultiplayer) {
            // ^ closest event we can get after ingameGUI instantiation is opening of the GuiMainMenu

            GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
            if (filteredChatLines == null) {
                filteredChatLines = new TheGreatFilter(originalChatLines = chat.chatLines, false);
                filteredDrawnChatLines = new TheGreatFilter(originalDrawnChatLines = chat.drawnChatLines, true);
            }

            checkWorldEnter = true; // allow closing the gui to be checked again

            // if someone closes the world before the timer runs out
            if (timer != 0) {
                logger.info("Disabled the timer to reenable adding new chat lines, was {} ticks left", timer);
                timer = 0;
                return;
            }

            if (chat.chatLines == filteredChatLines && chat.drawnChatLines == filteredDrawnChatLines) {
                // just to avoid repeated logs, the disabling code below is idempotent anyway
                return;
            }
            sanityCheck(chat);
            chat.chatLines = filteredChatLines;
            chat.drawnChatLines = filteredDrawnChatLines;
            logger.info("Disabled adding new chat lines");
        }
    }

    @SubscribeEvent
    public static void on(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || timer == 0 || --timer != 0) {
            return;
        }
        GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
        sanityCheck(chat);
        chat.chatLines = originalChatLines;
        chat.drawnChatLines = originalDrawnChatLines;
        logger.info("Reenabled adding new chat lines");
    }

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

    @SubscribeEvent
    public static void on(ClientChatReceivedEvent e) {
        if (!(enabled && isVanilla(e.getMessage()))) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiNewChat chat = mc.ingameGUI.getChatGUI();

        // Yes, this mess is to allow vanilla messages
        //
        // Basically the `drawnChatLines.add` call receives only one line of
        // a potentially multiline message at a time with no connection to
        // the original TextComponentTranslation and its lang key
        //
        // So we check it separately in this event handler, and then create
        // our own split and compare incoming lines with that

        // copying vanilla behaviour from the GuiNewChat#setChatLine here
        int i = MathHelper.floor((float) chat.getChatWidth() / chat.getChatScale());
        filteredChatLines.allowedLines = filteredDrawnChatLines.allowedLines =
                GuiUtilRenderComponents.splitText(e.getMessage(), i, mc.fontRenderer, false, false);
    }

    // sadly can't just cancel the ClientChatReceivedEvent, because you can call
    // `Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(...)` directly
    // as a workaround for that and a lot of mods actually do that (e.g. optifine)
    //
    // and also this dirty hack is much more assertive lol
    private static final class TheGreatFilter extends ForwardingList<ChatLine> {

        private final List<ChatLine> list;
        private final boolean split;

        private List<ITextComponent> allowedLines = emptyList();

        public TheGreatFilter(List<ChatLine> list, boolean split) {
            this.list = list;
            this.split = split;
        }

        @Override
        public void add(int index, ChatLine element) {
            ITextComponent text = element.getChatComponent();
            if (!split) {
                if (isVanilla(text)) {
                    super.add(index, element);
                }
            } else if (!allowedLines.isEmpty() && text.getFormattedText().equals(allowedLines.get(0).getFormattedText())) {
                // see big comment in ClientChatReceivedEvent listener
                allowedLines.remove(0);
                super.add(index, element);
            }
        }

        @Override
        protected List<ChatLine> delegate() {
            return list;
        }
    }
}
