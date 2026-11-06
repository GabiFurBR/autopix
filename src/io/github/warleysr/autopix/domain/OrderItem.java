package io.github.warleysr.autopix.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable order-item snapshot persisted when a cart is checked out.
 */
public final class OrderItem {

    private final String product;
    private final int quantity;
    private final float unitPrice;
    private final List<String> preCommands;
    private final List<String> commands;

    public OrderItem(String product, int quantity, float unitPrice,
                     List<String> preCommands, List<String> commands) {
        if (product == null || product.trim().isEmpty()) {
            throw new IllegalArgumentException("O produto do pedido nao pode ser vazio.");
        }
        this.product = product;
        this.quantity = Math.max(1, quantity);
        this.unitPrice = unitPrice;
        this.preCommands = immutableCopy(preCommands);
        this.commands = immutableCopy(commands);
    }

    private static List<String> immutableCopy(List<String> source) {
        return source == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(source));
    }

    public String getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public float getUnitPrice() { return unitPrice; }
    public float getLineTotal() {
        return java.math.BigDecimal.valueOf(Double.parseDouble(Float.toString(unitPrice)))
                .multiply(java.math.BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP).floatValue();
    }
    public List<String> getPreCommands() { return preCommands; }
    public List<String> getCommands() { return commands; }
}
