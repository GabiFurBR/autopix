package io.github.warleysr.autopix.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Immutable snapshot of a product and offer line currently held in a player's cart.
 * Preserves full catalog, offer, duration, pricing, currency, and delivery configuration.
 */
public final class CartItem {

    private final String key;
    private final String menu;
    private final int slot;
    private final String productId;
    private final String offerId;
    private final String product;
    private final String description;
    private final String category;
    private final String offerType;
    private final String duration;
    private final String currency;
    private final BigDecimal unitPriceDecimal;
    private final String deliveryType;
    private final String deliveryHandler;
    private final String commercialSnapshot;
    private final ItemStack icon;
    private final List<String> preCommands;
    private final List<String> commands;
    private final int quantity;

    public CartItem(String key, String menu, int slot, OrderProduct source, int quantity) {
        this(key, menu, slot,
                source != null ? source.getProduct() : "product",
                "default",
                source != null ? source.getProduct() : "product",
                null,
                menu != null ? menu : "general",
                "one_time",
                null,
                "BRL",
                source != null ? BigDecimal.valueOf(source.getPrice()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                "autopix",
                null,
                null,
                source != null ? source.getIcon() : null,
                source != null ? source.getPreCommands() : null,
                source != null ? source.getCommands() : null,
                quantity);
    }

    public CartItem(String key, String menu, int slot, String productId, String offerId, String product,
                    BigDecimal unitPrice, ItemStack icon, List<String> preCommands, List<String> commands, int quantity) {
        this(key, menu, slot, productId, offerId, product, null, "general", "one_time", null, "BRL",
                unitPrice, "autopix", null, null, icon, preCommands, commands, quantity);
    }

    public CartItem(String key, String menu, int slot, String productId, String offerId, String product,
                    String description, String category, String offerType, String duration, String currency,
                    BigDecimal unitPrice, String deliveryType, String deliveryHandler, String commercialSnapshot,
                    ItemStack icon, List<String> preCommands, List<String> commands, int quantity) {
        if (key == null || product == null) {
            throw new IllegalArgumentException("Dados do item do carrinho invalidos.");
        }
        this.key = key;
        this.menu = menu != null ? menu : "catalog";
        this.slot = slot;
        this.productId = productId != null && !productId.trim().isEmpty() ? productId.trim() : product.trim();
        this.offerId = offerId != null && !offerId.trim().isEmpty() ? offerId.trim() : "default";
        this.product = product.trim();
        this.description = description;
        this.category = category != null && !category.trim().isEmpty() ? category.trim() : "general";
        this.offerType = offerType != null && !offerType.trim().isEmpty() ? offerType.trim().toLowerCase() : "one_time";
        this.duration = duration != null && !duration.trim().isEmpty() ? duration.trim() : null;
        this.currency = currency != null && !currency.trim().isEmpty() ? currency.trim().toUpperCase() : "BRL";
        this.unitPriceDecimal = unitPrice != null
                ? unitPrice.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.deliveryType = deliveryType != null && !deliveryType.trim().isEmpty()
                ? deliveryType.trim().toLowerCase()
                : "autopix";
        this.deliveryHandler = deliveryHandler != null && !deliveryHandler.trim().isEmpty()
                ? deliveryHandler.trim()
                : null;
        this.icon = icon == null ? null : icon.clone();
        this.preCommands = immutableCopy(preCommands);
        this.commands = immutableCopy(commands);
        this.quantity = Math.max(1, quantity);
        this.commercialSnapshot = commercialSnapshot != null && !commercialSnapshot.trim().isEmpty()
                ? commercialSnapshot
                : buildCommercialSnapshotJson();
    }

    private CartItem(CartItem source, int quantity) {
        this.key = source.key;
        this.menu = source.menu;
        this.slot = source.slot;
        this.productId = source.productId;
        this.offerId = source.offerId;
        this.product = source.product;
        this.description = source.description;
        this.category = source.category;
        this.offerType = source.offerType;
        this.duration = source.duration;
        this.currency = source.currency;
        this.unitPriceDecimal = source.unitPriceDecimal;
        this.deliveryType = source.deliveryType;
        this.deliveryHandler = source.deliveryHandler;
        this.icon = source.icon == null ? null : source.icon.clone();
        this.preCommands = source.preCommands;
        this.commands = source.commands;
        this.quantity = Math.max(1, quantity);
        this.commercialSnapshot = buildCommercialSnapshotJson();
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
        obj.put("lineTotal", getLineTotalDecimal().toPlainString());
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

    public CartItem withQuantity(int quantity) {
        return new CartItem(this, quantity);
    }

    public String getKey() { return key; }
    public String getMenu() { return menu; }
    public int getSlot() { return slot; }
    public String getProductId() { return productId; }
    public String getOfferId() { return offerId; }
    public String getProduct() { return product; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getOfferType() { return offerType; }
    public String getDuration() { return duration; }
    public String getCurrency() { return currency; }
    public float getUnitPrice() { return unitPriceDecimal.floatValue(); }
    public BigDecimal getUnitPriceDecimal() { return unitPriceDecimal; }
    public String getDeliveryType() { return deliveryType; }
    public String getDeliveryHandler() { return deliveryHandler; }
    public String getCommercialSnapshot() { return commercialSnapshot; }
    public ItemStack getIcon() { return icon == null ? null : icon.clone(); }
    public List<String> getPreCommands() { return preCommands; }
    public List<String> getCommands() { return commands; }
    public int getQuantity() { return quantity; }

    public float getLineTotal() {
        return getLineTotalDecimal().floatValue();
    }

    public BigDecimal getLineTotalDecimal() {
        return unitPriceDecimal.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public OrderItem toOrderItem() {
        return new OrderItem(
                productId,
                offerId,
                product,
                description,
                category,
                offerType,
                duration,
                currency,
                quantity,
                unitPriceDecimal,
                getLineTotalDecimal(),
                deliveryType,
                deliveryHandler,
                commercialSnapshot,
                preCommands,
                commands);
    }
}
