package io.github.warleysr.autopix.mercadopago;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.api.AutoPixAPI;

/**
 * Optional local loopback HTTP receiver for Mercado Pago webhooks.
 * Disabled by default; when enabled, binds to 127.0.0.1 (loopback) so a local reverse proxy
 * (e.g., Nginx / Cloudflare Tunnel) can forward authenticated webhook calls without exposing
 * an unguarded public port on the Minecraft server.
 */
public final class WebhookHttpServer {

    private static volatile HttpServer server;

    private WebhookHttpServer() {
    }

    public static synchronized void start(AutoPix plugin) {
        startIfConfigured(plugin);
    }

    public static synchronized void startIfConfigured(AutoPix plugin) {
        stop();
        if (plugin == null || !plugin.isEnabled()) return;
        if (!plugin.getConfig().getBoolean("automatico.webhook.ativado", false)) {
            return;
        }

        String bindHost = plugin.getConfig().getString("automatico.webhook.bind", "127.0.0.1").trim();
        int port = plugin.getConfig().getInt("automatico.webhook.porta", 8090);
        String path = plugin.getConfig().getString("automatico.webhook.path", "/autopix/webhook/mercadopago").trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        try {
            HttpServer candidate = HttpServer.create(new InetSocketAddress(bindHost, port), 16);
            candidate.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AutoPix-Webhook-HTTP");
                t.setDaemon(true);
                return t;
            }));
            candidate.createContext(path, exchange -> handleRequest(plugin, exchange));
            candidate.start();
            server = candidate;
            plugin.getLogger().info("Receptor HTTP de Webhooks ativo em " + bindHost + ":" + port + path);
        } catch (Exception ex) {
            plugin.getLogger().severe("Falha ao iniciar receptor HTTP de Webhooks: " + ex.getMessage());
        }
    }

    public static synchronized void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            try {
                current.stop(0);
            } catch (Exception ignored) {
            }
        }
    }

    private static void handleRequest(AutoPix plugin, HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }

            String xSignature = exchange.getRequestHeaders().getFirst("x-signature");
            String xRequestId = exchange.getRequestHeaders().getFirst("x-request-id");

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                byte[] bytes = is.readAllBytes();
                body = new String(bytes, StandardCharsets.UTF_8);
            }

            String topic = "payment";
            String dataId = null;

            URI uri = exchange.getRequestURI();
            if (uri != null && uri.getQuery() != null) {
                for (String param : uri.getQuery().split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        if ("data.id".equalsIgnoreCase(kv[0]) || "id".equalsIgnoreCase(kv[0])) {
                            dataId = kv[1].trim();
                        } else if ("type".equalsIgnoreCase(kv[0]) || "topic".equalsIgnoreCase(kv[0])) {
                            topic = kv[1].trim();
                        }
                    }
                }
            }

            if (body != null && !body.trim().isEmpty()) {
                try {
                    JSONObject json = (JSONObject) new JSONParser().parse(body);
                    if (json.get("type") != null) {
                        topic = String.valueOf(json.get("type"));
                    } else if (json.get("topic") != null) {
                        topic = String.valueOf(json.get("topic"));
                    }
                    Object dataObj = json.get("data");
                    if (dataObj instanceof JSONObject) {
                        Object idObj = ((JSONObject) dataObj).get("id");
                        if (idObj != null) {
                            dataId = String.valueOf(idObj);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            String secret = plugin.getConfig().getString("automatico.webhook.secret", "").trim();
            if (!WebhookValidator.validateSignature(xSignature, xRequestId, dataId, secret)) {
                plugin.getLogger().warning("Webhook rejeitado: assinatura criptografica invalida ou ausente.");
                sendResponse(exchange, 401, "{\"error\":\"invalid_signature\"}");
                return;
            }

            final String finalTopic = topic;
            final String finalDataId = dataId;
            final String finalRequestId = xRequestId;

            if (Bukkit.getServer() != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                        AutoPixAPI.processWebhookNotification(
                                finalTopic, finalDataId, finalRequestId, xSignature, finalRequestId, secret));
            } else {
                AutoPixAPI.processWebhookNotification(
                        finalTopic, finalDataId, finalRequestId, xSignature, finalRequestId, secret);
            }

            sendResponse(exchange, 200, "{\"status\":\"accepted\"}");
        } catch (Exception ex) {
            try {
                sendResponse(exchange, 500, "{\"error\":\"internal_error\"}");
            } catch (Exception ignored) {
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
