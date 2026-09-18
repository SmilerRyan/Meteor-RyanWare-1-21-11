package smilerryan.ryanware.modules_standard.chat;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;

import smilerryan.ryanware.RyanWare;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatLogger extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> pathFormat = sgGeneral.add(new StringSetting.Builder()
        .name("path-format")
        .description("Full log path format.")
        .defaultValue("logs/chat/%date%_%time%_%player%_%server%.txt")
        .build()
    );

    private File logFile;
    private boolean sessionInitialized = false;

    public ChatLogger() {
        super(
            RyanWare.CATEGORY_STANDARD,
            RyanWare.modulePrefix_standard + "Chat-Logger",
            "Logs incoming and outgoing chat with session support."
        );
    }

    @Override
    public void onActivate() {
        logFile = null;
        sessionInitialized = false;
        ensureSession();
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        logFile = null;
        sessionInitialized = false;
        ensureSession();
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        Text t = event.getMessage();
        if (t == null) return;

        log("[I] " + t.getString());
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (event.message == null) return;

        log("[O] " + event.message);
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send e) {
        Packet<?> p = e.packet;

        if (p instanceof CommandExecutionC2SPacket cmd) {
            log("[O] /" + cmd.command());
        }

        if (p instanceof ChatCommandSignedC2SPacket signed) {
            log("[O] /" + signed.command());
        }
    }

    private void ensureSession() {
        if (logFile != null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        String player = "unknown";
        if (mc.player != null && mc.player.getGameProfile() != null) {
            player = mc.player.getName().getString();
        }

        String server = "singleplayer";
        if (mc.getCurrentServerEntry() != null) {
            server = mc.getCurrentServerEntry().address;
        }

        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String time = new SimpleDateFormat("HH-mm-ss").format(new Date());

        String path = pathFormat.get()
            .replace("%server%", sanitize(server))
            .replace("%player%", sanitize(player))
            .replace("%date%", sanitize(date))
            .replace("%time%", sanitize(time));

        if (new File(path).isAbsolute()) {
            logFile = new File(path);
        } else {
            logFile = new File(mc.runDirectory, path);
        }
    }

    private void log(String text) {
        try {
            ensureSession();
            if (logFile == null) return;

            if (!sessionInitialized) {
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                sessionInitialized = true;
            }

            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());

            FileWriter writer = new FileWriter(logFile, true);
            writer.write("[" + timestamp + "] " + text + "\n");
            writer.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
