package io.github.warleysr.autopix.mercadopago;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.PaymentInfo;
import io.github.warleysr.autopix.domain.PixData;

public class MercadoPagoAPI {

    private static final String API_URL = "https://api.mercadopago.com/v1/payments";

    @FunctionalInterface
    public interface PaymentLookupProvider {
        PaymentInfo getPayment(AutoPix ap, String paymentId) throws Exception;
    }

    @FunctionalInterface
    public interface PaymentCreationProvider {
        PixData createPayment(AutoPix ap, String playerName, String productName, BigDecimal price,
                              String externalReference, String idempotencyKey) throws Exception;
    }

    private static volatile PaymentLookupProvider lookupOverride;
    private static volatile PaymentCreationProvider creationOverride;

    public static void setPaymentLookupOverride(PaymentLookupProvider provider) {
        lookupOverride = provider;
    }

    public static void setPaymentCreationOverride(PaymentCreationProvider provider) {
        creationOverride = provider;
    }

    public static void clearTestOverrides() {
        lookupOverride = null;
        creationOverride = null;
    }

    @SuppressWarnings("unchecked")
    public static String buildCreatePaymentJson(String playerName, String productName,
                                                float price, String notificationUrl) {
        if (!Float.isFinite(price)) {
            throw new IllegalArgumentException("O valor do pagamento deve ser finito.");
        }
        BigDecimal roundedPrice = new BigDecimal(Float.toString(price));
        return buildCreatePaymentJson(playerName, productName, roundedPrice, playerName, notificationUrl);
    }

    @SuppressWarnings("unchecked")
    public static String buildCreatePaymentJson(String playerName, String productName,
                                                BigDecimal price, String externalReference,
                                                String notificationUrl) {
        if (price == null || price.compareTo(new BigDecimal("0.01")) < 0 || price.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("O valor do pagamento deve ser positivo e possuir no maximo 2 casas decimais.");
        }
        JSONObject payer = new JSONObject();
        payer.put("entity_type", "individual");
        payer.put("type", "customer");
        String safeUser = (playerName != null ? playerName.replaceAll("[^a-zA-Z0-9._-]", "") : "player");
        if (safeUser.isEmpty()) safeUser = "player";
        payer.put("email", safeUser + "@autopix.com");

        BigDecimal roundedPrice = price.setScale(2, RoundingMode.UNNECESSARY);

        JSONObject root = new JSONObject();
        root.put("description", productName != null ? productName : "Produto");
        root.put("payer", payer);
        root.put("external_reference", externalReference != null && !externalReference.trim().isEmpty()
                ? externalReference.trim()
                : (playerName != null ? playerName : "player"));
        root.put("payment_method_id", "pix");
        root.put("transaction_amount", roundedPrice);

        if (notificationUrl != null) {
            String trimmedUrl = notificationUrl.trim();
            if (trimmedUrl.startsWith("https://")) {
                root.put("notification_url", trimmedUrl);
            }
        }

        return root.toJSONString();
    }

    public static PixData createPixPayment(AutoPix ap, Player p, OrderProduct product, float price) throws Exception {
        if (product == null || p == null) return null;
        return createPixPayment(ap, p.getName(), product.getProduct(), price);
    }

    public static PixData createPixPayment(AutoPix ap, Player p, String productName, float price) throws Exception {
        return p == null ? null : createPixPayment(ap, p.getName(), productName, price);
    }

    public static PixData createPixPayment(AutoPix ap, String playerName, String productName, float price) throws Exception {
        return createPixPayment(ap, playerName, productName, price, UUID.randomUUID().toString());
    }

    public static PixData createPixPayment(AutoPix ap, String playerName, String productName,
                                           float price, String idempotencyKey) throws Exception {
        if (!Float.isFinite(price) || price < 0.01f) return null;
        BigDecimal bdPrice = new BigDecimal(Float.toString(price));
        return createPixPayment(ap, playerName, productName, bdPrice, playerName, idempotencyKey);
    }

    public static PixData createPixPayment(AutoPix ap, String playerName, String productName,
                                           BigDecimal price, String idempotencyKey) throws Exception {
        return createPixPayment(ap, playerName, productName, price, playerName, idempotencyKey);
    }

    public static PixData createPixPayment(AutoPix ap, String playerName, String productName,
                                           BigDecimal price, String externalReference,
                                           String idempotencyKey) throws Exception {
        if (playerName == null || productName == null || productName.trim().isEmpty()
                || price == null || price.compareTo(new BigDecimal("0.01")) < 0
                || price.stripTrailingZeros().scale() > 2) {
            return null;
        }

        BigDecimal normalizedPrice = price.setScale(2, RoundingMode.UNNECESSARY);

        PaymentCreationProvider override = creationOverride;
        if (override != null) {
            return override.createPayment(ap, playerName, productName,
                    normalizedPrice, externalReference, idempotencyKey);
        }

        if (ap == null) return null;
        String notificationUrl = ap.getConfig().getString("automatico.notificacoes", null);
        String jsonBody = buildCreatePaymentJson(playerName, productName, normalizedPrice, externalReference, notificationUrl);

        HttpURLConnection connection = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + ap.getConfig().getString("token-mp", "").trim());
            String safeIdempotencyKey = idempotencyKey != null && !idempotencyKey.trim().isEmpty()
                    ? idempotencyKey.trim() : UUID.randomUUID().toString();
            connection.setRequestProperty("X-Idempotency-Key", safeIdempotencyKey);

