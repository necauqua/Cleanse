package dev.necauqua.mods.cleanse;

import com.google.common.collect.ForwardingList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.translation.LanguageMap;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

@Mod(modid = "cleanse",
        clientSideOnly = true,
        updateJSON = "https://raw.githubusercontent.com/wiki/necauqua/cleanse/updates.json")
@EventBusSubscriber
public final class Cleanse {

    private static final Logger logger = LogManager.getLogger("Cleanse");

    private static final Set<String> vanillaKeys = new HashSet<>();

    private static int delay = 1000;

    private static boolean firstMainGui = false;
    private static boolean firstWorldOpen = false;

    private static List<ChatLine> originalChatLines;
    private static List<ChatLine> originalDrawnChatLines;
    private static List<ChatLine> filteredChatLines;
    private static List<ChatLine> filteredDrawnChatLines;

    private static List<ITextComponent> allowedChatLines = emptyList();

    @EventHandler
    public static void on(FMLPreInitializationEvent e) {
        Configuration c = new Configuration(e.getSuggestedConfigurationFile());
        c.load();
        delay = c.getInt("delay", "main", delay, 1, 60000, "Time in milliseconds that determines the duration of the chat suppression from the start of the game");
        if (c.hasChanged()) {
            c.save();
        }

        loadVanillaKeys();
    }

    @SubscribeEvent
    public static void on(GuiOpenEvent e) {
        // closest event we can get after ingameGUI instantiation is
        // (coincidentally) GuiOpenEvent for the GuiMainMenu
        if (e.getGui() instanceof GuiMainMenu) {
            if (firstMainGui) {
                return;
            }
            firstMainGui = true;

            Minecraft mc = Minecraft.getMinecraft();
            GuiNewChat chat = mc.ingameGUI.getChatGUI();

            // sadly can't just cancel the ClientChatReceivedEvent, because you can call
            // `Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(...)` directly
            // as a workaround for that and a lot of mods actually do that (e.g. optifine)

            chat.chatLines = filteredChatLines = new TheGreatFilter(originalChatLines = chat.chatLines, false);
            chat.drawnChatLines = filteredDrawnChatLines = new TheGreatFilter(originalDrawnChatLines = chat.drawnChatLines, true);

            logger.info("Disabled adding new chat lines");
        }

        if (e.getGui() != null || firstWorldOpen) {
            return;
        }
        firstWorldOpen = true;

        // yeah would be good if Minecraft event loop had scheduled tasks
        // (like actually *scheduled* tasks and not this 'post' that got a shit mapping),
        // and I don't want to make a tick handler that will be forever useless after the supressing period ends
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {
            }
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                GuiNewChat chat = mc.ingameGUI.getChatGUI();
                if (chat.chatLines != filteredChatLines) {
                    logger.error("SOME OTHER MOD DID THE SAME DIRTY HACK WE DID, THE `chatLines` FIELD WAS REPLACED WITH SOMETHING ELSE, THIS WILL BREAK THEIR THINGS");
                }
                if (chat.drawnChatLines != filteredDrawnChatLines) {
                    logger.error("SOME OTHER MOD DID THE SAME DIRTY HACK WE DID, THE `drawnChatLines` FIELD WAS REPLACED WITH SOMETHING ELSE, THIS WILL BREAK THEIR THINGS");
                }
                chat.chatLines = originalChatLines;
                chat.drawnChatLines = originalDrawnChatLines;

                logger.info("Reenabled adding new chat lines");
            });
        }, "Cleanse timer");
        timer.setDaemon(true);
        timer.start();

        logger.info("Started the timer to reenable adding new chat lines, {} ms", delay);
    }

    private static Set<String> loadKeys(String path) {
        InputStream is = Cleanse.class.getResourceAsStream(path);
        return is != null ?
                LanguageMap.parseLangFile(is).keySet() :
                emptySet();
    }

    private static void loadVanillaKeys() {
        vanillaKeys.addAll(loadKeys("/assets/minecraft/lang/en_us.lang"));
        vanillaKeys.addAll(loadKeys("/assets/realms/lang/en_us.lang"));
        vanillaKeys.addAll(loadKeys("/assets/forge/lang/en_US.lang"));
    }

    private static boolean isVanilla(ITextComponent text) {
        return text instanceof TextComponentTranslation && vanillaKeys.contains(((TextComponentTranslation) text).getKey());
    }

    // Yes, this mess below is allow vanilla messages
    //
    // Basically the `drawnChatLines.add` call receives only one line of a potentially multiline message at a time
    // with no connection to the original TextComponentTranslation by using which we could've
    // checked if the message was vanilla or not
    //
    // So we check that separately in the event handler, and then create our own split
    // and compare incoming lines with head of that split and pop it
    // (and allow the chat line to pass through) if the line matches

    @SubscribeEvent
    public static void on(ClientChatReceivedEvent e) {
        if (!isVanilla(e.getMessage())) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiNewChat chat = mc.ingameGUI.getChatGUI();

        // copying vanilla behaviour from the GuiNewChat#setChatLine here
        allowedChatLines = GuiUtilRenderComponents.splitText(e.getMessage(),
                MathHelper.floor((float) chat.getChatWidth() / chat.getChatScale()),
                mc.fontRenderer,
                false,
                false);
    }

    private static final class TheGreatFilter extends ForwardingList<ChatLine> {

        private final List<ChatLine> list;
        private final boolean split;

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
            } else if (!allowedChatLines.isEmpty() && text.getFormattedText().equals(allowedChatLines.get(0).getFormattedText())) {
                allowedChatLines.remove(0);
                super.add(index, element);
            }
        }

        @Override
        protected List<ChatLine> delegate() {
            return list;
        }
    }
}
