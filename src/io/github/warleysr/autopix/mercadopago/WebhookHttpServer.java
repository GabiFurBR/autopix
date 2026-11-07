package io.github.warleysr.autopix.mercadopago;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.api.AutoPixAPI;

/**
 * Optional local loopback HTTP receiver for Mercado Pago webhooks.
 * Disabled by default; when enabled, binds to 127.0.0.1 (loopback) so a local reverse proxy
 * (e.g., Nginx / Cloudflare Tunnel) can forward authenticated webhook calls without exposing
 * an unguarded public port on the Minecraft server.
 */
public final class WebhookHttpServer {

    private static volatile HttpServer server;
    private static volatile ExecutorService executor;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    public static final class ParsedWebhookRequest {
        private final String topic;
        private final String dataId;
        private final String notificationId;
        private final String requestId;

        public ParsedWebhookRequest(String topic, String dataId, String notificationId, String requestId) {
            this.topic = topic;
            this.dataId = dataId;
            this.notificationId = notificationId;
            this.requestId = requestId;
        }

        public String getTopic() {
            return topic;
        }

        public String getDataId() {
            return dataId;
        }

        public String getNotificationId() {
            return notificationId;
        }

        public String getRequestId() {
            return requestId;
        }
    }

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
        startEndpoint(plugin, bindHost, port, path);
    }

    public static synchronized boolean startEndpoint(AutoPix plugin, String bindHost, int port, String path) {
        stop();
        String normalizedHost = (bindHost != null && !bindHost.trim().isEmpty()) ? bindHost.trim() : "127.0.0.1";
        String normalizedPath = (path != null && !path.trim().isEmpty()) ? path.trim() : "/autopix/webhook/mercadopago";
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        try {
            HttpServer candidate = HttpServer.create(new InetSocketAddress(normalizedHost, port), 32);
            ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "AutoPix-Webhook-HTTP-" + THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            candidate.setExecutor(pool);
            candidate.createContext(normalizedPath, exchange -> handleRequest(plugin, exchange));
            candidate.start();
            server = candidate;
            executor = pool;
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().info("Receptor HTTP de Webhooks ativo em "
                        + normalizedHost + ":" + candidate.getAddress().getPort() + normalizedPath);
            }
            return true;
        } catch (Exception ex) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().severe("Falha ao iniciar receptor HTTP de Webhooks: " + ex.getMessage());
            }
            return false;
        }
    }

    public static int getBoundPort() {
        HttpServer current = server;
        return current != null && current.getAddress() != null ? current.getAddress().getPort() : -1;
    }

    public static synchronized void stop() {
        HttpServer current = server;
        ExecutorService currentPool = executor;
        server = null;
        executor = null;
        if (current != null) {
            try {
                current.stop(0);
            } catch (Exception ignored) {
            }
        }
        if (currentPool != null) {
            try {
                currentPool.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    public static ParsedWebhookRequest parseWebhookRequest(URI uri, String body, String xRequestId) {
        String topic = "payment";
        String queryDataId = null;
        String queryNotifId = null;
        String jsonDataId = null;
        String jsonNotifId = null;

        if (uri != null && uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String val = kv[1].trim();
                    if ("data.id".equalsIgnoreCase(key)) {
                        queryDataId = val;
                    } else if ("id".equalsIgnoreCase(key) || "notification_id".equalsIgnoreCase(key)) {
                        queryNotifId = val;
                    } else if ("type".equalsIgnoreCase(key) || "topic".equalsIgnoreCase(key)) {
                        if (!val.isEmpty()) {
                            topic = val;
                        }
                    }
                }
            }
        }

        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject json = (JSONObject) new JSONParser().parse(body);
                if (json.get("type") != null) {
                    topic = String.valueOf(json.get("type")).trim();
                } else if (json.get("topic") != null) {
                    topic = String.valueOf(json.get("topic")).trim();
                } else if (json.get("action") != null) {
                    topic = String.valueOf(json.get("action")).trim();
                }
                if (json.get("id") != null) {
                    String candidateNotif = String.valueOf(json.get("id")).trim();
                    if (!candidateNotif.isEmpty()) {
                        jsonNotifId = candidateNotif;
                    }
                }
                Object dataObj = json.get("data");
                if (dataObj instanceof JSONObject) {
                    Object idObj = ((JSONObject) dataObj).get("id");
                    if (idObj != null) {
                        String candidateDataId = String.valueOf(idObj).trim();
                        if (!candidateDataId.isEmpty()) {
                            jsonDataId = candidateDataId;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String resolvedDataId = jsonDataId != null ? jsonDataId : (queryDataId != null ? queryDataId : queryNotifId);
        String resolvedNotificationId = jsonNotifId != null
                ? jsonNotifId
                : (queryNotifId != null && (jsonDataId != null || queryDataId != null) ? queryNotifId : null);
        String normalizedRequestId = xRequestId != null && !xRequestId.trim().isEmpty() ? xRequestId.trim() : null;

        return new ParsedWebhookRequest(topic, resolvedDataId, resolvedNotificationId, normalizedRequestId);
    }

    private static String resolveWebhookSecret(AutoPix plugin) {
        String apiSecret = AutoPixAPI.getConfiguredWebhookSecret();
        if (apiSecret != null && !apiSecret.trim().isEmpty()) {
            return apiSecret.trim();
        }
        if (plugin != null && plugin.getConfig() != null) {
            String autoSecret = plugin.getConfig().getString("automatico.webhook.secret", "");
            if (autoSecret != null && !autoSecret.trim().isEmpty()) {
                return autoSecret.trim();
            }
            String mpSecret = plugin.getConfig().getString("mercadopago.webhook.secret", "");
            if (mpSecret != null && !mpSecret.trim().isEmpty()) {
                return mpSecret.trim();
            }
        }
        return null;
    }

    private static void handleRequest(AutoPix plugin, HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }

            if (!OrderManager.isDatabaseReady() || OrderManager.isShuttingDown()) {
                sendResponse(exchange, 503, "{\"error\":\"service_unavailable\"}");
                return;
            }

            String xSignature = exchange.getRequestHeaders().getFirst("x-signature");
            String xRequestId = exchange.getRequestHeaders().getFirst("x-request-id");

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                byte[] bytes = is.readAllBytes();
                body = new String(bytes, StandardCharsets.UTF_8);
            }

            ParsedWebhookRequest parsed = parseWebhookRequest(exchange.getRequestURI(), body, xRequestId);
            String topic = parsed.getTopic();
            String dataId = parsed.getDataId();
            String notificationId = parsed.getNotificationId();

            if (dataId == null || dataId.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"missing_data_id\"}");
                return;
            }

            String secret = resolveWebhookSecret(plugin);
            if (secret == null || secret.isEmpty()) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("Webhook rejeitado: segredo de webhook nao configurado.");
                }
                sendResponse(exchange, 503, "{\"error\":\"webhook_secret_not_configured\"}");
                return;
            }

            if (!WebhookValidator.validateSignature(xSignature, parsed.getRequestId(), dataId, secret)) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().warning("Webhook rejeitado: assinatura criptografica invalida, expirada ou ausente.");
                }
                sendResponse(exchange, 401, "{\"error\":\"invalid_signature\"}");
                return;
            }

            if (topic != null && !topic.trim().isEmpty()) {
                String normalizedTopic = topic.trim().toLowerCase(Locale.ROOT);
                if (!normalizedTopic.equals("payment")
                        && !normalizedTopic.equals("payment.updated")
                        && !normalizedTopic.equals("payment.created")) {
                    sendResponse(exchange, 200, "{\"status\":\"ignored_topic\"}");
                    return;
                }
            }

            String effectiveIdempotencyKey = (notificationId != null && !notificationId.isEmpty())
                    ? "mp_notif_" + notificationId
                    : null;

            boolean processed = AutoPixAPI.processWebhookNotification(
                    topic, dataId, effectiveIdempotencyKey, xSignature, parsed.getRequestId(), secret);

            if (processed) {
                sendResponse(exchange, 200, "{\"status\":\"processed\"}");
            } else {
                sendResponse(exchange, 500, "{\"error\":\"processing_failed\"}");
            }
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
