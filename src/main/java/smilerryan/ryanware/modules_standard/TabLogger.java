package smilerryan.ryanware.modules_standard;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;

import smilerryan.ryanware.RyanWare;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

public class TabLogger extends Module {

    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TabLogger-Thread");
        t.setDaemon(true);
        return t;
    });

    private Set<PlayerSnapshot> lastSnapshots = new HashSet<>();

    public TabLogger() {
        super(RyanWare.CATEGORY_STANDARD, RyanWare.modulePrefix_standard + "Tab-Logger", "Logs the tab list to text file.");
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> ignoreZeroPing = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-zero-ping")
        .description("Ignore players with 0 ping.")
        .defaultValue(true)
        .build()
    );

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;

        Set<PlayerSnapshot> currentSnapshots = new HashSet<>();

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() == null) continue;

            String uuid = entry.getProfile().id() != null ? entry.getProfile().id().toString() : "";
            String name = entry.getProfile().name() != null ? entry.getProfile().name() : "";

            if (uuid.isEmpty() || name.isEmpty()) continue;

            int ping = entry.getLatency();
            if (ignoreZeroPing.get() && ping == 0) continue;
            currentSnapshots.add(new PlayerSnapshot(uuid, name, ping));
        }

        if (!currentSnapshots.equals(lastSnapshots)) {
            Set<PlayerSnapshot> snapshotToProcess = new HashSet<>(currentSnapshots);
            lastSnapshots = currentSnapshots;
            ServerInfo server = mc.getCurrentServerEntry();
            String serverIp = server != null ? server.address.replaceAll("[:]", "_") : "singleplayer";
            logExecutor.submit(() -> processAndWriteLogs(snapshotToProcess, serverIp));
        }
    }

    private void processAndWriteLogs(Set<PlayerSnapshot> snapshots, String serverIp) {
        File folder = new File("logs" + File.separator + "tab");
        if (!folder.exists()) folder.mkdirs();

        File logFile = new File(folder, serverIp + ".txt");

        List<String> lines = new ArrayList<>();
        if (logFile.exists()) {
            try {
                lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        boolean modified = false;

        for (PlayerSnapshot snapshot : snapshots) {
            boolean uuidAndNameFound = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String[] parts = line.split(",", -1);

                if (parts.length >= 2 && parts[0].equalsIgnoreCase(snapshot.uuid) && parts[1].equalsIgnoreCase(snapshot.name)) {
                    uuidAndNameFound = true;

                    int lastPing = -1;
                    if (parts.length >= 3) {
                        try {
                            lastPing = Integer.parseInt(parts[parts.length - 1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    if (lastPing != snapshot.ping) {
                        lines.set(i, line + "," + snapshot.ping);
                        modified = true;
                    }

                    break;
                }
            }

            if (!uuidAndNameFound) {
                lines.add(snapshot.uuid + "," + snapshot.name + "," + snapshot.ping);
                modified = true;
            }
        }

        if (modified) {
            try {
                Files.write(logFile.toPath(), lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class PlayerSnapshot {
        final String uuid;
        final String name;
        final int ping;

        PlayerSnapshot(String uuid, String name, int ping) {
            this.uuid = uuid;
            this.name = name;
            this.ping = ping;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PlayerSnapshot that = (PlayerSnapshot) o;
            return ping == that.ping &&
                   Objects.equals(uuid, that.uuid) &&
                   Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid, name, ping);
        }
    }
}
