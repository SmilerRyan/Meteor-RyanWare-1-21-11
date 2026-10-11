package smilerryan.ryanware.modules_standard;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import smilerryan.ryanware.RyanWare;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PlayerList extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDoubleHalf = settings.createGroup("Double / Half Ping");
    private final SettingGroup sgSimilarPing = settings.createGroup("Similar Ping");
    private final SettingGroup sgOthers = settings.createGroup("Everyone else");

    public enum SortMode {
        Name,
        PingLowToHigh,
        PingHighToLow
    }

    public enum HighlightPriority {
        SimilarPingFirst,
        DoubleHalfFirst
    }

    // --- General Settings ---
    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("How to sort the players in the list.")
        .defaultValue(SortMode.Name)
        .build()
    );

    private final Setting<HighlightPriority> highlightPriority = sgGeneral.add(new EnumSetting.Builder<HighlightPriority>()
        .name("highlight-priority")
        .description("Which highlight takes priority when a player matches multiple rules.")
        .defaultValue(HighlightPriority.DoubleHalfFirst)
        .build()
    );

    private final Setting<Boolean> hideNormalPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-normal-players")
        .description("Only show players matching one of the enabled highlight rules.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> format = sgGeneral.add(new StringSetting.Builder()
        .name("format")
        .description("The layout of the line. Use {name} and {ping_pad} and {ping_raw} as placeholders.")
        .defaultValue("{name}")
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The scale factor of the text.")
        .defaultValue(0.67)
        .min(0.1)
        .max(10.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Integer> positionX = sgGeneral.add(new IntSetting.Builder()
        .name("position-x")
        .description("Horizontal screen position of the player list.")
        .defaultValue(10)
        .min(0)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Integer> positionY = sgGeneral.add(new IntSetting.Builder()
        .name("position-y")
        .description("Vertical screen position of the player list.")
        .defaultValue(20)
        .min(0)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Integer> lineSpacing = sgGeneral.add(new IntSetting.Builder()
        .name("line-spacing")
        .description("Extra vertical spacing between player rows.")
        .defaultValue(0)
        .min(0)
        .sliderMax(12)
        .build()
    );

    private final Setting<Boolean> background = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a configurable background behind the player list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color and opacity for the player list.")
        .defaultValue(new SettingColor(0, 0, 0, 160))
        .visible(background::get)
        .build()
    );
    
    // --- Double / Half Ping Settings ---
    private final Setting<Boolean> doubleHalfEnable = sgDoubleHalf.add(new BoolSetting.Builder()
        .name("enable")
        .description("Color players whose ping is roughly double or half of another player's ping.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> doubleHalfAmount = sgDoubleHalf.add(new IntSetting.Builder()
        .name("tolerance")
        .description("Allowed difference from an exact double/half relationship (0 is exact).")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<SettingColor> doubleHalfColor = sgDoubleHalf.add(new ColorSetting.Builder()
        .name("color")
        .description("The color for players with double/half pings of others.")
        .defaultValue(new SettingColor(255, 128, 0, 255)) // Default orange
        .build()
    );

    
    // --- Similar Ping Settings ---
    private final Setting<Boolean> similarPingEnable = sgSimilarPing.add(new BoolSetting.Builder()
        .name("enable")
        .description("Color players with similar pings.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> similarPingAmount = sgSimilarPing.add(new IntSetting.Builder()
        .name("amount")
        .description("Maximum ping difference to be considered similar.")
        .defaultValue(0)
        .min(0)
        .sliderMax(500)
        .build()
    );

    private final Setting<SettingColor> similarPingColor = sgSimilarPing.add(new ColorSetting.Builder()
        .name("color")
        .description("The color for players with similar pings.")
        .defaultValue(new SettingColor(255, 128, 0, 255))
        .build()
    );

    // --- Everyone else Settings ---
    private final Setting<SettingColor> textColor = sgOthers.add(new ColorSetting.Builder()
        .name("text-color")
        .description("The color for normal players.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );


    private List<PlayerListEntry> sortedPlayers;
    public PlayerList() {
        super(RyanWare.CATEGORY_STANDARD, RyanWare.modulePrefix_standard + "Player-List", "Custom player list, sortable by name, ping and detect similar or double/half pinging player patterns.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.getNetworkHandler() == null) return;
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        
        Comparator<PlayerListEntry> comparator;
        switch (sortMode.get()) {
            case Name:
                comparator = Comparator.comparing(entry -> entry.getProfile().name(), String.CASE_INSENSITIVE_ORDER);
                break;
            case PingHighToLow:
                comparator = Comparator.comparingInt(PlayerListEntry::getLatency).reversed();
                break;
            case PingLowToHigh:
            default:
                comparator = Comparator.comparingInt(PlayerListEntry::getLatency);
                break;
        }

        sortedPlayers = networkHandler.getPlayerList().stream()
            .sorted(comparator)
            .collect(Collectors.toList());
    }

    @EventHandler
    private void onRender(Render2DEvent event) {
        if (sortedPlayers == null || sortedPlayers.isEmpty()) return;
        
        double x = positionX.get();
        double y = positionY.get();
        
        double scaleValue = scale.get();
        
        // Calculate dynamic line height based on font metrics and scale
        int lineHeight = (int) (mc.textRenderer.fontHeight * scaleValue) + lineSpacing.get();
        
        // Find highest ping to determine layout padding dynamically
        int maxPing = 0;
        for (PlayerListEntry entry : sortedPlayers) {
            if (entry.getLatency() > maxPing) {
                maxPing = entry.getLatency();
            }
        }
        int maxPingLength = String.valueOf(maxPing).length();

        // Track players with similar pings
        Set<PlayerListEntry> similarPlayers = new HashSet<>();
        if (similarPingEnable.get()) {
            int amount = similarPingAmount.get();
            int size = sortedPlayers.size();
            for (int i = 0; i < size; i++) {
                PlayerListEntry p1 = sortedPlayers.get(i);
                for (int j = i + 1; j < size; j++) {
                    PlayerListEntry p2 = sortedPlayers.get(j);
                    if (Math.abs(p1.getLatency() - p2.getLatency()) <= amount) {
                        similarPlayers.add(p1);
                        similarPlayers.add(p2);
                    }
                }
            }
        }

        // Track players with double or half pings
        Set<PlayerListEntry> doubleHalfPlayers = new HashSet<>();
        if (doubleHalfEnable.get()) {
            int tolerance = doubleHalfAmount.get();
            int size = sortedPlayers.size();
            for (int i = 0; i < size; i++) {
                PlayerListEntry p1 = sortedPlayers.get(i);
                int lat1 = p1.getLatency();
                for (int j = i + 1; j < size; j++) {
                    PlayerListEntry p2 = sortedPlayers.get(j);
                    int lat2 = p2.getLatency();

                    // Avoid matches where both players have 0 ping
                    if (lat1 == 0 && lat2 == 0) continue;

                    // Checks: Is lat1 roughly double lat2? OR is lat2 roughly double lat1?
                    if (Math.abs(lat1 - 2 * lat2) <= tolerance || Math.abs(lat2 - 2 * lat1) <= tolerance) {
                        doubleHalfPlayers.add(p1);
                        doubleHalfPlayers.add(p2);
                    }
                }
            }
        }
        
        // Calculate dimensions for the background dynamically using formatted text   
        double maxWidth = 0;
        int visiblePlayers = 0;
        for (PlayerListEntry entry : sortedPlayers) {
            boolean matches = similarPlayers.contains(entry) || doubleHalfPlayers.contains(entry);
            if (hideNormalPlayers.get() && !matches) continue;
            String line = formatEntry(entry, maxPingLength);
            double width = mc.textRenderer.getWidth(line) * scaleValue;
            if (width > maxWidth) maxWidth = width;
            visiblePlayers++;
        }
        if (visiblePlayers == 0) return;

        // Draw background
        double padding = 5;
        double backgroundWidth = maxWidth + (padding * 2);
        double backgroundHeight = (visiblePlayers * lineHeight) + (padding * 2);

        if (background.get()) {
            event.drawContext.fill(
                (int)(x - padding),
                (int)(y - padding),
                (int)(x - padding + backgroundWidth),
                (int)(y - padding + backgroundHeight),
                backgroundColor.get().getPacked()
            );
        }
        
        // Draw each player entry
        for (PlayerListEntry entry : sortedPlayers) {
            boolean matches = similarPlayers.contains(entry) || doubleHalfPlayers.contains(entry);
            if (hideNormalPlayers.get() && !matches) continue;
            
            String line = formatEntry(entry, maxPingLength);
            
            int colorToUse = textColor.get().getPacked();

            switch (highlightPriority.get()) {
                case SimilarPingFirst:
                    if (similarPlayers.contains(entry)) {
                        colorToUse = similarPingColor.get().getPacked();
                    } else if (doubleHalfPlayers.contains(entry)) {
                        colorToUse = doubleHalfColor.get().getPacked();
                    }
                    break;

                case DoubleHalfFirst:
                default:
                    if (doubleHalfPlayers.contains(entry)) {
                        colorToUse = doubleHalfColor.get().getPacked();
                    } else if (similarPlayers.contains(entry)) {
                        colorToUse = similarPingColor.get().getPacked();
                    }
                    break;
            }
            
            // Push matrices using JOML's updated 2D Matrix API
            event.drawContext.getMatrices().pushMatrix();
            
            // Use 2D scaling
            event.drawContext.getMatrices().scale((float) scaleValue, (float) scaleValue);
            
            // Draw text
            event.drawContext.drawText(
                mc.textRenderer,
                line,
                (int) (x / scaleValue),
                (int) (y / scaleValue),
                colorToUse,
                true
            );
            
            // Pop the matrix
            event.drawContext.getMatrices().popMatrix();
            
            y += lineHeight;
        }
    }

    private String formatEntry(PlayerListEntry entry, int maxPingLength) {
        String name = entry.getProfile().name();
        int ping = entry.getLatency();
        
        // Always left pads with zeros up to maxPingLength (e.g. "005" or "120")
        String paddedPing = String.format("%0" + maxPingLength + "d", ping);
        
        // Replace custom formatting tags dynamically
        return format.get()
            .replace("{name}", name)
            .replace("{ping_pad}", paddedPing)
            .replace("{ping_raw}", Integer.toString(ping));
    }
}
