package io.github.warleysr.autopix.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import io.github.warleysr.autopix.domain.CartItem;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.inventory.InventoryManager;

/**
 * Per-player in-memory cart. Completed orders are persisted separately as
 * immutable order-item snapshots, so cart changes cannot alter an existing order.
 */
public final class CartManager {

    public static final int MAX_LINES = 45;
    public static final int MAX_QUANTITY = 64;

    private static final Map<UUID, CartState> CARTS = new ConcurrentHashMap<>();

    private CartManager() {
    }

    public static boolean add(Player player, String menu, int slot, int amount) {
        if (player == null || menu == null || amount <= 0) return false;
        OrderProduct product = InventoryManager.getOrderProduct(menu, slot);
        if (product == null || product.getPrice() < 0.01f) return false;
        String key = key(menu, slot);
        CartState cart = CARTS.computeIfAbsent(player.getUniqueId(), ignored -> new CartState());
        synchronized (cart) {
            CartItem current = cart.items.get(key);
            if (current == null && cart.items.size() >= MAX_LINES) return false;
            int quantity = Math.min(MAX_QUANTITY, (current == null ? 0 : current.getQuantity()) + amount);
            CartItem updated = current == null
                    ? new CartItem(key, menu, slot, product, quantity)
                    : current.withQuantity(quantity);
            cart.items.put(key, updated);
            cart.revision++;
            return true;
        }
    }

    public static boolean changeQuantity(Player player, String key, int delta) {
        if (player == null || key == null || delta == 0) return false;
        CartState cart = CARTS.get(player.getUniqueId());
        if (cart == null) return false;
        synchronized (cart) {
            CartItem current = cart.items.get(key);
            if (current == null) return false;
            int next = current.getQuantity() + delta;
            if (next <= 0) {
                cart.items.remove(key);
                if (cart.items.isEmpty()) clearCoupon(cart);
            } else {
                cart.items.put(key, current.withQuantity(Math.min(MAX_QUANTITY, next)));
            }
            cart.revision++;
            return true;
        }
    }

    public static boolean setQuantity(Player player, String key, int quantity) {
        if (player == null || key == null) return false;
        CartState cart = CARTS.get(player.getUniqueId());
        if (cart == null) return false;
        synchronized (cart) {
            CartItem current = cart.items.get(key);
            if (current == null) return false;
            if (quantity <= 0) {
                cart.items.remove(key);
                if (cart.items.isEmpty()) clearCoupon(cart);
            } else if (quantity != current.getQuantity()) {
                cart.items.put(key, current.withQuantity(Math.min(MAX_QUANTITY, quantity)));
            } else {
                return true;
            }
            cart.revision++;
            return true;
        }
    }

    public static boolean remove(Player player, String key) {
        if (player == null || key == null) return false;
        CartState cart = CARTS.get(player.getUniqueId());
        if (cart == null) return false;
        synchronized (cart) {
            boolean removed = cart.items.remove(key) != null;
            if (removed) {
                if (cart.items.isEmpty()) clearCoupon(cart);
                cart.revision++;
            }
            return removed;
        }
    }

    public static List<CartItem> getItems(Player player) {
        if (player == null) return java.util.Collections.emptyList();
        CartState cart = CARTS.get(player.getUniqueId());
        if (cart == null) return java.util.Collections.emptyList();
        synchronized (cart) {
            return new ArrayList<>(cart.items.values());
        }
    }

    public static List<OrderItem> createOrderSnapshot(Player player) {
        List<OrderItem> items = new ArrayList<>();
        for (CartItem item : getItems(player)) items.add(item.toOrderItem());
        return java.util.Collections.unmodifiableList(items);
    }

    public static BigDecimal getSubtotal(Player player) {
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : getItems(player)) {
            total = total.add(new BigDecimal(Float.toString(item.getUnitPrice()))
                    .multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal getTotal(Player player) {
        BigDecimal subtotal = getSubtotal(player);
        int discount = getDiscountPercent(player);
        if (discount > 0) {
            subtotal = subtotal.multiply(BigDecimal.valueOf(100L - discount))
                    .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
        }
        return subtotal.setScale(2, RoundingMode.HALF_UP);
    }

    public static int getItemCount(Player player) {
        int count = 0;
        for (CartItem item : getItems(player)) count += item.getQuantity();
        return count;
    }

    public static int getDiscountPercent(Player player) {
        CartState cart = player == null ? null : CARTS.get(player.getUniqueId());
        if (cart == null) return 0;
        synchronized (cart) { return cart.discountPercent; }
    }

    public static String getCouponName(Player player) {
        CartState cart = player == null ? null : CARTS.get(player.getUniqueId());
        if (cart == null) return null;
        synchronized (cart) { return cart.couponName; }
    }

    /**
     * Returns the same Mercado Pago idempotency key until cart contents or
     * discount change. A new cart gets a new identity after a successful checkout.
     */
    public static String getCheckoutIdempotencyKey(Player player) {
        if (player == null) return UUID.randomUUID().toString();
        CartState cart = CARTS.computeIfAbsent(player.getUniqueId(), ignored -> new CartState());
        synchronized (cart) {
            String material = cart.cartId + ":" + cart.revision;
            return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public static void applyDiscount(Player player, String couponName, int percent) {
        if (player == null) return;
        CartState cart = CARTS.computeIfAbsent(player.getUniqueId(), ignored -> new CartState());
        synchronized (cart) {
            cart.couponName = couponName;
            cart.discountPercent = Math.max(0, Math.min(99, percent));
            cart.revision++;
        }
    }

    public static void clearDiscount(Player player) {
        CartState cart = player == null ? null : CARTS.get(player.getUniqueId());
        if (cart == null) return;
        synchronized (cart) {
            if (cart.discountPercent != 0 || cart.couponName != null) {
                clearCoupon(cart);
                cart.revision++;
            }
        }
    }

    public static void clear(Player player) {
        if (player != null) CARTS.remove(player.getUniqueId());
    }

    public static void clearAll() {
        CARTS.clear();
    }

    public static String getSummary(Player player) {
        List<CartItem> items = getItems(player);
        if (items.isEmpty()) return "Carrinho vazio";
        if (items.size() == 1 && items.get(0).getQuantity() == 1) {
            return truncate(items.get(0).getProduct(), 64);
        }
        return "Carrinho (" + getItemCount(player) + " itens)";
    }

    public static String key(String menu, int slot) {
        return menu.toLowerCase(Locale.ROOT) + ":" + slot;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "Carrinho";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void clearCoupon(CartState cart) {
        cart.couponName = null;
        cart.discountPercent = 0;
    }

    private static final class CartState {
        private final Map<String, CartItem> items = new LinkedHashMap<>();
        private final UUID cartId = UUID.randomUUID();
        private long revision;
        private String couponName;
        private int discountPercent;
    }
}
