package smilerryan.ryanware.modules_standard;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import smilerryan.ryanware.RyanWare;

public class NearbyPlayerIndicators extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Mode {
        Corner,
        CenterExact
    }

    private final Setting<Mode> indicatorMode = sgGeneral.add(
        new EnumSetting.Builder<Mode>()
            .name("mode")
            .description("Indicator placement mode.")
            .defaultValue(Mode.Corner)
            .build()
    );

    private final Setting<SettingColor> indicatorColor = sgGeneral.add(
        new ColorSetting.Builder()
            .name("color")
            .description("Indicator arrow color.")
            .defaultValue(new SettingColor(255, 0, 0, 128))
            .build()
    );

    private final Setting<Integer> arrowSize = sgGeneral.add(
        new IntSetting.Builder()
            .name("arrow-size")
            .description("Size of each arrow.")
            .defaultValue(48)
            .min(1)
            .sliderMax(48)
            .build()
    );

    private final Setting<Integer> arrowThickness = sgGeneral.add(
        new IntSetting.Builder()
            .name("arrow-thickness")
            .description("Thickness of the arrow lines.")
            .defaultValue(3)
            .min(1)
            .sliderMax(10)
            .build()
    );

    private final Setting<Integer> spacing = sgGeneral.add(
        new IntSetting.Builder()
            .name("spacing")
            .description("Spacing between arrows.")
            .defaultValue(72)
            .min(1)
            .sliderMax(72)
            .build()
    );

    private final Setting<Integer> cornerMargin = sgGeneral.add(
        new IntSetting.Builder()
            .name("edge-margin")
            .description("Distance from the screen edges.")
            .defaultValue(128)
            .min(1)
            .sliderMax(128)
            .build()
    );

    public NearbyPlayerIndicators() {
        super(
            RyanWare.CATEGORY_STANDARD,
            RyanWare.modulePrefix_standard + "Nearby-Player-Indicators",
            "Shows arrows toward players outside your view."
        );
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null || mc.options == null) return;

        int width = mc.getWindow().getScaledWidth();
        int height = mc.getWindow().getScaledHeight();

        float tickDelta = event.tickDelta;

        double px = MathHelper.lerp(tickDelta, mc.player.lastX, mc.player.getX());
        double py = MathHelper.lerp(tickDelta, mc.player.lastY, mc.player.getY())
            + mc.player.getEyeHeight(mc.player.getPose());
        double pz = MathHelper.lerp(tickDelta, mc.player.lastZ, mc.player.getZ());

        Vec3d eye = new Vec3d(px, py, pz);

        float yaw = mc.player.getYaw(tickDelta);
        float pitch = mc.player.getPitch(tickDelta);

        double fov = Math.toRadians(mc.options.getFov().getValue());
        double aspect = (double) width / Math.max(1, height);
        double verticalFov = 2.0 * Math.atan(Math.tan(fov / 2.0) / aspect);

        double halfHorizontalFov = fov / 2.0;
        double halfVerticalFov = verticalFov / 2.0;

        // Positions: top-left, top, top-right, left,
        // right, bottom-left, bottom, bottom-right.
        int[] counts = new int[8];

        for (PlayerEntity target : mc.world.getPlayers()) {
            if (target == mc.player) continue;
            if (target.isSpectator() || target.isInvisible()) continue;

            double tx = MathHelper.lerp(tickDelta, target.lastX, target.getX());
            double ty = MathHelper.lerp(tickDelta, target.lastY, target.getY())
                + target.getEyeHeight(target.getPose());
            double tz = MathHelper.lerp(tickDelta, target.lastZ, target.getZ());

            Vec3d delta = new Vec3d(tx - eye.x, ty - eye.y, tz - eye.z);

            double horizontalDistance = Math.sqrt(
                delta.x * delta.x + delta.z * delta.z
            );

            double targetYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
            double relativeYaw = wrapDegrees(targetYaw - yaw);

            double targetPitch = -Math.toDegrees(
                Math.atan2(delta.y, Math.max(0.0001, horizontalDistance))
            );
            double relativePitch = targetPitch - pitch;

            // Hide the indicator while the player is within the view angles.
            if (Math.abs(Math.toRadians(relativeYaw)) <= halfHorizontalFov
                && Math.abs(Math.toRadians(relativePitch)) <= halfVerticalFov) {
                continue;
            }

            double yawRadians = Math.toRadians(relativeYaw);
            double pitchRadians = Math.toRadians(relativePitch);

            // Project the target direction onto a virtual screen plane.
            double dirX = Math.sin(yawRadians);
            double dirY = Math.tan(pitchRadians);

            float margin = cornerMargin.get();
            float size = arrowSize.get();
            int thickness = arrowThickness.get();

            if (indicatorMode.get() == Mode.CenterExact) {
                float cx = width / 2.0f;
                float cy = height / 2.0f;

                double angle = Math.atan2(dirY, dirX);

                float halfW = (width / 2.0f) - margin;
                float halfH = (height / 2.0f) - margin;

                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);

                float scale = Math.min(
                    Math.abs(cos) > 0.0001 ? halfW / Math.abs(cos) : Float.MAX_VALUE,
                    Math.abs(sin) > 0.0001 ? halfH / Math.abs(sin) : Float.MAX_VALUE
                );

                float x = cx + cos * scale;
                float y = cy + sin * scale;

                drawArrow(event, x, y, size, cos, sin, thickness);
            } else {
                // Corner mode logic
                double max = Math.max(Math.abs(dirX), Math.abs(dirY));
                if (max < 0.0001) continue;

                double nx = dirX / max;
                double ny = dirY / max;

                int position;
                if (ny < -0.65) {
                    position = nx < -0.35 ? 0 : nx > 0.35 ? 2 : 1;
                } else if (ny > 0.65) {
                    position = nx < -0.35 ? 5 : nx > 0.35 ? 7 : 6;
                } else {
                    position = nx < 0 ? 3 : 4;
                }

                int index = counts[position]++;
                float gap = spacing.get();

                float x;
                float y;

                switch (position) {
                    case 0: // Top-left
                        x = margin + size / 2.0f + index * gap;
                        y = margin + size / 2.0f;
                        break;
                    case 1: // Top
                        x = width / 2.0f + index * gap;
                        y = margin + size / 2.0f;
                        break;
                    case 2: // Top-right
                        x = width - margin - size / 2.0f - index * gap;
                        y = margin + size / 2.0f;
                        break;
                    case 3: // Left
                        x = margin + size / 2.0f;
                        y = height / 2.0f + index * gap;
                        break;
                    case 4: // Right
                        x = width - margin - size / 2.0f;
                        y = height / 2.0f + index * gap;
                        break;
                    case 5: // Bottom-left
                        x = margin + size / 2.0f + index * gap;
                        y = height - margin - size / 2.0f;
                        break;
                    case 6: // Bottom
                        x = width / 2.0f + index * gap;
                        y = height - margin - size / 2.0f;
                        break;
                    default: // Bottom-right
                        x = width - margin - size / 2.0f - index * gap;
                        y = height - margin - size / 2.0f;
                        break;
                }

                x = MathHelper.clamp(x, margin + size / 2.0f, width - margin - size / 2.0f);
                y = MathHelper.clamp(y, margin + size / 2.0f, height - margin - size / 2.0f);

                boolean left = position == 0 || position == 3 || position == 5;
                boolean right = position == 2 || position == 4 || position == 7;
                boolean up = position <= 2;
                boolean down = position >= 5;

                float dx = left ? -1 : right ? 1 : 0;
                float dy = up ? -1 : down ? 1 : 0;

                drawArrow(event, x, y, size, dx, dy, thickness);
            }
        }
    }

    private void drawArrow(
        Render2DEvent event,
        float x,
        float y,
        float size,
        float dx,
        float dy,
        int thickness
    ) {
        if (dx == 0 && dy == 0) dy = -1;

        // Arrow direction and perpendicular direction.
        double angle = Math.atan2(dy, dx);
        double length = size / 2.0;
        double headLength = size * 0.4;
        double headWidth = size * 0.32;

        double tipX = x + Math.cos(angle) * length;
        double tipY = y + Math.sin(angle) * length;

        double baseX = x - Math.cos(angle) * length;
        double baseY = y - Math.sin(angle) * length;

        double perpX = -Math.sin(angle);
        double perpY = Math.cos(angle);

        int color = indicatorColor.get().getPacked();

        // Draw the arrow shaft as small rectangles along its direction.
        double shaftEndX = tipX - Math.cos(angle) * headLength;
        double shaftEndY = tipY - Math.sin(angle) * headLength;

        double shaftLength = Math.hypot(
            shaftEndX - baseX,
            shaftEndY - baseY
        );

        int steps = Math.max(1, (int) Math.ceil(shaftLength));

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;

            int sx = (int) Math.round(
                baseX + (shaftEndX - baseX) * t
            );
            int sy = (int) Math.round(
                baseY + (shaftEndY - baseY) * t
            );

            drawBlock(event, sx, sy, thickness, color);
        }

        // Draw the two arrowhead sides.
        double headBaseX = tipX - Math.cos(angle) * headLength;
        double headBaseY = tipY - Math.sin(angle) * headLength;

        double leftX = headBaseX + perpX * headWidth;
        double leftY = headBaseY + perpY * headWidth;

        double rightX = headBaseX - perpX * headWidth;
        double rightY = headBaseY - perpY * headWidth;

        drawSegment(event, tipX, tipY, leftX, leftY, thickness, color);
        drawSegment(event, tipX, tipY, rightX, rightY, thickness, color);
    }

    private void drawSegment(
        Render2DEvent event,
        double x1,
        double y1,
        double x2,
        double y2,
        int thickness,
        int color
    ) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        int steps = Math.max(1, (int) Math.ceil(length));

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;

            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);

            drawBlock(event, x, y, thickness, color);
        }
    }

    private void drawBlock(Render2DEvent event, int x, int y, int thickness, int color) {
        int half = thickness / 2;
        event.drawContext.fill(
            x - half,
            y - half,
            x + half + 1,
            y + half + 1,
            color
        );
    }

    private double wrapDegrees(double degrees) {
        degrees %= 360.0;
        if (degrees >= 180.0) degrees -= 360.0;
        if (degrees < -180.0) degrees += 360.0;
        return degrees;
    }
}