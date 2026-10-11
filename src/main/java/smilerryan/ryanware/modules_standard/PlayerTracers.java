package smilerryan.ryanware.modules_standard;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import smilerryan.ryanware.RyanWare;

public class PlayerTracers extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Tracer color.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private final Setting<Boolean> ignore0Ping = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-0-ping")
        .description("Ignore players with 0ms ping.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreBotTag = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-bot-tag")
        .description("Ignore players containing [BOT].")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreNpcTag = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-npc-tag")
        .description("Ignore players containing [NPC].")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreCIT = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-cit")
        .description("Ignore CIT fake players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreInvisible = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-invisible")
        .description("Ignore invisible players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreSpectators = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-spectators")
        .description("Ignore spectators.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreNoTabEntry = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-no-tab-entry")
        .description("Ignore players missing from tab list.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreBadUUID = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-bad-uuid")
        .description("Ignore non UUID v4 players.")
        .defaultValue(false)
        .build()
    );

    public PlayerTracers() {
        super(
            RyanWare.CATEGORY_STANDARD,
            RyanWare.modulePrefix_standard + "Player-Tracers",
            "Draws stable tracers to real players."
        );
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        float t = event.tickDelta;

        // Interpolated eye position
        double px = lerp(mc.player.lastX, mc.player.getX(), t);
        double py = lerp(mc.player.lastY, mc.player.getY(), t)
                + mc.player.getEyeHeight(mc.player.getPose());
        double pz = lerp(mc.player.lastZ, mc.player.getZ(), t);

        // Small forward offset keeps start point inside frustum
        Vec3d forward = mc.player.getRotationVec(t).multiply(0.25);
        double sx = px + forward.x;
        double sy = py + forward.y;
        double sz = pz + forward.z;

        for (PlayerEntity player : mc.world.getPlayers()) {

            if (player == mc.player)
                continue;

            PlayerListEntry entry = mc.getNetworkHandler()
                .getPlayerListEntry(player.getUuid());

            if (ignoreNoTabEntry.get() && entry == null)
                continue;

            if (ignore0Ping.get() && entry != null && entry.getLatency() <= 0)
                continue;

            if (ignoreInvisible.get() && player.isInvisible())
                continue;

            if (ignoreSpectators.get() && player.isSpectator())
                continue;

            if (ignoreBadUUID.get() && player.getUuid().version() != 4)
                continue;


            String name = stripFormatting(player.getName().getString());
            String display = stripFormatting(player.getDisplayName().getString());


            if (ignoreBotTag.get()
                    && (name.contains("[BOT]")
                    || display.contains("[BOT]")))
                continue;


            if (ignoreNpcTag.get()
                    && (name.contains("[NPC]")
                    || display.contains("[NPC]")))
                continue;


            if (ignoreCIT.get()
                    && (name.startsWith("CIT-")
                    || display.startsWith("CIT-")))
                continue;


            double tx = lerp(player.lastX, player.getX(), t);
            double ty = lerp(player.lastY, player.getY(), t)
                    + player.getEyeHeight(player.getPose());
            double tz = lerp(player.lastZ, player.getZ(), t);

            event.renderer.line(sx, sy, sz, tx, ty, tz, tracerColor.get());
        }
    }

    private double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }

    private String stripFormatting(String s) {
        if (s == null || s.isEmpty()) return "";

        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            out.append(c);
        }
        return out.toString().trim();
    }
}
