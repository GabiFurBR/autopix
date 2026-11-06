package io.github.warleysr.autopix.api;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.TimeManager;
import io.github.warleysr.autopix.cart.CartManager;
import io.github.warleysr.autopix.domain.CartItem;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.CheckoutFailedEvent;
import io.github.warleysr.autopix.events.OrderCreatedEvent;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.github.warleysr.autopix.qrcode.ImageCreator;
import io.github.warleysr.autopix.qrcode.PixGenerator;

/**
 * Public integration facade for Skript and other plugins.
 *
 * Inventory/cart mutation and checkout initiation must be called from the
 * Bukkit primary thread. Network and database work is performed asynchronously.
 */
public final class AutoPixAPI {

    private AutoPixAPI() {
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("AutoPixAPI deve ser chamada pela thread principal do servidor.");
        }
    }

    private static boolean isAvailable() {
        AutoPix plugin = AutoPix.getInstance();
        return plugin != null && plugin.isEnabled();
    }

    public static boolean addToCart(Player player, String menu, int slot, int quantity) {
        requireMainThread();
        return isAvailable() && CartManager.add(player, menu, slot, quantity);
    }

    public static boolean removeFromCart(Player player, String itemKey) {
        requireMainThread();
        return isAvailable() && CartManager.remove(player, itemKey);
    }

    public static boolean changeQuantity(Player player, String itemKey, int delta) {
        requireMainThread();
        return isAvailable() && CartManager.changeQuantity(player, itemKey, delta);
    }

    public static boolean setQuantity(Player player, String itemKey, int quantity) {
        requireMainThread();
        return isAvailable() && CartManager.setQuantity(player, itemKey, quantity);
    }

    public static List<CartItem> getCartItems(Player player) {
        return CartManager.getItems(player);
    }

    public static float getCartSubtotal(Player player) {
        return CartManager.getSubtotal(player).floatValue();
    }

    public static float getCartTotal(Player player) {
        return CartManager.getTotal(player).floatValue();
    }

    public static int getCartItemCount(Player player) {
        return CartManager.getItemCount(player);
    }

    public static String getCartCoupon(Player player) {
        return CartManager.getCouponName(player);
    }

    public static boolean applyCartDiscount(Player player, String couponName, int percent) {
        requireMainThread();
        if (!isAvailable() || player == null || CartManager.getItems(player).isEmpty()
                || couponName == null || couponName.trim().isEmpty()
                || percent < 1 || percent > 99) return false;
        CartManager.applyDiscount(player, couponName.trim(), percent);
        return true;
    }

    public static void clearCart(Player player) {
        requireMainThread();
        CartManager.clear(player);
    }

    public static void openShopMenu(Player player) {
        requireMainThread();
        if (isAvailable() && player != null) InventoryManager.openMenu(player, "principal");
    }

    public static void openCartMenu(Player player) {
        requireMainThread();
        if (isAvailable() && player != null) InventoryManager.openCartMenu(player);
    }

    public static boolean checkout(Player player) {
        return checkout(player, false);
    }

    /**
     * Starts one payment for the complete cart. Set giveMap=false when the
     * caller will display the QR payload received in OrderCreatedEvent.
     */
    public static boolean checkout(Player player, boolean giveMap) {
        requireMainThread();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable() || player == null || !player.isOnline()) return false;

        List<OrderItem> orderItems = CartManager.createOrderSnapshot(player);
        if (orderItems.isEmpty()) {
            player.sendMessage("§b[AutoPix] §cSeu carrinho está vazio.");
            return false;
        }

        final float total = CartManager.getTotal(player).floatValue();
        if (!Float.isFinite(total) || total < 0.01f) {
            player.sendMessage("§b[AutoPix] §cO total do carrinho precisa ser de pelo menos R$ 0,01.");
            return false;
        }

        if (!OrderManager.tryAcquireCreateLock(player.getName())) {
            MSG.sendMessage(player, "processando-pedido");
            return false;
        }
        if (!TimeManager.canExecute(plugin, player, "create")) {
            OrderManager.releaseCreateLock(player.getName());
            return false;
        }

        final String playerName = player.getName();
        final java.util.UUID playerUuid = player.getUniqueId();
        final String summary = buildSummary(orderItems);
        final String checkoutIdempotencyKey = CartManager.getCheckoutIdempotencyKey(player);
        final boolean automaticMode = plugin.getConfig().getBoolean("automatico.ativado", true);
        final boolean generateMap = giveMap
                && (automaticMode || plugin.getConfig().getBoolean("pix.mapa", true));
        final int maxMinutes = Math.max(1, plugin.getConfig().getInt("mapa.tempo-pagar", 30));

        player.closeInventory();
        MSG.sendMessage(player, "criando-mapa");
        MSG.sendActionBar(player, "actionbar-gerando", "{produto}", summary);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PixData pixData = null;
            boolean orderPersisted = false;
            boolean releaseLockNow = true;
            try {
                if (!plugin.isEnabled()) return;

                if (automaticMode) {
                    Order lastOrder = OrderManager.getLastOrder(playerName);
                    if (lastOrder != null && !lastOrder.isValidated()) {
                        PixData previousPix = OrderManager.getPixData(lastOrder);
                        long elapsed = TimeUnit.MILLISECONDS.toMinutes(
                                Math.max(0L, System.currentTimeMillis() - lastOrder.getCreated().getTime()));
                        if (previousPix != null && previousPix.isPending() && elapsed < maxMinutes) {
                            fireCheckoutFailed(playerName, playerUuid, "pending_order");
                            return;
                        }
                    }
                }

                String qrPayload;
                if (automaticMode) {
                    pixData = MercadoPagoAPI.createPixPayment(plugin, playerName, summary, total,
                            checkoutIdempotencyKey);
                    if (pixData == null) {
                        fireCheckoutFailed(playerName, playerUuid, "mercadopago_error");
                        return;
                    }
                    qrPayload = pixData.getQrCode();
                } else {
                    qrPayload = PixGenerator.generatePayload(
                            AutoPix.getPixKey(), AutoPix.getPixName(), summary, total);
                }

                if (qrPayload == null || qrPayload.trim().isEmpty()) {
                    if (pixData != null) {
                        try { MercadoPagoAPI.cancelPayment(plugin, pixData.getPaymentId()); }
                        catch (Exception ignored) { }
                    }
                    fireCheckoutFailed(playerName, playerUuid, "empty_qr");
                    return;
                }

                BufferedImage qrImage = generateMap ? ImageCreator.generateQR(qrPayload) : null;

                Order order = OrderManager.createOrderWithItems(
                        playerName, summary, total, pixData, orderItems);
                if (order == null) {
                    if (pixData != null) {
                        try {
                            if (!MercadoPagoAPI.cancelPayment(plugin, pixData.getPaymentId())) {
                                plugin.getLogger().severe("Cobranca PIX orfa #" + pixData.getPaymentId()
                                        + " nao foi cancelada apos falha ao persistir o pedido.");
                            }
                        } catch (Exception cancelException) {
                            plugin.getLogger().severe("Falha ao cancelar cobranca PIX orfa #"
                                    + pixData.getPaymentId() + ": " + cancelException.getMessage());
                        }
                    }
                    fireCheckoutFailed(playerName, playerUuid, "database_error");
                    return;
                }

                orderPersisted = true;
                Order createdOrder = order;
                PixData createdPixData = pixData;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        AutoPixEventDispatcher.dispatch(new OrderCreatedEvent(
                                createdOrder.getId(), playerName, playerUuid, summary, total,
                                createdPixData != null ? createdPixData.getPaymentId() : null,
                                "pending", createdOrder.getCreated().getTime(), qrPayload, orderItems));
                        CartManager.clear(player);

                        for (OrderItem item : orderItems) {
                            for (String command : item.getPreCommands()) {
                                String rendered = renderCommand(command, playerName, item);
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
                            }
                        }

                        if (generateMap && qrImage != null && player.isOnline()) {
                            OrderProduct mapProduct = new OrderProduct(summary, total, null,
                                    Collections.emptyList(), Collections.emptyList());
                            org.bukkit.inventory.ItemStack map =
                                    ImageCreator.generateMap(qrImage, player, mapProduct);
                            if (!InventoryManager.deliverPaymentMapSafely(player, map)) {
                                MSG.sendMessage(player, "mao-vazia");
                            }
                            SoundManager.play(player, SoundManager.GENERATE_QR);
                            MSG.sendTitle(player, "titulo-qr", "subtitulo-qr", 10, 70, 20,
                                    "{produto}", summary, "{tempo}", Integer.toString(maxMinutes));
                            MSG.sendActionBar(player, "actionbar-pendente",
                                    "{produto}", summary, "{tempo}", Integer.toString(maxMinutes));
                        }

                    } catch (Exception deliveryException) {
                        plugin.getLogger().severe("Pedido #" + createdOrder.getId()
                                + " foi salvo, mas a exibicao local do QR falhou: " + deliveryException.getMessage());
                    } finally {
                        OrderManager.releaseCreateLock(playerName);
                    }
                });
                releaseLockNow = false;
            } catch (Exception exception) {
                if (!orderPersisted && pixData != null) {
                    try {
                        if (!MercadoPagoAPI.cancelPayment(plugin, pixData.getPaymentId())) {
                            plugin.getLogger().severe("Cobranca PIX orfa #" + pixData.getPaymentId()
                                    + " nao foi cancelada apos falha no checkout.");
                        }
                    } catch (Exception cancelException) {
                        plugin.getLogger().severe("Falha ao cancelar cobranca PIX orfa #"
                                + pixData.getPaymentId() + ": " + cancelException.getMessage());
                    }
                }
                plugin.getLogger().warning("Erro ao finalizar carrinho de " + playerName + ": " + exception.getMessage());
                fireCheckoutFailed(playerName, playerUuid, "checkout_error");
            } finally {
                if (releaseLockNow) OrderManager.releaseCreateLock(playerName);
            }
        });

        return true;
    }

    private static void fireCheckoutFailed(String playerName, java.util.UUID playerUuid, String reason) {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            TimeManager.resetDelay(playerName, "create");
            AutoPixEventDispatcher.dispatch(new CheckoutFailedEvent(playerName, playerUuid, reason));
        });
    }

    private static String buildSummary(List<OrderItem> items) {
        if (items.size() == 1 && items.get(0).getQuantity() == 1) {
            return truncate(items.get(0).getProduct(), 64);
        }
        return truncate("Carrinho (" + items.stream().mapToInt(OrderItem::getQuantity).sum() + " itens)", 64);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String renderCommand(String command, String playerName, OrderItem item) {
        return command.replace("{player}", playerName)
                .replace("{produto}", item.getProduct())
                .replace("{quantidade}", Integer.toString(item.getQuantity()))
                .replace("{preco}", String.format(java.util.Locale.ROOT, "%.2f", item.getLineTotal()).replace('.', ','))
                .replace('&', '§');
    }
}
