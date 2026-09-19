package smilerryan.ryanware.modules_standard.chat.ollama;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import meteordevelopment.meteorclient.systems.modules.Module;

public class Ollama {

    public static class Request {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled = false;

        public void setConnection(HttpURLConnection connection) {
            this.connection = connection;

            if (cancelled && connection != null) {
                connection.disconnect();
            }
        }

        public void cancel() {
            cancelled = true;

            HttpURLConnection conn = connection;

            if (conn != null) {
                conn.disconnect();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {return "";}
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String extractJsonContent(String line) {
        int contentStartIndex = line.indexOf("\"content\":\"");
        if (contentStartIndex == -1) {
            return null;
        }

        contentStartIndex += 11;
        StringBuilder contentBuilder = new StringBuilder();
        boolean isEscaped = false;

        for (int i = contentStartIndex; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (isEscaped) {
                switch (ch) {
                    case '\"': contentBuilder.append('"'); break;
                    case '\\': contentBuilder.append('\\'); break;
                    case 'n': contentBuilder.append('\n'); break;
                    case 'r': contentBuilder.append('\r'); break;
                    case 't': contentBuilder.append('\t'); break;
                    default: contentBuilder.append(ch); break;
                }
                isEscaped = false;
            } else if (ch == '\\') {
                isEscaped = true;
            } else if (ch == '"') {
                break;
            } else {
                contentBuilder.append(ch);
            }
        }

        return contentBuilder.toString();
    }

    private static String cleanThinkingTags(String response) {
        return response
            .replaceAll("(?i)<think>.*?</think>", "")
            .replaceAll("(?i)\\\\u003c/?think\\\\u003e", "")
            .replaceAll("(?i)<think>|</think>", "")
            .trim();
    }

    public static String queryOllama(
        String baseUrl,
        String modelName,
        String prompt,
        Module specificModule
    ) {
        return queryOllama(
            baseUrl,
            modelName,
            prompt,
            specificModule,
            new Request()
        );
    }

    public static String queryOllama(
        String baseUrl,
        String modelName,
        String prompt,
        Module specificModule,
        Request request
    ) {
        HttpURLConnection conn = null;

        try {
            if (request.isCancelled()) {
                return "";
            }

            URL url = new URL(baseUrl + "/api/chat");

            conn = (HttpURLConnection) url.openConnection();

            request.setConnection(conn);

            if (request.isCancelled()) {
                conn.disconnect();
                return "";
            }

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            );

            String json = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false}",
                escapeJson(modelName), 
                escapeJson(prompt), 
                escapeJson(prompt)
            );

            try (OutputStream os = conn.getOutputStream()) {
                if (request.isCancelled()) {
                    return "";
                }

                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            if (request.isCancelled()) {
                return "";
            }

            int responseCode = conn.getResponseCode();

            if (request.isCancelled()) {
                return "";
            }

            if (responseCode != 200) {
                if (!request.isCancelled()) {
                    specificModule.error(
                        "Ollama query failed: HTTP " + responseCode
                    );
                }

                return null;
            }

            StringBuilder response = new StringBuilder();

            try (
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                        conn.getInputStream(),
                        StandardCharsets.UTF_8
                    )
                )
            ) {
                String line;

                while ((line = br.readLine()) != null) {

                    /*
                     * Check cancellation on every response line.
                     */
                    if (request.isCancelled()) {
                        return "";
                    }

                    String content = extractJsonContent(line);

                    if (content != null) {
                        response.append(content);
                    }
                }
            }

            if (request.isCancelled()) {
                return "";
            }

            return cleanThinkingTags(response.toString());

        } catch (Exception e) {

            /*
             * disconnect() normally causes an exception when
             * cancelling a blocked HTTP request. That's expected.
             */
            if (request.isCancelled()) {
                return "";
            }

            specificModule.error(
                "Ollama query failed: " + e.getMessage()
            );

            return null;

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

}
