package smilerryan.ryanware.modules_standard.chat.ollama;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;

import smilerryan.ryanware.RyanWare;
import smilerryan.ryanware.modules_standard.Settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaSuggestions extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPrompt = settings.createGroup("Prompt");

    private final Setting<String> model = sgGeneral.add(new StringSetting.Builder()
        .name("model")
        .description("Model to use.")
        .defaultValue("llama3.2")
        .build()
    );

    private final Setting<Integer> debounce = sgGeneral.add(
        new IntSetting.Builder()
            .name("debounce")
            .description("Time to wait after typing before sending to Ollama.")
            .defaultValue(250)
            .min(0)
            .sliderMax(2000)
            .build()
    );

    private final Setting<Boolean> instantAnswers = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-answers")
        .description("Trigger AI query immediately when opening chat without requiring input. Closing chat cancels slow requests.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> instantDebounce = sgGeneral.add(
        new IntSetting.Builder()
            .name("instant-debounce")
            .description("Wait time (ms) before triggering instant answer when chat opens.")
            .defaultValue(500)
            .min(0)
            .sliderMax(5000)
            .build()
    );

    private final Setting<String> prompt = sgPrompt.add(new StringSetting.Builder()
        .name("prompt")
        .description("Prompt sent to Ollama. Use {history_X} for X previous chat messages (e.g. {history_1}, {history_100}) and {input} for the chat message. Leave input empty for instant answers.")
        .defaultValue("{history_100}\n{input}")
        .build()
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private volatile String sentPrompt = "";
    private volatile String response = "";

    private String lastInput = null;
    private String lastAppliedResponse = null;

    /**
     * Time when the current debounce period ends.
     */
    private long debounceUntil = 0;

    /**
     * Time when instant answer debounce period ends.
     */
    private long instantDebounceUntil = 0;

    /**
     * Identifies the newest request.
     */
    private volatile int requestId = 0;

    /**
     * Actual active Ollama HTTP request.
     */
    private volatile Ollama.Request activeRequest = null;

    /**
     * Actual Minecraft chat messages received through Meteor.
     *
     * These are incoming game chat messages, not the chat input history.
     */
    private final Deque<String> recentMessages = new ArrayDeque<>();

    private static final int MAX_MESSAGES = 100;

    /**
     * Tracks if we've already triggered instant answer in this chat session.
     */
    private boolean instantAnswerTriggered = false;

    public OllamaSuggestions() {
        super(
            RyanWare.CATEGORY_STANDARD,
            RyanWare.modulePrefix_standard + "Ollama-Suggestions",
            "Gets Ollama responses from Minecraft chat input."
        );
    }

    @Override
    public void onActivate() {
        cancelCurrentRequest();

        lastInput = null;
        sentPrompt = "";
        response = "";
        lastAppliedResponse = null;
        debounceUntil = 0;
        instantDebounceUntil = 0;
        instantAnswerTriggered = false;
        requestId++;

        /*
         * Start with a fresh history for this module activation.
         */
        synchronized (recentMessages) {
            recentMessages.clear();
        }
    }

    @Override
    public void onDeactivate() {
        cancelCurrentRequest();

        lastInput = null;
        sentPrompt = "";
        response = "";
        lastAppliedResponse = null;
        debounceUntil = 0;
        instantDebounceUntil = 0;
        instantAnswerTriggered = false;
        requestId++;

        synchronized (recentMessages) {
            recentMessages.clear();
        }
    }

    /**
     * Receives actual Minecraft chat messages.
     *
     * This is the same mechanism used by OllamaChat.
     *
     * It includes messages from:
     * - Other players
     * - The local player
     * - Server messages
     * - Join/leave messages
     * - Announcements
     * - Other messages received by Meteor's ReceiveMessageEvent
     */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive()) {
            return;
        }

        if (event.getMessage() == null) {
            return;
        }

        String message = event.getMessage().getString();

        if (message == null || message.isBlank()) {
            return;
        }

        synchronized (recentMessages) {
            recentMessages.addLast(message);

            while (recentMessages.size() > MAX_MESSAGES) {
                recentMessages.removeFirst();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) {
            return;
        }

        if (!(mc.currentScreen instanceof ChatScreen chatScreen)) {
            // Chat closed - cancel any pending instant answer request.
            if (instantDebounceUntil > 0 || activeRequest != null) {
                cancelCurrentRequest();
                instantDebounceUntil = 0;
                instantAnswerTriggered = false;
            }

            lastInput = null;
            sentPrompt = "";
            response = "";
            lastAppliedResponse = null;
            debounceUntil = 0;

            return;
        }

        /*
         * Instant answer handling.
         */
        if (instantAnswers.get() && !instantAnswerTriggered) {
            if (instantDebounceUntil == 0) {

                // Start instant answer debounce when chat opens.
                instantDebounceUntil =
                    System.currentTimeMillis() + instantDebounce.get();

            } else if (System.currentTimeMillis() >= instantDebounceUntil) {

                // Instant answer debounce finished, trigger request.
                instantAnswerTriggered = true;
                instantDebounceUntil = 0;

                int currentRequest = requestId;

                requestOllama("", currentRequest);
            }
        }

        TextFieldWidget field = getChatField(chatScreen);

        if (field == null) {
            return;
        }

        String input = field.getText();

        /*
         * Input changed - always trigger standard flow regardless
         * of instant answers.
         */
        if (!input.equals(lastInput)) {

            lastInput = input;

            /*
             * Immediately cancel the currently running HTTP request.
             */
            cancelCurrentRequest();

            /*
             * Invalidate the previous request.
             */
            requestId++;

            /*
             * Clear the old prompt/output.
             */
            sentPrompt = "";
            response = "";
            lastAppliedResponse = null;

            /*
             * Cancel instant answer tracking since user started typing.
             */
            instantAnswerTriggered = false;
            instantDebounceUntil = 0;

            /*
             * Empty input doesn't need an Ollama request.
             */
            if (input.isBlank()) {
                debounceUntil = 0;
                return;
            }

            /*
             * Start/restart debounce timer.
             */
            debounceUntil =
                System.currentTimeMillis() + debounce.get();

            return;
        }

        /*
         * Nothing changed, so check whether debounce has finished.
         */
        if (input.isBlank()) {
            return;
        }

        if (debounceUntil == 0) {
            return;
        }

        if (System.currentTimeMillis() < debounceUntil) {
            return;
        }

        /*
         * Consume the timer so this request isn't started again.
         */
        debounceUntil = 0;

        /*
         * Start the request.
         */
        int currentRequest = requestId;

        requestOllama(input, currentRequest);
    }

    /**
     * Parses the history placeholder from the prompt template
     * and extracts the requested number of messages.
     */
    private int extractHistoryCount(String promptTemplate) {
        Pattern pattern = Pattern.compile("\\{history_(\\d+)\\}");
        Matcher matcher = pattern.matcher(promptTemplate);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        return 0;
    }

    /**
     * Builds the history string from actual Minecraft chat messages.
     *
     * Example:
     *
     * {history_10}
     *
     * gives the 10 most recent messages received through
     * ReceiveMessageEvent.
     */
    private String buildHistoryString(String promptTemplate) {
        int limit = extractHistoryCount(promptTemplate);

        if (limit <= 0) {
            return "";
        }

        List<String> messages;

        synchronized (recentMessages) {
            messages = new ArrayList<>(recentMessages);
        }

        if (messages.isEmpty()) {
            return "";
        }

        /*
         * Keep only the newest X messages.
         */
        if (messages.size() > limit) {
            messages = messages.subList(
                messages.size() - limit,
                messages.size()
            );
        }

        return String.join("\n", messages);
    }

    /**
     * Replaces all {history_X} placeholders in the prompt.
     */
    private String replaceHistoryPlaceholder(
        String promptTemplate,
        String history
    ) {
        return promptTemplate.replaceAll(
            "\\{history_\\d+\\}",
            Matcher.quoteReplacement(history)
        );
    }

    private void requestOllama(String input, int currentRequest) {

        /*
         * Make absolutely sure an old request isn't still active.
         */
        cancelCurrentRequest();

        String promptTemplate = prompt.get();

        String history =
            buildHistoryString(promptTemplate);

        String fullPrompt =
            replaceHistoryPlaceholder(
                promptTemplate,
                history
            ).replace("{input}", input);

        /*
         * Show exactly what is being sent.
         */
        sentPrompt = fullPrompt;
        response = "";

        Ollama.Request request = new Ollama.Request();

        activeRequest = request;

        Thread thread = new Thread(() -> {

            try {

                String result = Ollama.queryOllama(
                    Modules.get()
                        .get(Settings.class)
                        .s_Ollama_Url
                        .get(),

                    model.get(),
                    fullPrompt,
                    this,
                    request
                );

                /*
                 * Don't allow an obsolete request to touch the UI.
                 */
                if (
                    request.isCancelled()
                    || currentRequest != requestId
                ) {
                    return;
                }

                response =
                    result == null
                        ? ""
                        : result;

            } catch (Exception e) {

                if (
                    request.isCancelled()
                    || currentRequest != requestId
                ) {
                    return;
                }

                response =
                    "Ollama error: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage();

            } finally {

                /*
                 * Only clear activeRequest if this is still
                 * the currently active request.
                 */
                if (activeRequest == request) {
                    activeRequest = null;
                }
            }

        }, "Ollama-Suggestions-Worker");

        thread.setDaemon(true);
        thread.start();
    }

    private void cancelCurrentRequest() {
        Ollama.Request request = activeRequest;

        if (request != null) {
            request.cancel();
            activeRequest = null;
        }
    }

    @EventHandler
    private void onRender(Render2DEvent event) {

        if (!isActive()) {
            return;
        }

        if (!(mc.currentScreen instanceof ChatScreen)) {
            return;
        }

        int x = 5;
        int y = 5;

        /*
         * Instant Answer Status Indicator.
         */
        if (instantAnswers.get() && !instantAnswerTriggered) {

            event.drawContext.drawTextWithShadow(
                mc.textRenderer,
                "Instant: Waiting...",
                x,
                y,
                0xFFFF5555
            );

            y += mc.textRenderer.fontHeight + 4;
        }

        /*
         * PROMPT
         */
        if (!sentPrompt.isEmpty()) {

            event.drawContext.drawTextWithShadow(
                mc.textRenderer,
                "Sent:",
                x,
                y,
                0xFFFFFF55
            );

            y += mc.textRenderer.fontHeight + 2;

            for (
                String line :
                sentPrompt
                    .replace("\r\n", "\n")
                    .split("\n")
            ) {

                event.drawContext.drawTextWithShadow(
                    mc.textRenderer,
                    line,
                    x,
                    y,
                    0xFFFFFFFF
                );

                y += mc.textRenderer.fontHeight + 2;
            }

            y += 5;
        }

        /*
         * RESPONSE
         */
        if (!response.isEmpty()) {

            event.drawContext.drawTextWithShadow(
                mc.textRenderer,
                "Response:",
                x,
                y,
                0xFF55FFFF
            );

            y += mc.textRenderer.fontHeight + 2;

            for (
                String line :
                response
                    .replace("\r\n", "\n")
                    .split("\n")
            ) {

                event.drawContext.drawTextWithShadow(
                    mc.textRenderer,
                    line,
                    x,
                    y,
                    0xFFFFFFFF
                );

                y += mc.textRenderer.fontHeight + 2;
            }
        }
    }

    private TextFieldWidget getChatField(ChatScreen chatScreen) {
        return (TextFieldWidget) chatScreen.children().stream()
            .filter(child -> child instanceof TextFieldWidget)
            .findFirst()
            .orElse(null);
    }
}