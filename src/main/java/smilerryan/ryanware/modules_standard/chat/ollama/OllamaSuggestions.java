package smilerryan.ryanware.modules_standard.chat.ollama;

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

    private final Setting<String> prompt = sgPrompt.add(new StringSetting.Builder()
        .name("prompt")
        .description("Prompt sent to Ollama. Use {input} for the chat message.")
        .defaultValue(
            "Respond with only the corrected version of the following input with proper english and grammer only, no explanations:
{input}"
        )
        .build()
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private volatile String sentPrompt = "";
    private volatile String response = "";

    private String lastInput = null;

    /*
     * Time when the current debounce period ends.
     */
    private long debounceUntil = 0;

    /*
     * Identifies the newest request.
     */
    private volatile int requestId = 0;

    /*
     * Actual active Ollama HTTP request.
     */
    private volatile Ollama.Request activeRequest = null;

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
        debounceUntil = 0;
        requestId++;
    }

    @Override
    public void onDeactivate() {
        cancelCurrentRequest();

        lastInput = null;
        sentPrompt = "";
        response = "";
        debounceUntil = 0;
        requestId++;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) {
            return;
        }

        if (!(mc.currentScreen instanceof ChatScreen chatScreen)) {
            cancelCurrentRequest();

            lastInput = null;
            sentPrompt = "";
            response = "";
            debounceUntil = 0;

            return;
        }

        TextFieldWidget field = getChatField(chatScreen);

        if (field == null) {
            return;
        }

        String input = field.getText();

        /*
         * Input changed.
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

    private void requestOllama(
        String input,
        int currentRequest
    ) {
        /*
         * Make absolutely sure an old request isn't still active.
         */
        cancelCurrentRequest();

        String fullPrompt = prompt.get()
            .replace("{input}", input);

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

                response = result == null ? "" : result;

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
         * PROMPT
         */
        if (!sentPrompt.isEmpty()) {

            event.drawContext.drawTextWithShadow(
                mc.textRenderer,
                "Prompt:",
                x,
                y,
                0xFFFFFF55
            );

            y += mc.textRenderer.fontHeight + 2;

            for (
                String line :
                sentPrompt.replace("\r\n", "\n").split("\n")
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
                "Ollama:",
                x,
                y,
                0xFF55FFFF
            );

            y += mc.textRenderer.fontHeight + 2;

            for (
                String line :
                response.replace("\r\n", "\n").split("\n")
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
