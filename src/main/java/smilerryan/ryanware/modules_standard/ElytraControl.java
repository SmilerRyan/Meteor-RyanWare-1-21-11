package smilerryan.ryanware.modules_standard;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;
import smilerryan.ryanware.RyanWare;

import java.util.List;

public class ElytraControl extends Module {

    private static final int TICKS_PER_SECOND = 20;
    private static final int SETTLE_TICKS = 4;
    private static final int MAX_RESTORE_ATTEMPTS = 3;
    private static final int CHEST_SLOT = 6;
    private static final int TAKEOFF_JUMP_TICKS = 8;

    private static final List<Item> CHESTPLATES = List.of(
        Items.NETHERITE_CHESTPLATE,
        Items.DIAMOND_CHESTPLATE,
        Items.IRON_CHESTPLATE,
        Items.CHAINMAIL_CHESTPLATE,
        Items.GOLDEN_CHESTPLATE,
        Items.LEATHER_CHESTPLATE
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHazey = settings.createGroup("Hazey Bypass");

    private final Setting<Double> fallMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-multiplier")
        .description("How much of the downward glide speed is kept while flying.")
        .defaultValue(0.01)
        .min(0.0)
        .sliderMax(1.0)
        .build()
    );

    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Speed of the key-driven horizontal flight.")
        .defaultValue(1.0)
        .min(0.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Speed of the jump and sneak driven climb and dive.")
        .defaultValue(1.0)
        .min(0.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> acceleration = sgGeneral.add(new BoolSetting.Builder()
        .name("acceleration")
        .description("Ramp the horizontal speed up while a movement key is held.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> accelerationStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("acceleration-step")
        .description("How much speed the ramp adds every tick.")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMax(5.0)
        .visible(acceleration::get)
        .build()
    );

    private final Setting<Double> accelerationStart = sgGeneral.add(new DoubleSetting.Builder()
        .name("acceleration-start")
        .description("Speed kept while no movement key is held.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderMax(5.0)
        .visible(acceleration::get)
        .build()
    );

    private final Setting<Boolean> autoTakeOff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Jump and start gliding after holding jump for a moment.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> stopInWater = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-in-water")
        .description("Do not drive the flight vector while you are in water.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hazeyBypass = sgHazey.add(new BoolSetting.Builder()
        .name("hazey-bypass")
        .description("Swaps the worn elytra for a chestplate for a short window on a fixed interval, then puts the elytra back on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> oneTick = sgHazey.add(new BoolSetting.Builder()
        .name("one-tick")
        .description("Keep the chestplate on for a single tick instead of the set time.")
        .defaultValue(false)
        .visible(hazeyBypass::get)
        .build()
    );

    private final Setting<Double> bypassFrequency = sgHazey.add(new DoubleSetting.Builder()
        .name("frequency")
        .description("Seconds the elytra stays on between two chestplate windows.")
        .defaultValue(5.0)
        .min(0.5)
        .sliderMax(30.0)
        .visible(hazeyBypass::get)
        .build()
    );

    private final Setting<Double> bypassTime = sgHazey.add(new DoubleSetting.Builder()
        .name("time")
        .description("How long the chestplate stays on, in seconds.")
        .defaultValue(1.0)
        .min(0.05)
        .sliderMax(10.0)
        .visible(() -> hazeyBypass.get() && !oneTick.get())
        .build()
    );

    private final Setting<Boolean> refly = sgHazey.add(new BoolSetting.Builder()
        .name("refly")
        .description("Start gliding again as soon as the elytra is back on.")
        .defaultValue(true)
        .visible(hazeyBypass::get)
        .build()
    );

    private final Setting<Boolean> keeperPlus = sgHazey.add(new BoolSetting.Builder()
        .name("keeper-plus")
        .description("Re-start the glide every tick while the chestplate is on, so the client keeps flying through the window.")
        .defaultValue(true)
        .visible(hazeyBypass::get)
        .build()
    );

    private final Setting<Boolean> debug = sgHazey.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print every Hazey bypass step to chat.")
        .defaultValue(false)
        .visible(hazeyBypass::get)
        .build()
    );

    private boolean lastJumpPressed;
    private boolean incrementJumpTimer;
    private int jumpTimer;
    private double rampedSpeed;

    private boolean wearingChestplate;
    private int stowedSlot = -1;
    private int ticksLeft;
    private int cooldown;
    private int settleTicks;
    private int restoreAttempts;
    private boolean wasGliding;
    private boolean reflyPending;
    private boolean keeperAnnounced;
    private String lastDebugState = "";

    public ElytraControl() {
        super(RyanWare.CATEGORY_STANDARD, RyanWare.modulePrefix_standard + "Elytra-Control",
            "Meteor-style elytra control: your keys drive the flight vector, with the Hazey speed bypass.");
    }

    @Override
    public void onActivate() {
        resetState();
        debug("module enabled, chest=%s cursor=%s", chestName(), cursorName());
    }

    @Override
    public void onDeactivate() {
        if (wearingChestplate) restoreElytra();
        resetState();
    }

    private void resetState() {
        lastJumpPressed = false;
        incrementJumpTimer = false;
        jumpTimer = 0;
        rampedSpeed = 0;
        wearingChestplate = false;
        stowedSlot = -1;
        ticksLeft = 0;
        cooldown = 0;
        settleTicks = 0;
        restoreAttempts = 0;
        wasGliding = false;
        reflyPending = false;
        keeperAnnounced = false;
        lastDebugState = "";
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (reflyPending) tickRefly();

        if (!hazeyBypass.get()) {
            if (wearingChestplate) restoreElytra();
            return;
        }

        if (!canClick()) {
            debugThrottled("blocked: " + guardReason());
            return;
        }

        if (settleTicks > 0) {
            settleTicks--;
            return;
        }

        if (wearingChestplate) {
            applyKeeper();
            debugThrottled("wearing the chestplate, " + ticksLeft + " ticks left");
            if (--ticksLeft <= 0) {
                restoreElytra();
                cooldown = Math.max(SETTLE_TICKS, frequencyTicks());
            }
            return;
        }

        if (cooldown > 0) {
            debugThrottled("waiting " + cooldown + " ticks before the next chestplate window");
            cooldown--;
            return;
        }

        if (!chestHasElytra()) {
            debugThrottled("wear the elytra to start the cycle, chest is " + chestName());
            cooldown = frequencyTicks();
            return;
        }

        swapToChestplate();
    }

    private void applyKeeper() {
        if (!keeperPlus.get() || !wasGliding) return;
        if (mc.player == null) return;
        if (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.hasVehicle()
            || mc.player.getAbilities().flying || !mc.player.isAlive()) {
            wasGliding = false;
            return;
        }

        if (!mc.player.isGliding()) startGliding();

        if (!keeperAnnounced) {
            keeperAnnounced = true;
            debug("keeper+: holding the glide with the chestplate on");
        }
    }

    private void tickRefly() {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            reflyPending = false;
            return;
        }
        if (!chestHasElytra()) return;
        if (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isGliding()) {
            reflyPending = false;
            return;
        }

        startGliding();
        debug("refly: gliding again");
        reflyPending = false;
    }

    private void startGliding() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        mc.player.startGliding();
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void debug(String message, Object... args) {
        if (!debug.get()) return;
        info("[Hazey] " + String.format(message, args));
    }

    private void debugThrottled(String message) {
        if (!debug.get()) return;
        if (message.equals(lastDebugState)) return;
        lastDebugState = message;
        debug("%s", message);
    }

    private String guardReason() {
        if (mc.interactionManager == null) return "interaction manager is null";
        if (mc.currentScreen != null) return "screen open: " + mc.currentScreen.getClass().getSimpleName();
        if (!cursorStack().isEmpty()) return "cursor holds " + cursorName();
        return "unknown";
    }

    private String chestName() {
        if (mc.player == null) return "no player";
        ItemStack stack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return stack.isEmpty() ? "empty" : stack.getName().getString();
    }

    private String cursorName() {
        ItemStack stack = cursorStack();
        return stack.isEmpty() ? "empty" : stack.getName().getString();
    }

    private ItemStack cursorStack() {
        if (mc.player == null) return ItemStack.EMPTY;
        return mc.player.currentScreenHandler.getCursorStack();
    }

    private boolean chestHasElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    private void swapToChestplate() {
        int chestplate = findChestplateSlot();
        if (chestplate < 0) {
            debugThrottled("no chestplate in the inventory");
            cooldown = frequencyTicks();
            return;
        }

        wasGliding = mc.player.isGliding();
        debug("swap: chestplate at slot %d, chest was %s, gliding=%s", chestplate, chestName(), wasGliding);
        swapChestWith(chestplate);
        settleTicks = SETTLE_TICKS;

        if (chestHasElytra()) {
            debug("swap rejected: chest is still %s, backing off", chestName());
            cooldown = frequencyTicks();
            return;
        }

        debug("swap: chest is now %s, cursor=%s", chestName(), cursorName());
        stowedSlot = chestplate;
        wearingChestplate = true;
        restoreAttempts = 0;
        ticksLeft = durationTicks();
    }

    private void restoreElytra() {
        if (!wearingChestplate) return;

        if (mc.player == null) {
            debug("restore skipped: no player");
            wearingChestplate = false;
            return;
        }
        if (!canClick()) {
            debugThrottled("restore blocked: " + guardReason());
            return;
        }
        if (chestHasElytra()) {
            debug("restore skipped: elytra is already worn");
            finishRestore();
            return;
        }

        int elytra = findSlot(Items.ELYTRA);
        if (elytra < 0) elytra = stowedSlot;
        debug("restore: elytra at slot %d, chest was %s", elytra, chestName());
        swapChestWith(elytra);
        settleTicks = SETTLE_TICKS;

        if (!chestHasElytra()) {
            restoreAttempts++;
            debug("restore rejected: chest is %s (attempt %d)", chestName(), restoreAttempts);
            if (restoreAttempts >= MAX_RESTORE_ATTEMPTS) {
                debug("giving up on this window");
                wearingChestplate = false;
                stowedSlot = -1;
                ticksLeft = 0;
                restoreAttempts = 0;
                cooldown = frequencyTicks();
            }
            return;
        }

        debug("restore: chest is now %s, cursor=%s", chestName(), cursorName());
        finishRestore();
    }

    private void finishRestore() {
        wearingChestplate = false;
        stowedSlot = -1;
        ticksLeft = 0;
        restoreAttempts = 0;
        reflyPending = refly.get() && mc.player != null && !mc.player.isOnGround();
    }

    /** Same clicks as a manual swap: pick the item up, drop it on the chest slot, put the old piece back. */
    private void swapChestWith(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot > 35) return;
        if (mc.player == null || mc.interactionManager == null) return;

        int containerSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        int syncId = mc.player.currentScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, CHEST_SLOT, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    private int findSlot(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private int findChestplateSlot() {
        for (Item item : CHESTPLATES) {
            int slot = findSlot(item);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    private int frequencyTicks() {
        return Math.max(1, (int) Math.round(bypassFrequency.get() * TICKS_PER_SECOND));
    }

    private int durationTicks() {
        if (oneTick.get()) return 1;
        return Math.max(1, (int) Math.round(bypassTime.get() * TICKS_PER_SECOND));
    }

    private boolean canClick() {
        return mc.interactionManager != null && mc.currentScreen == null && cursorStack().isEmpty();
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;

        boolean keeperGlide = keeperPlus.get() && wearingChestplate && wasGliding && mc.player.isGliding();
        if (!keeperGlide && !chestHasElytra()) return;

        autoTakeOff();

        if (!mc.player.isGliding()) {
            rampedSpeed = 0;
            return;
        }

        if (mc.player.isTouchingWater() && stopInWater.get()) {
            startGliding();
            return;
        }

        Vec3d forward = direction(mc.player.getYaw()).multiply(0.1);
        Vec3d right = direction(mc.player.getYaw() + 90f).multiply(0.1);

        double velX = 0.0;
        double velZ = 0.0;
        double velY = event.movement.y;

        if (velY < 0.0) {
            velY *= fallMultiplier.get();
        } else if (velY > 0.0) {
            velY = 0.0;
        }

        double speed = nextSpeed();

        boolean movingForward = false;
        boolean movingSideways = false;

        if (mc.options.forwardKey.isPressed()) {
            velX += forward.x * speed * 10.0;
            velZ += forward.z * speed * 10.0;
            movingForward = true;
        } else if (mc.options.backKey.isPressed()) {
            velX -= forward.x * speed * 10.0;
            velZ -= forward.z * speed * 10.0;
            movingForward = true;
        }

        if (mc.options.rightKey.isPressed()) {
            velX += right.x * speed * 10.0;
            velZ += right.z * speed * 10.0;
            movingSideways = true;
        } else if (mc.options.leftKey.isPressed()) {
            velX -= right.x * speed * 10.0;
            velZ -= right.z * speed * 10.0;
            movingSideways = true;
        }

        if (movingForward && movingSideways) {
            double diagonal = 1.0 / Math.sqrt(2.0);
            velX *= diagonal;
            velZ *= diagonal;
        }

        if (mc.options.jumpKey.isPressed()) {
            velY += 0.5 * verticalSpeed.get();
        } else if (mc.options.sneakKey.isPressed()) {
            velY -= 0.5 * verticalSpeed.get();
        }

        event.movement = new Vec3d(velX, velY, velZ);
    }

    private double nextSpeed() {
        if (!acceleration.get()) {
            rampedSpeed = 0;
            return horizontalSpeed.get();
        }
        if (!hasDirectionalInput()) rampedSpeed = 0;
        rampedSpeed = Math.min(rampedSpeed + accelerationStart.get() + accelerationStep.get() * 0.1,
            horizontalSpeed.get());
        return rampedSpeed;
    }

    private boolean hasDirectionalInput() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
            || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
    }

    private void autoTakeOff() {
        if (incrementJumpTimer) jumpTimer++;

        boolean jumpPressed = mc.options.jumpKey.isPressed();

        if (autoTakeOff.get() && jumpPressed && mc.getNetworkHandler() != null) {
            if (!lastJumpPressed && !mc.player.isGliding()) {
                jumpTimer = 0;
                incrementJumpTimer = true;
            }

            if (jumpTimer >= TAKEOFF_JUMP_TICKS) {
                jumpTimer = 0;
                incrementJumpTimer = false;
                mc.player.setSprinting(true);
                mc.player.jump();
                startGliding();
            }
        }

        lastJumpPressed = jumpPressed;
    }

    private Vec3d direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    @Override
    public String getInfoString() {
        if (wearingChestplate) return "chest " + ticksLeft + "t";
        return mc.player != null && mc.player.isGliding() ? "control" : null;
    }
}
