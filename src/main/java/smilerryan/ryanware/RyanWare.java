package smilerryan.ryanware;

import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.commands.Commands;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smilerryan.ryanware.modules.*;
import smilerryan.ryanware.modules.crystal_anchor_etc.*;
import smilerryan.ryanware.modules_standard.*;
import smilerryan.ryanware.modules_standard.automation.*;
import smilerryan.ryanware.modules_standard.chat.*;
import smilerryan.ryanware.modules_standard.chat.edits.*;
import smilerryan.ryanware.modules_standard.chat.ollama.*;
import smilerryan.ryanware.commands.*;
import smilerryan.ryanware.commands.chat.*;
import meteordevelopment.orbit.EventBus;
import meteordevelopment.meteorclient.MeteorClient;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class RyanWare extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("RyanWare");

    private static final String MOD_ID = resolveModId();
    public static boolean hideTitleCredits = false;

    public static String addonName = "RyanWare";

    public static String catName_extras = "";
    public static String catName_standard = "";

    public static String commandPrefix = "";
    public static String modulePrefix_extras = "";
    public static String modulePrefix_standard = "";

    public static Item iconItem_extras = Items.GLOWSTONE;
    public static Item iconItem_standard = Items.GLOWSTONE;
    
    public static Category CATEGORY_EXTRAS;
    public static Category CATEGORY_STANDARD;

    private static String resolveModId() {
        String classResource = RyanWare.class.getName().replace('.', '/') + ".class";
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.findPath(classResource).isPresent()) {
                return mod.getMetadata().getId();
            }
        }
        throw new RuntimeException("Could not determine mod ID for " + RyanWare.class.getSimpleName());
    }

    static {
        try {
            FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container -> {
                try {
                    LoaderModMetadata meta = (LoaderModMetadata) container.getMetadata();
                    if (meta.getCustomValue("ryanware:addon-name") != null) {
                        addonName = meta.getCustomValue("ryanware:addon-name").getAsString();
                    }
                    if (meta.getCustomValue("ryanware:module-prefix-extras") != null) {
                        modulePrefix_extras = meta.getCustomValue("ryanware:module-prefix-extras").getAsString();
                    }
                    if (meta.getCustomValue("ryanware:command-prefix") != null) {
                        commandPrefix = meta.getCustomValue("ryanware:command-prefix").getAsString();
                    }
                    if (meta.getCustomValue("ryanware:module-prefix-standard") != null) {
                        modulePrefix_standard = meta.getCustomValue("ryanware:module-prefix-standard").getAsString();
                    }
                    if (meta.getCustomValue("ryanware:cat-name-extras") != null) {
                        catName_extras = meta.getCustomValue("ryanware:cat-name-extras").getAsString();
                    }
                    if (meta.getCustomValue("ryanware:cat-name-standard") != null) {
                        catName_standard = meta.getCustomValue("ryanware:cat-name-standard").getAsString();
                    }
                    if (meta.getCustomValue("ryanware:icon-extras") != null) {
                        String iconName = meta.getCustomValue("ryanware:icon-extras").getAsString().toLowerCase(Locale.ROOT);
                        Identifier id = Identifier.of("minecraft", iconName);
                        iconItem_extras = Registries.ITEM.get(id);
                        if (iconItem_extras == null) iconItem_extras = Items.SPONGE;
                    }
                    if (meta.getCustomValue("ryanware:icon-standard") != null) {
                        String iconName = meta.getCustomValue("ryanware:icon-standard").getAsString().toLowerCase(Locale.ROOT);
                        Identifier id = Identifier.of("minecraft", iconName);
                        iconItem_standard = Registries.ITEM.get(id);
                        if (iconItem_standard == null) iconItem_standard = Items.SPONGE;
                    }
                } catch (Exception e) {
                    LOG.error("Failed to read mod metadata or override values.", e);
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to access mod container", e);
        }

        CATEGORY_EXTRAS = new Category(catName_extras, iconItem_extras.getDefaultStack());
        CATEGORY_STANDARD = new Category(catName_standard, iconItem_standard.getDefaultStack());
    }

    @Override
    public void onInitialize() {
        LOG.info("Initializing {} Addon.", addonName);

        if (MOD_ID.equals("meteor-client")) {
            hideTitleCredits = true;
        }

        // Commands
        Commands.add(new Command_CopyInv());
        Commands.add(new Command_GMC());
        Commands.add(new Command_Note());

        // Commands - Chat
        Commands.add(new Command_addText());
        Commands.add(new Command_RandomNumber());
        Commands.add(new Command_TAMsg());

        // Commands - Chat - autoLogin
        var command_autoLogin = new command_autoLogin();
        Commands.add(command_autoLogin);
        MeteorClient.EVENT_BUS.subscribe(command_autoLogin);      
        
        // Commands - Chat - reply
        var command_reply = new command_reply();
        Commands.add(command_reply);
        MeteorClient.EVENT_BUS.subscribe(command_reply);

        // Modules
        Modules.get().add(new _example());
        Modules.get().add(new AirPunchSwap());
        Modules.get().add(new AntiBlockBreak());
        Modules.get().add(new AntiBlockPlace());
        Modules.get().add(new AntiClimb());
        Modules.get().add(new AntiTouch());
        Modules.get().add(new AternosOnliner());
        Modules.get().add(new AtSomeone());
        Modules.get().add(new Aura());
        Modules.get().add(new AutoChatScreenshotter());
        Modules.get().add(new AutoChestMover());
        Modules.get().add(new AutoClickPlayers());
        Modules.get().add(new AutoFollowItems());
        Modules.get().add(new AutoFollowPlayers());
        Modules.get().add(new AutoGroom());
        Modules.get().add(new AutoHighwayBuilder());
        Modules.get().add(new AutoItemFrameDupe());
        Modules.get().add(new AutoKillAnnouncer());
        Modules.get().add(new AutoMineNearbyBlocks());
        Modules.get().add(new AutoResponder());
        Modules.get().add(new AutoRingBells());
        Modules.get().add(new AutoStealDupes());
        Modules.get().add(new AutoTotem());
        Modules.get().add(new AutoWalkHome());
        Modules.get().add(new BeehiveCoordLogger());
        Modules.get().add(new BritishChat());
        Modules.get().add(new ChatEncryption());
        Modules.get().add(new ChatLogger());
        Modules.get().add(new ChatReplacer());
        Modules.get().add(new ChatSpam());        
        Modules.get().add(new ChatSpeaker());        
        Modules.get().add(new ChunkBlockESP());
        Modules.get().add(new ClearLag());
        Modules.get().add(new Clicker());
        Modules.get().add(new ClickTP());
        Modules.get().add(new CommandAura());
        Modules.get().add(new CommandRedirector());
        Modules.get().add(new CompletionCrash());
        Modules.get().add(new CringeDetector());
        Modules.get().add(new CustomCrosshair());
        Modules.get().add(new DeathCommands());
        Modules.get().add(new ElytraCreativeFly());
        Modules.get().add(new ElytraFakeRockets());
        Modules.get().add(new ElytraFly());
        Modules.get().add(new ErmActuallyCorrector());        
        Modules.get().add(new EventAnnouncer());
        Modules.get().add(new Excavator());
        Modules.get().add(new ExtraScreenshot());
        Modules.get().add(new FocusCommands());
        Modules.get().add(new ForceColoredChat());
        Modules.get().add(new ForceYLevel());
        Modules.get().add(new FullScreenCapture());
        Modules.get().add(new HideItemFrameItems());
        Modules.get().add(new HideItemFrameMaps());
        Modules.get().add(new LizardMode());
        Modules.get().add(new LookDownDropper());
        Modules.get().add(new MaxMaceKill());
        Modules.get().add(new ModuleDisabler());
        Modules.get().add(new ModuleMenu());
        Modules.get().add(new NewChunks());
        Modules.get().add(new NoBlockDamage());
        Modules.get().add(new NoItemUsageCooldown());
        Modules.get().add(new PacketDelayer());
        Modules.get().add(new PacketLimiter());
        Modules.get().add(new PacketRecorderReplayer());
        Modules.get().add(new PlayerAlerter());
        Modules.get().add(new PlayerCoordsTracker());
        Modules.get().add(new PlayerHider());
        Modules.get().add(new PlayerList());
        Modules.get().add(new PlayerShapeESP());
        Modules.get().add(new PublicChatTags());
        Modules.get().add(new Radio());
        Modules.get().add(new RedirectMsgCommands());
        Modules.get().add(new RedirectPublicChat());
        Modules.get().add(new RemoteViewProxyServer());
        Modules.get().add(new RemoteViewWebServer());
        Modules.get().add(new RemoveNewBlocks());
        Modules.get().add(new Scaffold());
        Modules.get().add(new ScreenRecorder());
        Modules.get().add(new SoundBlocker());
        Modules.get().add(new SpeechToText());
        Modules.get().add(new TabCompletePrivacy());
        Modules.get().add(new TntCleaner());
        Modules.get().add(new TotemAutoLeave());
        Modules.get().add(new TotemBypass());
        Modules.get().add(new UserLookups());
        Modules.get().add(new WorldDownloader());

        // Modules - Crystal, Anchor, etc
        Modules.get().add(new AutoAnchor());
        Modules.get().add(new CrystalAura());
        Modules.get().add(new CrystalAura2());
        Modules.get().add(new CrystalAura3());
        Modules.get().add(new CrystalKillAura());

        // Modules Standard
        Modules.get().add(new BungeeSpoofer());
        Modules.get().add(new CoordNotifier());
        Modules.get().add(new CustomTabText());
        Modules.get().add(new DeathCoords());
        Modules.get().add(new DurabilityBlocker());
        Modules.get().add(new f3_number_hider());
        Modules.get().add(new Flight());
        Modules.get().add(new FullBright());
        Modules.get().add(new lessCpuWhenIdle());
        Modules.get().add(new MorePing());
        Modules.get().add(new NoAttackDamage());
        Modules.get().add(new PlayerTracers());
        Modules.get().add(new Settings());
        Modules.get().add(new TabLogger());

        // Modules Standard - Chat 
        Modules.get().add(new AutoRunFileOnChat());
        Modules.get().add(new ChatPlinger());
        Modules.get().add(new ChatRepeater());
        Modules.get().add(new DiscordChatLogger());
        Modules.get().add(new TextOnly_AI_Chat());
        Modules.get().add(new TextOnly_AI_Chat_2());
        
        // Modules Standard - Automation
        Modules.get().add(new AutoOBSReplays());
        Modules.get().add(new AutoRespawn());
        Modules.get().add(new AutoWalkForwards());
        Modules.get().add(new ForceOpenTab());
        Modules.get().add(new SkinBlinker());

        // Modules Standard - Chat - Edits
        Modules.get().add(new AntiFancyChat());
        Modules.get().add(new ChatIgnorer());
        Modules.get().add(new ChatTranslator());

        // Modules Standard - Chat - Ollama
        Modules.get().add(new OllamaAnnoyer());
        Modules.get().add(new OllamaChat());
        Modules.get().add(new OllamaSuggestions());
        Modules.get().add(new OllamaTranslator());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY_EXTRAS);
        Modules.registerCategory(CATEGORY_STANDARD);
    }

    @Override
    public String getWebsite() {
        return "https://github.com/SmilerRyan/meteor-ryanware";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("SmilerRyan", "meteor-ryanware");
    }

    @Override
    public String getCommit() {
        return FabricLoader
            .getInstance()
            .getModContainer(MOD_ID)
            .get().getMetadata()
            .getCustomValue("github:sha")
            .getAsString().trim();
    }

    public String getPackage() {
        return "smilerryan.ryanware";
    }
}