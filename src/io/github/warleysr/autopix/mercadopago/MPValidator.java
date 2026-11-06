package io.github.warleysr.autopix.mercadopago;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.inventory.InventoryManager;

public class MPValidator {

    private static final String API_URL = "https://api.mercadopago.com/v1/payments/search";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_SEARCH_OFFSET = 10000;

    private static final Set<String> VALIDATING_TXIDS = ConcurrentHashMap.newKeySet();

    public static void validateTransaction(AutoPix ap, String txid, Player p) {
        if (ap == null || txid == null || p == null) return;
        String normalizedTxId = txid.trim();
        String normalizedLookupId = normalizedTxId.toUpperCase(Locale.ROOT);

        if (!VALIDATING_TXIDS.add(normalizedLookupId)) {
            MSG.sendMessage(p, "processando-pedido");
            return;
        }

        try {
            OrderManager.TransactionCheck check = OrderManager.checkTransactionStatus(normalizedTxId);
            if (check == OrderManager.TransactionCheck.ALREADY_VALIDATED) {
                MSG.sendMessage(p, "ja-validado");
                SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                return;
            }
            if (check == OrderManager.TransactionCheck.DB_ERROR) {
                MSG.sendMessage(p, "erro-validar");
                SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                return;
            }

            List<Order> orders = OrderManager.getOrders(p.getName());
            if (orders.isEmpty()) {
                MSG.sendMessage(p, "pix-nao-validado");
                SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                return;
            }

            // Mercado Pago returns up to 50 payments per page. Continue through
            // subsequent pages instead of silently ignoring older valid PIX payments.
            for (int offset = 0; offset <= MAX_SEARCH_OFFSET; offset += PAGE_SIZE) {
                HttpURLConnection connection = null;
                int statusCode;
                String responseBody;

                try {
                    String url = API_URL + "?sort=date_created&criteria=desc&limit="
                            + PAGE_SIZE + "&offset=" + offset;
                    connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(10000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Authorization",
                            "Bearer " + ap.getConfig().getString("token-mp", "").trim());

                    statusCode = connection.getResponseCode();
                    responseBody = MercadoPagoAPI.readBody(connection, statusCode);
                } finally {
                    if (connection != null) connection.disconnect();
                }

                if (statusCode != 200) {
                    ap.getLogger().warning("Falha ao buscar transacoes no Mercado Pago (HTTP "
                            + statusCode + "): " + MercadoPagoAPI.extractSafeErrorSummary(responseBody));
                    MSG.sendMessage(p, "erro-validar");
                    SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                    return;
                }

                JSONObject json = (JSONObject) new JSONParser().parse(responseBody);
                JSONArray results = (JSONArray) json.get("results");
                if (results == null || results.isEmpty()) break;

                for (Object resElem : results) {
                    if (!(resElem instanceof JSONObject)) continue;
                    JSONObject paymentJson = (JSONObject) resElem;

                    String status = paymentJson.get("status") != null
                            ? String.valueOf(paymentJson.get("status")) : "";
                    if (!"approved".equalsIgnoreCase(status)) continue;

                    JSONObject details = (JSONObject) paymentJson.get("transaction_details");
                    if (details == null || details.get("transaction_id") == null) continue;

                    String transaction = String.valueOf(details.get("transaction_id")).trim();
                    String extractedTxId = transaction.regionMatches(true, 0, "PIX", 0, 3)
                            && transaction.length() > 3
                            ? transaction.substring(3) : transaction;
                    if (!extractedTxId.equalsIgnoreCase(normalizedLookupId)) continue;

                    Object paidObj = details.get("total_paid_amount");
                    if (!(paidObj instanceof Number)) continue;
                    double paid = ((Number) paidObj).doubleValue();
                    String paymentId = paymentJson.get("id") != null
                            ? String.valueOf(paymentJson.get("id")) : null;

                    for (Order order : orders) {
                        if (order.isValidated()) continue;
                        // Automatic PIX orders already have a Mercado Pago payment ID;
                        // manual QR orders are the ones without a pix_data row.
                        if (OrderManager.getPixData(order) != null) continue;
                        List<OrderItem> orderItems = OrderManager.getOrderItems(order.getId());
                        OrderProduct op = orderItems.isEmpty()
                                ? InventoryManager.getProductByOrder(order) : null;
                        if (orderItems.isEmpty() && op == null) continue;
                        if (OrderManager.toCents(order.getPrice()) != OrderManager.toCents(paid)) continue;

                        if (OrderManager.approveManualOrder(order, normalizedTxId)) {
                            if (orderItems.isEmpty()) {
                                OrderManager.deliverApprovedOrderOnMainThread(
                                        ap, order, op, paymentId, normalizedTxId);
                            } else {
                                OrderManager.deliverApprovedOrderOnMainThread(
                                        ap, order, orderItems, paymentId, normalizedTxId);
                            }
                        } else {
                            MSG.sendMessage(p, "erro-validar");
                            SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                        }
                        return;
                    }
                }

                if (results.size() < PAGE_SIZE) break;
            }

            MSG.sendMessage(p, "pix-nao-validado");
            SoundManager.play(p, SoundManager.PAYMENT_REJECTED);

        } catch (Exception e) {
            ap.getLogger().warning("Erro na validacao manual do PIX para " + p.getName() + ": " + e.getMessage());
            MSG.sendMessage(p, "erro-validar");
            SoundManager.play(p, SoundManager.CONNECTION_ERROR);
        } finally {
            VALIDATING_TXIDS.remove(normalizedLookupId);
        }
    }
}