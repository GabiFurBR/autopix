package io.github.warleysr.autopix.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.inventory.ItemStack;

/**
 * Immutable snapshot of a product line currently held in a player's cart.
 */
public final class CartItem {

    private final String key;
    private final String menu;
    private final int slot;
    private final String product;
    private final float unitPrice;
    private final ItemStack icon;
    private final List<String> preCommands;
    private final List<String> commands;
    private final int quantity;

    public CartItem(String key, String menu, int slot, OrderProduct source, int quantity) {
        if (key == null || menu == null || source == null) {
            throw new IllegalArgumentException("Dados do item do carrinho invalidos.");
        }
        this.key = key;
        this.menu = menu;
        this.slot = slot;
        this.product = source.getProduct();
        this.unitPrice = source.getPrice();
        this.icon = source.getIcon() == null ? null : source.getIcon().clone();
        this.preCommands = immutableCopy(source.getPreCommands());
        this.commands = immutableCopy(source.getCommands());
        this.quantity = Math.max(1, quantity);
    }

    private CartItem(CartItem source, int quantity) {
        this.key = source.key;
        this.menu = source.menu;
        this.slot = source.slot;
        this.product = source.product;
        this.unitPrice = source.unitPrice;
        this.icon = source.icon == null ? null : source.icon.clone();
        this.preCommands = source.preCommands;
        this.commands = source.commands;
        this.quantity = Math.max(1, quantity);
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
    public String getProduct() { return product; }
    public float getUnitPrice() { return unitPrice; }
    public ItemStack getIcon() { return icon == null ? null : icon.clone(); }
    public List<String> getPreCommands() { return preCommands; }
    public List<String> getCommands() { return commands; }
    public int getQuantity() { return quantity; }

    public float getLineTotal() {
        return OrderManagerPrice.multiply(unitPrice, quantity);
    }

    public OrderItem toOrderItem() {
        return new OrderItem(product, quantity, unitPrice, preCommands, commands);
    }

    private static final class OrderManagerPrice {
        private static float multiply(float price, int quantity) {
            return java.math.BigDecimal.valueOf(Double.parseDouble(Float.toString(price)))
                    .multiply(java.math.BigDecimal.valueOf(quantity))
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .floatValue();
        }
    }
}
