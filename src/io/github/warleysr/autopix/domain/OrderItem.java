package io.github.warleysr.autopix.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Immutable order-item snapshot persisted when a cart is checked out.
 * Preserves full catalog, offer, pricing, currency, duration, and delivery metadata.
 */
public final class OrderItem {

    private final String productId;
    private final String offerId;
    private final String product;
    private final String description;
    private final String category;
    private final String offerType;
    private final String duration;
    private final String currency;
    private final int quantity;
    private final BigDecimal unitPriceDecimal;
    private final BigDecimal lineTotalDecimal;
    private final String deliveryType;
    private final String deliveryHandler;
    private final String deliverySnapshot;
    private final List<String> preCommands;
    private final List<String> commands;

    public OrderItem(String product, int quantity, float unitPrice,
                     List<String> preCommands, List<String> commands) {
        this(product, "default", product, null, "general", "one_time", null, "BRL",
                quantity,
                BigDecimal.valueOf(unitPrice).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP),
                "autopix", null, null, preCommands, commands);
    }

    public OrderItem(String productId, String offerId, String product, String category,
                     int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                     String deliveryType, String deliverySnapshot, String duration,
                     List<String> preCommands, List<String> commands) {
        this(productId, offerId, product, null, category, "one_time", duration, "BRL",
                quantity, unitPrice, lineTotal, deliveryType, null, deliverySnapshot, preCommands, commands);
    }

    public OrderItem(String productId, String offerId, String product, String description,
                     String category, String offerType, String duration, String currency,
                     int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                     String deliveryType, String deliveryHandler, String deliverySnapshot,
                     List<String> preCommands, List<String> commands) {
        if (product == null || product.trim().isEmpty()) {
            throw new IllegalArgumentException("O produto do pedido nao pode ser vazio.");
        }
        this.productId = productId != null && !productId.trim().isEmpty() ? productId.trim() : product.trim();
        this.offerId = offerId != null && !offerId.trim().isEmpty() ? offerId.trim() : "default";
        this.product = product.trim();
        this.description = description;
        this.category = category != null && !category.trim().isEmpty() ? category.trim() : "general";
        this.offerType = offerType != null && !offerType.trim().isEmpty() ? offerType.trim().toLowerCase() : "one_time";
        this.duration = duration != null && !duration.trim().isEmpty() ? duration.trim() : null;
        this.currency = currency != null && !currency.trim().isEmpty() ? currency.trim().toUpperCase() : "BRL";
        this.quantity = Math.max(1, quantity);
        this.unitPriceDecimal = unitPrice != null
                ? unitPrice.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.lineTotalDecimal = lineTotal != null
                ? lineTotal.setScale(2, RoundingMode.HALF_UP)
                : this.unitPriceDecimal.multiply(BigDecimal.valueOf(this.quantity)).setScale(2, RoundingMode.HALF_UP);
        this.deliveryType = deliveryType != null && !deliveryType.trim().isEmpty()
                ? deliveryType.trim().toLowerCase()
                : "autopix";
        this.deliveryHandler = deliveryHandler != null && !deliveryHandler.trim().isEmpty()
                ? deliveryHandler.trim()
                : null;
        this.preCommands = immutableCopy(preCommands);
        this.commands = immutableCopy(commands);
        this.deliverySnapshot = deliverySnapshot != null && !deliverySnapshot.trim().isEmpty()
                ? deliverySnapshot
                : buildCommercialSnapshotJson();
    }

    @SuppressWarnings("unchecked")
    private String buildCommercialSnapshotJson() {
        JSONObject obj = new JSONObject();
        obj.put("productId", productId);
        obj.put("offerId", offerId);
        obj.put("productName", product);
        obj.put("description", description);
        obj.put("category", category);
        obj.put("offerType", offerType);
        obj.put("duration", duration);
        obj.put("currency", currency);
        obj.put("quantity", quantity);
        obj.put("unitPrice", unitPriceDecimal.toPlainString());
        obj.put("lineTotal", lineTotalDecimal.toPlainString());
        obj.put("deliveryType", deliveryType);
        obj.put("deliveryHandler", deliveryHandler);
        JSONArray preArr = new JSONArray();
        preArr.addAll(preCommands);
        obj.put("preCommands", preArr);
        JSONArray cmdArr = new JSONArray();
        cmdArr.addAll(commands);
        obj.put("commands", cmdArr);
        return obj.toJSONString();
    }

    private static List<String> immutableCopy(List<String> source) {
        return source == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(source));
    }

    public String getProductId() { return productId; }
    public String getOfferId() { return offerId; }
    public String getProduct() { return product; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getOfferType() { return offerType; }
    public String getDuration() { return duration; }
    public String getCurrency() { return currency; }
    public int getQuantity() { return quantity; }
    public float getUnitPrice() { return unitPriceDecimal.floatValue(); }
    public BigDecimal getUnitPriceDecimal() { return unitPriceDecimal; }
    public float getLineTotal() { return lineTotalDecimal.floatValue(); }
    public BigDecimal getLineTotalDecimal() { return lineTotalDecimal; }
    public String getDeliveryType() { return deliveryType; }
    public String getDeliveryHandler() { return deliveryHandler; }
    public String getDeliverySnapshot() { return deliverySnapshot; }
    public List<String> getPreCommands() { return preCommands; }
    public List<String> getCommands() { return commands; }
}
