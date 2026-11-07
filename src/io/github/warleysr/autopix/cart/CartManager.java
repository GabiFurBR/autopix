package io.github.warleysr.autopix.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.api.Cart;
import io.github.warleysr.autopix.api.CartResult;
import io.github.warleysr.autopix.catalog.CatalogManager;
import io.github.warleysr.autopix.catalog.DeliveryConfig;
import io.github.warleysr.autopix.catalog.Offer;
import io.github.warleysr.autopix.catalog.Product;
import io.github.warleysr.autopix.domain.CartItem;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.CartUpdatedEvent;
import io.github.warleysr.autopix.inventory.InventoryManager;

/**
 * Manages player carts with persistent database backing and in-memory caching.
 * Preserves full product, offer, duration, pricing, and delivery metadata across restarts.
 */
public final class CartManager {

    public static final int MAX_LINES = 45;
    public static final int MAX_QUANTITY = 64;

    private static final Map<UUID, CartState> CARTS = new ConcurrentHashMap<>();

    private CartManager() {
    }

    public static void preloadCartAsync(UUID uuid) {
        if (uuid == null || CARTS.containsKey(uuid)) return;
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null && plugin.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!OrderManager.isDatabaseReady()) return;
                CartState loaded = loadFromDatabase(uuid);
                CARTS.putIfAbsent(uuid, loaded);
            });
        }
    }

    public static Cart getCart(UUID uuid) {
        if (uuid == null) {
            return new CartImpl(null, Collections.emptyList(), null, 0);
        }
        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            return toCartSnapshot(uuid, cart);
        }
    }

    public static CartResult addToCart(UUID uuid, String productId, String offerId, int quantity) {
        if (uuid == null) return CartResult.failure("UUID inválido.", null);
        if (quantity <= 0) return CartResult.failure("Quantidade deve ser maior que zero.", getCart(uuid));
        if (productId == null || productId.trim().isEmpty()) {
            return CartResult.failure("ID do produto inválido.", getCart(uuid));
        }

        Product product = CatalogManager.getProduct(productId);
        if (product == null || !product.isEnabled()) {
            return CartResult.failure("Produto '" + productId + "' não encontrado ou indisponível no catálogo.", getCart(uuid));
        }

        String requestedOfferId = (offerId != null && !offerId.trim().isEmpty())
                ? offerId.trim().toLowerCase(Locale.ROOT)
                : null;

        Offer offer = null;
        String resolvedOfferId;
        BigDecimal unitPrice;
        String offerType = "one_time";
        String duration = null;

        if (product.hasOffers()) {
            if (requestedOfferId == null) {
                offer = product.getOffer("default");
                if (offer == null) {
                    return CartResult.failure("Selecione uma oferta válida para o produto '" + product.getName() + "'.", getCart(uuid));
                }
            } else {
                offer = product.getOffer(requestedOfferId);
                if (offer == null) {
                    return CartResult.failure("A oferta '" + offerId + "' não existe para o produto '" + product.getName() + "'.", getCart(uuid));
                }
            }

            if (!offer.isEnabled()) {
                return CartResult.failure("A oferta '" + offer.getId() + "' está indisponível no momento.", getCart(uuid));
            }

            unitPrice = offer.getPrice();
            if (unitPrice == null || unitPrice.compareTo(new BigDecimal("0.01")) < 0) {
                return CartResult.failure("A oferta '" + offer.getId() + "' possui preço inválido.", getCart(uuid));
            }
            resolvedOfferId = offer.getId();
            offerType = offer.getType() != null ? offer.getType().name().toLowerCase(Locale.ROOT) : "one_time";
            duration = offer.getDurationString();
        } else {
            if (requestedOfferId != null && !"default".equalsIgnoreCase(requestedOfferId)) {
                return CartResult.failure("A oferta '" + offerId + "' não existe para o produto '" + product.getName() + "'.", getCart(uuid));
            }
            unitPrice = product.getPrice();
            if (unitPrice == null || unitPrice.compareTo(new BigDecimal("0.01")) < 0) {
                return CartResult.failure("O produto '" + product.getName() + "' possui preço inválido (R$ 0,00).", getCart(uuid));
            }
            resolvedOfferId = "default";
        }

        DeliveryConfig deliv = product.getDelivery();
        String deliveryType = deliv != null && deliv.getType() != null
                ? deliv.getType().name().toLowerCase(Locale.ROOT)
                : "autopix";
        String deliveryHandler = deliv != null ? deliv.getHandler() : null;
        List<String> preCmds = deliv != null ? deliv.getPreCommands() : Collections.emptyList();
        List<String> rawCmds = deliv != null ? deliv.getCommands() : Collections.emptyList();
        List<String> cmds = adaptCommandsForOfferDuration(rawCmds, duration);
        ItemStack icon = null;

        String itemKey = (product.getId() + ":" + resolvedOfferId).toLowerCase(Locale.ROOT);

        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            CartItem current = cart.items.get(itemKey);
            if (current == null && cart.items.size() >= MAX_LINES) {
                return CartResult.failure("Limite de linhas do carrinho atingido (máximo " + MAX_LINES + ").", toCartSnapshot(uuid, cart));
            }

            int newQty = Math.min(MAX_QUANTITY, (current == null ? 0 : current.getQuantity()) + quantity);

            CartItem updated = new CartItem(
                    itemKey,
                    "catalog",
                    0,
                    product.getId(),
                    resolvedOfferId,
                    product.getName(),
                    product.getDescription(),
                    product.getCategory(),
                    offerType,
                    duration,
                    "BRL",
                    unitPrice,
                    deliveryType,
                    deliveryHandler,
                    null,
                    icon,
                    preCmds,
                    cmds,
                    newQty);

            cart.items.put(itemKey, updated);
            cart.revision++;
            persistCartAsync(uuid, cart);
            Cart snapshot = toCartSnapshot(uuid, cart);
            fireCartUpdated(uuid, snapshot, "add");
            return CartResult.success("Produto adicionado ao carrinho com sucesso.", snapshot);
        }
    }

    private static List<String> adaptCommandsForOfferDuration(List<String> commands, String duration) {
        if (commands == null || commands.isEmpty()) return Collections.emptyList();
        if (duration == null || duration.trim().isEmpty()) return commands;
        String dur = duration.trim();
        List<String> adapted = new ArrayList<>(commands.size());
        for (String cmd : commands) {
            if (cmd == null) continue;
            String updated = cmd.replace("{duracao}", dur);
            if (updated.matches("(?i).*\\baddtemp\\s+\\S+\\s+\\d+[dhms]$")) {
                updated = updated.replaceFirst("(?i)(\\baddtemp\\s+\\S+\\s+)\\d+[dhms]$", "$1" + dur);
            }
            adapted.add(updated);
        }
        return adapted;
    }

    public static CartResult removeFromCart(UUID uuid, String productId, String offerId, int quantity) {
        if (uuid == null) return CartResult.failure("UUID inválido.", null);
        if (productId == null) return CartResult.failure("Produto inválido.", getCart(uuid));
        String resolvedOffer = offerId != null ? offerId : "default";
        String itemKey = (productId + ":" + resolvedOffer).toLowerCase(Locale.ROOT);

        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            CartItem current = cart.items.get(itemKey);
            if (current == null) {
                for (String k : cart.items.keySet()) {
                    if (k.startsWith(productId.toLowerCase(Locale.ROOT) + ":")) {
                        itemKey = k;
                        current = cart.items.get(k);
                        break;
                    }
                }
            }

            if (current == null) {
                return CartResult.failure("Item não encontrado no carrinho.", toCartSnapshot(uuid, cart));
            }

            if (quantity <= 0 || quantity >= current.getQuantity()) {
                cart.items.remove(itemKey);
                if (cart.items.isEmpty()) clearCoupon(cart);
            } else {
                cart.items.put(itemKey, current.withQuantity(current.getQuantity() - quantity));
            }

            cart.revision++;
            persistCartAsync(uuid, cart);
            Cart snapshot = toCartSnapshot(uuid, cart);
            fireCartUpdated(uuid, snapshot, "remove");
            return CartResult.success("Item removido do carrinho.", snapshot);
        }
    }

    public static CartResult clearCart(UUID uuid) {
        if (uuid == null) return CartResult.failure("UUID inválido.", null);
        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            cart.items.clear();
            clearCoupon(cart);
            cart.revision++;
            persistCartAsync(uuid, cart);
            Cart snapshot = toCartSnapshot(uuid, cart);
            fireCartUpdated(uuid, snapshot, "clear");
            return CartResult.success("Carrinho esvaziado com sucesso.", snapshot);
        }
    }

    public static CartResult applyCoupon(UUID uuid, String couponCode) {
        if (uuid == null) return CartResult.failure("UUID inválido.", null);
        if (couponCode == null || couponCode.trim().isEmpty()) {
            return CartResult.failure("Código de cupom inválido.", getCart(uuid));
        }

        int percent = validateCouponPercent(couponCode.trim());
        if (percent <= 0) {
            return CartResult.failure("Cupom inválido ou expirado.", getCart(uuid));
        }

        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            cart.couponName = couponCode.trim();
            cart.discountPercent = percent;
            cart.revision++;
            persistCartAsync(uuid, cart);
            Cart snapshot = toCartSnapshot(uuid, cart);
            fireCartUpdated(uuid, snapshot, "apply_coupon");
            return CartResult.success("Cupom de " + percent + "% aplicado com sucesso!", snapshot);
        }
    }

    public static CartResult removeCoupon(UUID uuid) {
        if (uuid == null) return CartResult.failure("UUID inválido.", null);
        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            clearCoupon(cart);
            cart.revision++;
            persistCartAsync(uuid, cart);
            Cart snapshot = toCartSnapshot(uuid, cart);
            fireCartUpdated(uuid, snapshot, "remove_coupon");
            return CartResult.success("Cupom removido com sucesso.", snapshot);
        }
    }

    // =========================================================================
    // Legacy / Bukkit Menu Compatibility Methods
    // =========================================================================

    public static boolean add(Player player, String menu, int slot, int amount) {
        if (player == null || menu == null || amount <= 0) return false;
        OrderProduct product = InventoryManager.getOrderProduct(menu, slot);
        if (product == null || product.getPrice() < 0.01f) return false;
        String key = key(menu, slot);

        CartState cart = getOrCreateState(player.getUniqueId());
        synchronized (cart) {
            CartItem current = cart.items.get(key);
            if (current == null && cart.items.size() >= MAX_LINES) return false;
            int quantity = Math.min(MAX_QUANTITY, (current == null ? 0 : current.getQuantity()) + amount);
            CartItem updated = current == null
                    ? new CartItem(key, menu, slot, product, quantity)
                    : current.withQuantity(quantity);
            cart.items.put(key, updated);
            cart.revision++;
            persistCartAsync(player.getUniqueId(), cart);
            fireCartUpdated(player.getUniqueId(), toCartSnapshot(player.getUniqueId(), cart), "add");
            return true;
        }
    }

    public static boolean changeQuantity(Player player, String key, int delta) {
        if (player == null || key == null || delta == 0) return false;
        CartState cart = getOrCreateState(player.getUniqueId());
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
            persistCartAsync(player.getUniqueId(), cart);
            fireCartUpdated(player.getUniqueId(), toCartSnapshot(player.getUniqueId(), cart), "update");
            return true;
        }
    }

    public static boolean setQuantity(Player player, String key, int quantity) {
        if (player == null || key == null) return false;
        CartState cart = getOrCreateState(player.getUniqueId());
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
            persistCartAsync(player.getUniqueId(), cart);
            fireCartUpdated(player.getUniqueId(), toCartSnapshot(player.getUniqueId(), cart), "update");
            return true;
        }
    }

    public static boolean remove(Player player, String key) {
        if (player == null || key == null) return false;
        CartState cart = getOrCreateState(player.getUniqueId());
        synchronized (cart) {
            boolean removed = cart.items.remove(key) != null;
            if (removed) {
                if (cart.items.isEmpty()) clearCoupon(cart);
                cart.revision++;
                persistCartAsync(player.getUniqueId(), cart);
                fireCartUpdated(player.getUniqueId(), toCartSnapshot(player.getUniqueId(), cart), "remove");
            }
            return removed;
        }
    }

    public static List<CartItem> getItems(Player player) {
        if (player == null) return Collections.emptyList();
        return getItems(player.getUniqueId());
    }

    public static List<CartItem> getItems(UUID uuid) {
        if (uuid == null) return Collections.emptyList();
        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            return new ArrayList<>(cart.items.values());
        }
    }

    public static List<OrderItem> createOrderSnapshot(Player player) {
        return createOrderSnapshot(player != null ? player.getUniqueId() : null);
    }

    public static List<OrderItem> createOrderSnapshot(UUID uuid) {
        List<OrderItem> items = new ArrayList<>();
        for (CartItem item : getItems(uuid)) {
            items.add(item.toOrderItem());
        }
        return Collections.unmodifiableList(items);
    }

    public static BigDecimal getSubtotal(Player player) {
        return player != null ? getCart(player.getUniqueId()).getSubtotal() : BigDecimal.ZERO;
    }

    public static BigDecimal getTotal(Player player) {
        return player != null ? getCart(player.getUniqueId()).getTotal() : BigDecimal.ZERO;
    }

    public static int getItemCount(Player player) {
        return player != null ? getCart(player.getUniqueId()).getItemCount() : 0;
    }

    public static int getDiscountPercent(Player player) {
        if (player == null) return 0;
        CartState cart = getOrCreateState(player.getUniqueId());
        synchronized (cart) {
            return cart.discountPercent;
        }
    }

    public static String getCouponName(Player player) {
        if (player == null) return null;
        CartState cart = getOrCreateState(player.getUniqueId());
        synchronized (cart) {
            return cart.couponName;
        }
    }

    public static String getCheckoutIdempotencyKey(Player player) {
        return getCheckoutIdempotencyKey(player != null ? player.getUniqueId() : null);
    }

    public static String getCheckoutIdempotencyKey(UUID uuid) {
        if (uuid == null) return UUID.randomUUID().toString();
        CartState cart = getOrCreateState(uuid);
        synchronized (cart) {
            String material = cart.cartId + ":" + cart.revision;
            return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public static void applyDiscount(Player player, String couponName, int percent) {
        if (player == null) return;
        CartState cart = getOrCreateState(player.getUniqueId());
        synchronized (cart) {
            cart.couponName = couponName;
            cart.discountPercent = Math.max(0, Math.min(99, percent));
            cart.revision++;
            persistCartAsync(player.getUniqueId(), cart);
            fireCartUpdated(player.getUniqueId(), toCartSnapshot(player.getUniqueId(), cart), "apply_coupon");
        }
    }

    public static void clearDiscount(Player player) {
        if (player == null) return;
        CartState cart = getOrCreateState(player.getUniqueId());
        synchronized (cart) {
            if (cart.discountPercent != 0 || cart.couponName != null) {
                clearCoupon(cart);
                cart.revision++;
                persistCartAsync(player.getUniqueId(), cart);
                fireCartUpdated(player.getUniqueId(), toCartSnapshot(player.getUniqueId(), cart), "remove_coupon");
            }
        }
    }

    public static void clear(Player player) {
        if (player != null) {
            clearCart(player.getUniqueId());
        }
    }

    public static void clearAll() {
        CARTS.clear();
    }

    public static String getSummary(Player player) {
        if (player == null) return "Carrinho vazio";
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

    public static int validateCouponPercent(String couponCode) {
        if (couponCode == null || couponCode.trim().isEmpty()) return 0;
        AutoPix plugin = AutoPix.getInstance();
        if (plugin == null) return 0;
        FileConfiguration config = plugin.getConfig();
        String code = couponCode.trim();

        String path = "cupons." + code;
        if (!config.contains(path)) path = "cupons." + code.toLowerCase(Locale.ROOT);
        if (!config.contains(path)) path = "coupons." + code.toLowerCase(Locale.ROOT);
        if (!config.contains(path)) return 0;

        int percent = config.getInt(path + ".porcentagem", config.getInt(path + ".percentage", 0));
        return Math.max(0, Math.min(99, percent));
    }

    // =========================================================================
    // Persistence Layer (Database)
    // =========================================================================

    private static CartState getOrCreateState(UUID uuid) {
        CartState existing = CARTS.get(uuid);
        if (existing != null) {
            return existing;
        }
        // Avoid blocking Bukkit main thread on synchronous JDBC if called from primary thread
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            CartState fresh = new CartState();
            CartState prev = CARTS.putIfAbsent(uuid, fresh);
            if (prev != null) return prev;
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null && plugin.isEnabled() && OrderManager.isDatabaseReady()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    CartState loaded = loadFromDatabase(uuid);
                    synchronized (fresh) {
                        if (fresh.items.isEmpty() && !loaded.items.isEmpty()) {
                            fresh.items.putAll(loaded.items);
                            fresh.cartId = loaded.cartId;
                            fresh.couponName = loaded.couponName;
                            fresh.discountPercent = loaded.discountPercent;
                        }
                    }
                });
            }
            return fresh;
        }
        return CARTS.computeIfAbsent(uuid, CartManager::loadFromDatabase);
    }

    private static Cart toCartSnapshot(UUID uuid, CartState cart) {
        return new CartImpl(uuid, new ArrayList<>(cart.items.values()), cart.couponName, cart.discountPercent);
    }

    private static void fireCartUpdated(UUID uuid, Cart cart, String operation) {
        AutoPixEventDispatcher.dispatch(new CartUpdatedEvent(uuid, cart, operation));
    }

    private static void persistCartAsync(UUID uuid, CartState state) {
        AutoPix plugin = AutoPix.getInstance();
        Runnable task = () -> saveToDatabase(uuid, state);
        if (plugin != null && plugin.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    private static synchronized void saveToDatabase(UUID uuid, CartState state) {
        if (uuid == null || !OrderManager.isDatabaseReady()) return;
        List<CartItem> itemsToSave;
        String coupon;
        UUID cartId;
        synchronized (state) {
            itemsToSave = new ArrayList<>(state.items.values());
            coupon = state.couponName;
            cartId = state.cartId;
        }

        try (Connection connection = OrderManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delItems = connection.prepareStatement(
                        "DELETE FROM autopix_cart_items WHERE cart_id = ?;")) {
                    delItems.setString(1, cartId.toString());
                    delItems.executeUpdate();
                }

                try (PreparedStatement delCart = connection.prepareStatement(
                        "DELETE FROM autopix_carts WHERE player_uuid = ?;")) {
                    delCart.setString(1, uuid.toString());
                    delCart.executeUpdate();
                }

                if (!itemsToSave.isEmpty() || coupon != null) {
                    Timestamp now = Timestamp.from(Instant.now());
                    try (PreparedStatement insCart = connection.prepareStatement(
                            "INSERT INTO autopix_carts (id, player_uuid, coupon_id, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, ?, ?);")) {
                        insCart.setString(1, cartId.toString());
                        insCart.setString(2, uuid.toString());
                        insCart.setString(3, coupon);
                        insCart.setTimestamp(4, now);
                        insCart.setTimestamp(5, now);
                        insCart.executeUpdate();
                    }

                    if (!itemsToSave.isEmpty()) {
                        String insSql = "INSERT INTO autopix_cart_items "
                                + "(id, cart_id, product_id, offer_id, product_name, description, category, "
                                + "offer_type, duration, unit_price, currency, delivery_type, delivery_handler, "
                                + "pre_commands, commands, commercial_snapshot, quantity, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
                        try (PreparedStatement insItem = connection.prepareStatement(insSql)) {
                            for (CartItem item : itemsToSave) {
                                insItem.setString(1, UUID.randomUUID().toString());
                                insItem.setString(2, cartId.toString());
                                insItem.setString(3, item.getProductId());
                                insItem.setString(4, item.getOfferId());
                                insItem.setString(5, item.getProduct());
                                insItem.setString(6, item.getDescription());
                                insItem.setString(7, item.getCategory());
                                insItem.setString(8, item.getOfferType());
                                insItem.setString(9, item.getDuration());
                                insItem.setBigDecimal(10, item.getUnitPriceDecimal());
                                insItem.setString(11, item.getCurrency());
                                insItem.setString(12, item.getDeliveryType());
                                insItem.setString(13, item.getDeliveryHandler());
                                insItem.setString(14, encodeList(item.getPreCommands()));
                                insItem.setString(15, encodeList(item.getCommands()));
                                insItem.setString(16, item.getCommercialSnapshot());
                                insItem.setInt(17, item.getQuantity());
                                insItem.setTimestamp(18, now);
                                insItem.setTimestamp(19, now);
                                insItem.addBatch();
                            }
                            insItem.executeBatch();
                        }
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao persistir carrinho de " + uuid + ": " + ex.getMessage());
            }
        }
    }

    private static CartState loadFromDatabase(UUID uuid) {
        CartState state = new CartState();
        if (uuid == null || !OrderManager.isDatabaseReady()) return state;

        try (Connection connection = OrderManager.getConnection()) {
            String cartSql = "SELECT id, coupon_id FROM autopix_carts WHERE player_uuid = ?;";
            String loadedCartId = null;
            String couponId = null;

            try (PreparedStatement statement = connection.prepareStatement(cartSql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        loadedCartId = result.getString("id");
                        couponId = result.getString("coupon_id");
                    }
                }
            }

            if (loadedCartId == null) {
                return state;
            }

            try {
                state.cartId = UUID.fromString(loadedCartId);
            } catch (IllegalArgumentException ignored) {
            }

            if (couponId != null) {
                state.couponName = couponId;
                state.discountPercent = validateCouponPercent(couponId);
            }

            String itemsSql = "SELECT * FROM autopix_cart_items WHERE cart_id = ?;";
            try (PreparedStatement statement = connection.prepareStatement(itemsSql)) {
                statement.setString(1, loadedCartId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String prodId = result.getString("product_id");
                        String offId = result.getString("offer_id");
                        int qty = result.getInt("quantity");
                        if (prodId == null) continue;

                        if (prodId.startsWith("menu:")) {
                            String[] parts = prodId.split(":");
                            if (parts.length >= 3) {
                                String menuName = parts[1];
                                int slot = Integer.parseInt(parts[2]);
                                OrderProduct legacyProduct = Bukkit.getServer() != null
                                        ? InventoryManager.getOrderProduct(menuName, slot)
                                        : null;
                                if (legacyProduct != null) {
                                    String key = key(menuName, slot);
                                    state.items.put(key, new CartItem(key, menuName, slot, legacyProduct, qty));
                                }
                            }
                            continue;
                        }

                        String resolvedOfferId = offId != null && !offId.trim().isEmpty() ? offId.trim() : "default";
                        String itemKey = (prodId + ":" + resolvedOfferId).toLowerCase(Locale.ROOT);

                        Product catalogProduct = CatalogManager.getProduct(prodId);
                        Offer catalogOffer = catalogProduct != null ? catalogProduct.getOffer(resolvedOfferId) : null;

                        String prodName = safeGetString(result, "product_name");
                        if ((prodName == null || prodName.isEmpty()) && catalogProduct != null) {
                            prodName = catalogProduct.getName();
                        }
                        if (prodName == null || prodName.isEmpty()) {
                            prodName = prodId;
                        }

                        String description = safeGetString(result, "description");
                        if (description == null && catalogProduct != null) {
                            description = catalogProduct.getDescription();
                        }

                        String category = safeGetString(result, "category");
                        if ((category == null || category.isEmpty()) && catalogProduct != null) {
                            category = catalogProduct.getCategory();
                        }
                        if (category == null || category.isEmpty()) {
                            category = "general";
                        }

                        String offerType = safeGetString(result, "offer_type");
                        if ((offerType == null || offerType.isEmpty()) && catalogOffer != null && catalogOffer.getType() != null) {
                            offerType = catalogOffer.getType().name().toLowerCase(Locale.ROOT);
                        }
                        if (offerType == null || offerType.isEmpty()) {
                            offerType = "one_time";
                        }

                        String duration = safeGetString(result, "duration");
                        if (duration == null && catalogOffer != null) {
                            duration = catalogOffer.getDurationString();
                        }

                        BigDecimal price = safeGetBigDecimal(result, "unit_price");
                        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                            if (catalogOffer != null) {
                                price = catalogOffer.getPrice();
                            } else if (catalogProduct != null) {
                                price = catalogProduct.getPrice();
                            } else {
                                price = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                            }
                        }

                        String currency = safeGetString(result, "currency");
                        if (currency == null || currency.isEmpty()) currency = "BRL";

                        String deliveryType = safeGetString(result, "delivery_type");
                        String deliveryHandler = safeGetString(result, "delivery_handler");
                        List<String> preCmds = decodeList(safeGetString(result, "pre_commands"));
                        List<String> cmds = decodeList(safeGetString(result, "commands"));
                        String commercialSnapshot = safeGetString(result, "commercial_snapshot");

                        if (catalogProduct != null && catalogProduct.getDelivery() != null) {
                            DeliveryConfig deliv = catalogProduct.getDelivery();
                            if (deliveryType == null || deliveryType.isEmpty()) {
                                deliveryType = deliv.getType().name().toLowerCase(Locale.ROOT);
                            }
                            if (deliveryHandler == null || deliveryHandler.isEmpty()) {
                                deliveryHandler = deliv.getHandler();
                            }
                            if (preCmds.isEmpty()) {
                                preCmds = deliv.getPreCommands();
                            }
                            if (cmds.isEmpty()) {
                                cmds = adaptCommandsForOfferDuration(deliv.getCommands(), duration);
                            }
                        }

                        state.items.put(itemKey, new CartItem(
                                itemKey, "catalog", 0,
                                catalogProduct != null ? catalogProduct.getId() : prodId,
                                resolvedOfferId,
                                prodName,
                                description,
                                category,
                                offerType,
                                duration,
                                currency,
                                price,
                                deliveryType != null ? deliveryType : "autopix",
                                deliveryHandler,
                                commercialSnapshot,
                                null,
                                preCmds,
                                cmds,
                                qty));
                    }
                }
            }
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao carregar carrinho de " + uuid + " do banco: " + ex.getMessage());
            }
        }

        return state;
    }

    private static String safeGetString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static BigDecimal safeGetBigDecimal(ResultSet rs, String col) {
        try {
            BigDecimal val = rs.getBigDecimal(col);
            return val != null ? val.setScale(2, RoundingMode.HALF_UP) : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String encodeList(List<String> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (String s : list) {
                if (s != null) arr.add(s);
            }
        }
        return arr.toJSONString();
    }

    private static List<String> decodeList(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        try {
            Object parsed = new JSONParser().parse(json);
            if (parsed instanceof JSONArray) {
                for (Object o : (JSONArray) parsed) {
                    if (o != null) list.add(String.valueOf(o));
                }
            }
        } catch (Exception ignored) {
        }
        return list;
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
        private UUID cartId = UUID.randomUUID();
        private long revision;
        private String couponName;
        private int discountPercent;
    }
}