            try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
                outputStream.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode);

            if (statusCode != 201) {
                String apiError = extractSafeErrorSummary(responseBody);
                ap.getLogger().warning("Falha ao criar cobranca PIX no Mercado Pago (HTTP "
                        + statusCode + "): " + apiError);
                return null;
            }

            return parseCreatedPixData(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    public static PixData parseCreatedPixData(String responseBody) throws Exception {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return null;
        }
        JSONObject json = (JSONObject) new JSONParser().parse(responseBody);
        Object idObj = json.get("id");
        if (idObj == null) return null;
        String paymentId = String.valueOf(idObj);

        JSONObject poi = (JSONObject) json.get("point_of_interaction");
        if (poi == null) return null;
        JSONObject txData = (JSONObject) poi.get("transaction_data");
        if (txData == null) return null;
        String qr = (String) txData.get("qr_code");
        if (qr == null || qr.trim().isEmpty()) return null;

        return new PixData(paymentId, qr);
    }

    public static PaymentInfo getPayment(AutoPix ap, String id) {
        if (id == null || id.trim().isEmpty()) return null;

        PaymentLookupProvider override = lookupOverride;
        if (override != null) {
            try {
                return override.getPayment(ap, id.trim());
            } catch (Exception ex) {
                if (ap != null) {
                    ap.getLogger().warning("Erro ao consultar pagamento #" + id + " no provedor: " + ex.getMessage());
                }
                return null;
            }
        }

        if (ap == null) return null;
        HttpURLConnection connection = null;
        try {
            int intervalMs = Math.max(2000, ap.getConfig().getInt("automatico.intervalo", 10) * 1000 - 500);
            int timeoutMs = Math.min(8000, intervalMs);

            connection = (HttpURLConnection) URI.create(API_URL + "/" + id.trim()).toURL().openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + ap.getConfig().getString("token-mp", "").trim());

            int statusCode = connection.getResponseCode();
            if (statusCode != 200) {
                return null;
            }
            String responseBody = readBody(connection, statusCode);
            return parsePaymentInfo(id.trim(), responseBody);
        } catch (Exception e) {
            ap.getLogger().warning("Erro ao consultar pagamento #" + id + " no Mercado Pago: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    public static PaymentInfo parsePaymentInfo(String paymentId, String responseBody) throws Exception {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return null;
        }
        JSONObject json = (JSONObject) new JSONParser().parse(responseBody);
        Object statusObj = json.get("status");
        if (statusObj == null) return null;
        String status = String.valueOf(statusObj);

        String pixId = null;
        BigDecimal paid = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        JSONObject details = (JSONObject) json.get("transaction_details");
        if (details != null) {
            Object paidObj = details.get("total_paid_amount");
            if (paidObj != null) {
                try {
                    paid = new BigDecimal(String.valueOf(paidObj)).setScale(2, RoundingMode.HALF_UP);
                } catch (NumberFormatException ignored) {
                }
            }
            Object rawTxId = details.get("transaction_id");
            if (rawTxId != null) {
                String txStr = String.valueOf(rawTxId).trim();
                if (txStr.startsWith("PIX") && txStr.length() > 3) {
                    pixId = txStr.substring(3);
                } else if (!txStr.isEmpty()) {
                    pixId = txStr;
                }
            }
        }

        if (paid.compareTo(BigDecimal.ZERO) <= 0) {
            Object amountObj = json.get("transaction_amount");
            if (amountObj != null) {
                try {
                    paid = new BigDecimal(String.valueOf(amountObj)).setScale(2, RoundingMode.HALF_UP);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String currencyId = "BRL";
        Object curObj = json.get("currency_id");
        if (curObj != null && !String.valueOf(curObj).trim().isEmpty()) {
            currencyId = String.valueOf(curObj).trim().toUpperCase();
        }

        String externalReference = null;
        Object extRefObj = json.get("external_reference");
        if (extRefObj != null && !String.valueOf(extRefObj).trim().isEmpty()) {
            externalReference = String.valueOf(extRefObj).trim();
        }

        if ("approved".equalsIgnoreCase(status) && (pixId == null || pixId.isEmpty())) {
            if (paymentId != null && !paymentId.trim().isEmpty()) {
                pixId = "MP-" + paymentId.trim();
            }
        }

        return new PaymentInfo(paymentId, pixId, status, paid, currencyId, externalReference);
    }

    public static boolean cancelPayment(AutoPix ap, String paymentId) throws Exception {
        if (paymentId == null || paymentId.trim().isEmpty()) return false;
        if (ap == null) return false;

        String jsonBody = "{\"status\":\"cancelled\"}";
        HttpURLConnection connection = (HttpURLConnection) URI.create(API_URL + "/" + paymentId.trim()).toURL().openConnection();
        try {
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + ap.getConfig().getString("token-mp", "").trim());

            try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
                outputStream.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode);
            boolean success = statusCode == 200;

            if (!success) {
                ap.getLogger().warning("Nao foi possivel cancelar o pagamento #" + paymentId
                        + " no Mercado Pago (HTTP " + statusCode + "): " + extractSafeErrorSummary(responseBody));
            }
            return success;
        } finally {
            connection.disconnect();
        }
    }

    public static String readBody(HttpURLConnection connection, int statusCode) {
        if (connection == null) return "";
        InputStream stream = null;
        try {
            stream = (statusCode >= 200 && statusCode < 400)
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (stream == null) return "";

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            return response.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String extractSafeErrorSummary(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return "sem detalhes retornados pela API";
        }
        try {
            JSONObject json = (JSONObject) new JSONParser().parse(responseBody);
            Object message = json.get("message");
            Object error = json.get("error");
            if (message != null && error != null) {
                return error + " - " + message;
            }
            if (message != null) {
                return String.valueOf(message);
            }
            if (error != null) {
                return String.valueOf(error);
            }
        } catch (Exception ignored) {
        }
        return "resposta inesperada da API";
    }
}
