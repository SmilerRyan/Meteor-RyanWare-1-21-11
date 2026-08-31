package smilerryan.ryanware.modules_standard;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;  // <-- ADD THIS IMPORT
import smilerryan.ryanware.RyanWare;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class Flight extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Elytra Flight");
    private final SettingGroup sgBoat = settings.createGroup("Boat Flight");

    private final Setting<Boolean> speedEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("speed-enabled")
        .description("Allows you to go a custom fly speed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("How fast to fly.")
        .defaultValue(8.0)
        .min(0.1)
        .max(100.0)
        .sliderMax(10.0)
        .visible(speedEnabled::get)
        .build()
    );

    private final Setting<Boolean> instantTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-takeoff")
        .description("Take off immediately when jumping, without requiring double jump.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableEveryTick = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-flight-every-tick")
        .description("Bypasses some anti-fly by re-enabling flight every tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bypassAntiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("bypass-anti-kick")
        .description("Bypasses vanilla anti-kick while flying.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> antiSlowdown = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-slowdown")
        .description("Periodically forces zero movement to bypass slowdown effects.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> slowdownInterval = sgGeneral.add(new IntSetting.Builder()
        .name("anti-slowdown-interval")
        .description("Ticks between anti-slowdown bursts.")
        .defaultValue(20)
        .min(1)
        .max(200)
        .sliderMax(100)
        .visible(antiSlowdown::get)
        .build()
    );

    private final Setting<Integer> slowdownDuration = sgGeneral.add(new IntSetting.Builder()
        .name("anti-slowdown-duration")
        .description("How many ticks movement is forced to zero.")
        .defaultValue(2)
        .min(1)
        .max(20)
        .sliderMax(10)
        .visible(antiSlowdown::get)
        .build()
    );

    private final Setting<Double> slowdownSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("anti-slowdown-speed")
        .description("How fast to fly during the anti-slowdown time")
        .defaultValue(1.0)
        .min(0.0)
        .max(100.0)
        .sliderMax(10.0)
        .visible(antiSlowdown::get)
        .build()
    );

    private final Setting<Boolean> elytraBlockSlowdown = sgSafety.add(new BoolSetting.Builder()
        .name("slow-down-enabled")
        .description("Slows Elytra movement when very close to blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> elytraSlowdownSpeed = sgSafety.add(new DoubleSetting.Builder()
        .name("slow-down-speed")
        .description("Maximum Elytra speed when close to a block.")
        .defaultValue(1.0)
        .min(0.05)
        .max(10.0)
        .sliderMax(5.0)
        .visible(elytraBlockSlowdown::get)
        .build()
    );

    private final Setting<Boolean> boatFly = sgBoat.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Allows boats to fly while you are riding them.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> boatFlySpeed = sgBoat.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Boat Fly movement speed.")
        .defaultValue(1.5)
        .min(0.05)
        .max(10.0)
        .sliderMax(5.0)
        .visible(boatFly::get)
        .build()
    );

    private final Setting<Boolean> boatLookVertical = sgBoat.add(new BoolSetting.Builder()
        .name("one-handed-mode")
        .description("Looking almost directly up or down with controls up/down for you.")
        .defaultValue(false)
        .build()
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private boolean wasJumping = false;
    private boolean isFlying = false;

    // Vanilla-like double tap timer (~7 ticks window)
    private int jumpTimer = 0;
    private static final int DOUBLE_TAP_WINDOW = 7;

    // Anti-slowdown state
    private int slowdownTick = 0;
    private int slowdownActive = 0;

    public Flight() {
        super(RyanWare.CATEGORY_STANDARD, RyanWare.modulePrefix_standard + "Flight", "Toggle flying with double jump, like creative mode.");
    }

    @Override
    public void onActivate() {
        wasJumping = false;
        isFlying = false;
        jumpTimer = 0;

        slowdownTick = 0;
        slowdownActive = 0;

        if (mc.player != null) {
            mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().allowFlying = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        boolean isJumping = mc.options.jumpKey.isPressed();

        // Instant takeoff mode
        if (instantTakeoff.get()) {
            if (!isFlying && isJumping && !mc.player.isOnGround()) {
                isFlying = true;
                mc.player.getAbilities().allowFlying = true;
                mc.player.getAbilities().flying = true;
            }
        }

        // Double-tap detection (vanilla-like)
        if (!wasJumping && isJumping) {
            if (jumpTimer > 0) {
                isFlying = !isFlying;
                mc.player.getAbilities().allowFlying = !isFlying;
                mc.player.getAbilities().flying = !isFlying;
                jumpTimer = 0;
            } else {
                jumpTimer = DOUBLE_TAP_WINDOW;
            }
        }

        // Countdown timer
        if (jumpTimer > 0) jumpTimer--;

        // Reset on ground (vanilla behavior feel)
        if (mc.player.isOnGround() && isFlying && instantTakeoff.get()) {
            isFlying = false;
            mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }

        // Apply allowFlying every tick
        if (enableEveryTick.get() && isFlying) {
            mc.player.getAbilities().allowFlying = true;
            mc.player.getAbilities().flying = true;
        }

        // Apply flight speed when flying
        if (isFlying && speedEnabled.get()) {
            mc.player.getAbilities().setFlySpeed((float) (speed.get() * 0.05f));
        }

        /*
         * Elytra block safety.
         */
        if (elytraBlockSlowdown.get()
            && mc.player.isGliding()
            && mc.world != null) {

            if (isNearSolidBlock()) {
                double maxSpeed = elytraSlowdownSpeed.get();

                var velocity = mc.player.getVelocity();

                double horizontalSpeed = Math.sqrt(
                    velocity.x * velocity.x +
                    velocity.z * velocity.z
                );

                if (horizontalSpeed > maxSpeed && horizontalSpeed > 0.0) {
                    double multiplier = maxSpeed / horizontalSpeed;

                    mc.player.setVelocity(
                        velocity.x * multiplier,
                        velocity.y,
                        velocity.z * multiplier
                    );
                }
            }
        }

        /*
         * Boat Fly.
         */
        if (boatFly.get()) {
            handleBoatFly();
        }

        // Bypass Vanilla Anti-kick
        if (isFlying && !mc.player.isOnGround() && bypassAntiKick.get() && mc.player.age % 10 < 2) {
            mc.player.setVelocity(
                mc.player.getVelocity().x,
                mc.player.getVelocity().y + (mc.player.age % 10 == 0 ? -0.04 : 0.04),
                mc.player.getVelocity().z
            );
        }

        // Anti-slowdown
        if (antiSlowdown.get() && isFlying) {
            if (slowdownActive > 0) {
                // mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
                mc.player.getAbilities().setFlySpeed((float) (slowdownSpeed.get() * 0.05f));
                slowdownActive--;
            } else {
                slowdownTick++;

                if (slowdownTick >= slowdownInterval.get()) {
                    slowdownTick = 0;
                    slowdownActive = slowdownDuration.get();
                }
            }
        }

        wasJumping = isJumping;
    }

    private boolean isNearSolidBlock() {
        var pos = mc.player.getBlockPos();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    BlockPos checkPos = pos.add(dx, dy, dz);
                    if (mc.world.getBlockState(checkPos).isSolidBlock(mc.world, checkPos)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void handleBoatFly() {
        if (mc.player.getVehicle() == null) return;

        var vehicle = mc.player.getVehicle();

        if (!vehicle.getType().toString().toLowerCase().contains("boat")) {
            return;
        }

        double speedValue = boatFlySpeed.get();

        // Instantly rotate the boat to match the player's yaw.
        vehicle.setYaw(mc.player.getYaw());

        // W/S controls forward and backward movement.
        double forward = 0.0;

        if (mc.options.forwardKey.isPressed()) {
            forward += 1.0;
        }

        if (mc.options.backKey.isPressed()) {
            forward -= 1.0;
        }

        // Use player's current yaw.
        double yaw = Math.toRadians(mc.player.getYaw());

        double moveX = -Math.sin(yaw) * forward;
        double moveZ = Math.cos(yaw) * forward;

        /*
         * Vertical movement:
         *
         * Space = full speed up
         * Control = full speed down
         *
         * Look Vertical:
         * Slightly up = slowly rises
         * Farther up = rises faster
         * Directly up = full speed up
         *
         * Slightly down = slowly descends
         * Farther down = descends faster
         * Directly down = full speed down
         */
        double vertical = 0.0;

        if (mc.options.jumpKey.isPressed()) {
            vertical = 1.0;
        } else if (isControlDown()) {
            vertical = -1.0;
        } else if (boatLookVertical.get()) {
            float pitch = mc.player.getPitch();

            /*
             * Minecraft pitch:
             * -90 = directly up
             *   0 = straight ahead
             * +90 = directly down
             *
             * Ignore the first 5 degrees around horizontal
             * so tiny mouse movements don't cause vertical movement.
             */
            double deadzone = 10.0;

            if (pitch < -deadzone) {
                // Convert -5..-90 into 0..1.
                vertical = (pitch + deadzone) / (-90.0 + deadzone);

            } else if (pitch > deadzone) {
                // Convert 5..90 into 0..-1.
                vertical = -(pitch - deadzone) / (90.0 - deadzone);
            }
        }

        /*
         * Explicitly control all velocity.
         * Y is proportional to looking angle when
         * Look Vertical is enabled.
         */
        vehicle.setVelocity(
            moveX * speedValue,
            vertical * speedValue,
            moveZ * speedValue
        );
    }

    private boolean isControlDown() {
        try {
            /*
             * Find the long GLFW window handle without relying
             * on version-specific Window accessor methods.
             */
            Object window = mc.getWindow();

            for (Field field : window.getClass().getDeclaredFields()) {
                if (field.getType() == long.class) {
                    field.setAccessible(true);

                    long value = field.getLong(window);

                    if (value != 0L) {
                        boolean left = GLFW.glfwGetKey(value, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS;
                        boolean right = GLFW.glfwGetKey(value, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

                        if (left || right) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }
}