package smilerryan.ryanware.modules_standard;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import smilerryan.ryanware.RyanWare;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;

public class MorePing extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum PingMode {
        KEEPALIVE_ONLY,
        ALL_PACKETS
    }

    private final Setting<PingMode> mode = sgGeneral.add(
        new EnumSetting.Builder<PingMode>()
            .name("mode")
            .description("Which outgoing packets to delay.")
            .defaultValue(PingMode.KEEPALIVE_ONLY)
            .build()
    );

    private final Setting<Boolean> enableExactBoost = sgGeneral.add(
        new BoolSetting.Builder()
            .name("enable-exact-boost")
            .description("Automatically add the delay needed to approach the target ping.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> targetPing = sgGeneral.add(
        new IntSetting.Builder()
            .name("target-ping")
            .description("Desired total ping in milliseconds.")
            .defaultValue(100)
            .min(1)
            .sliderMin(1)
            .sliderMax(1000)
            .visible(enableExactBoost::get)
            .build()
    );

    private final Setting<Integer> minPing = sgGeneral.add(
        new IntSetting.Builder()
            .name("minimum-ping")
            .description("Fixed base delay in milliseconds.")
            .defaultValue(100)
            .min(0)
            .sliderMin(0)
            .sliderMax(1000)
            .visible(() -> !enableExactBoost.get())
            .build()
    );

    private final Setting<Integer> randomExtra = sgGeneral.add(
        new IntSetting.Builder()
            .name("random-extra")
            .description("Random additional delay from 0 to this value. Set to 0 for no jitter.")
            .defaultValue(50)
            .min(0)
            .sliderMin(0)
            .sliderMax(1000)
            .visible(() -> !enableExactBoost.get())
            .build()
    );

    // Packet identity matters: packets must be released only once.
    private final Object2LongMap<Packet<?>> packets =
        new Object2LongOpenHashMap<>();

    private final Set<Packet<?>> releasing =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private final Random random = new Random();

    private int basePing = -1;
    private int lastObservedPing = -1;
    private int lastAppliedDelay;
    private int currentDelay;
    private int autoBoost;

    public MorePing() {
        super(
            RyanWare.CATEGORY_STANDARD,
            RyanWare.modulePrefix_standard + "More-Ping",
            "Add fixed, random, or automatically calculated packet delay."
        );
    }

    @Override
    public void onActivate() {
        packets.clear();
        releasing.clear();
        basePing = -1;
        lastObservedPing = -1;
        lastAppliedDelay = 0;
        currentDelay = 0;
        autoBoost = 0;
    }

    @Override
    public void onDeactivate() {
        if (mc.getNetworkHandler() != null) {
            for (Packet<?> packet : packets.keySet().toArray(new Packet<?>[0])) {
                packets.removeLong(packet);
                releasing.add(packet);
                mc.getNetworkHandler().sendPacket(packet);
                releasing.remove(packet);
            }
        }

        packets.clear();
        releasing.clear();
        currentDelay = 0;
        autoBoost = 0;
        lastAppliedDelay = 0;
    }

    @Override
    public String getInfoString() {
        if (enableExactBoost.get()) {
            if (basePing < 0) {
                return "[WAITING →" + targetPing.get() + "]";
            }

            return String.format(
                "[%dms +%dms → %dms]",
                basePing,
                currentDelay,
                targetPing.get()
            );
        }

        return String.format(
            "[%s %d+0-%dms]",
            mode.get() == PingMode.ALL_PACKETS ? "ALL" : "KA",
            minPing.get(),
            randomExtra.get()
        );
    }

    private int getPlayerListPing() {
        if (mc.getNetworkHandler() == null || mc.getSession() == null) {
            return -1;
        }

        String username = mc.getSession().getUsername();

        for (var entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().name().equalsIgnoreCase(username)) {
                return Math.max(0, entry.getLatency());
            }
        }

        return -1;
    }

    private int calculateExactBoost() {
        int observed = getPlayerListPing();

        if (observed < 0) {
            basePing = -1;
            autoBoost = 0;
            currentDelay = minPing.get();
            return currentDelay;
        }

        // Estimate the underlying ping by removing our previous
        // artificial delay from the displayed latency.
        if (lastObservedPing < 0) {
            basePing = observed;
        } else if (observed != lastObservedPing) {
            basePing = Math.max(0, observed - lastAppliedDelay);
        }

        lastObservedPing = observed;

        // The fixed base is included in the target budget.
        autoBoost = Math.max(
            0,
            targetPing.get() - basePing - minPing.get()
        );

        currentDelay = minPing.get() + autoBoost;
        return currentDelay;
    }

    private int getDelay() {
        if (enableExactBoost.get()) {
            return calculateExactBoost();
        }

        currentDelay = minPing.get();
        if (randomExtra.get() > 0) {
            currentDelay += random.nextInt(randomExtra.get() + 1);
        }

        return currentDelay;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        Packet<?> packet = event.packet;

        // Allow packets sent by our own release operation through.
        if (releasing.remove(packet)) {
            return;
        }

        if (mode.get() == PingMode.KEEPALIVE_ONLY
            && !(packet instanceof KeepAliveC2SPacket)) {
            return;
        }

        int delay = getDelay();

        if (delay <= 0) {
            return;
        }

        if (packet instanceof KeepAliveC2SPacket) {
            lastAppliedDelay = delay;
        }

        // Store an absolute deadline, not a start time.
        packets.put(packet, System.currentTimeMillis() + delay);
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (packets.isEmpty() || mc.getNetworkHandler() == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (Packet<?> packet : packets.keySet().toArray(new Packet<?>[0])) {
            if (packets.getLong(packet) <= now) {
                packets.removeLong(packet);

                releasing.add(packet);
                mc.getNetworkHandler().sendPacket(packet);
                releasing.remove(packet);
            }
        }
    }
}
