package io.github.warleysr.autopix.api;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.TimeManager;
import io.github.warleysr.autopix.cart.CartManager;
import io.github.warleysr.autopix.catalog.CatalogManager;
import io.github.warleysr.autopix.catalog.CustomDonationConfig;
import io.github.warleysr.autopix.catalog.DeliveryConfig;
import io.github.warleysr.autopix.catalog.Offer;
import io.github.warleysr.autopix.catalog.Product;
import io.github.warleysr.autopix.delivery.DeliveryManager;
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
import io.github.warleysr.autopix.mercadopago.WebhookValidator;
import io.github.warleysr.autopix.qrcode.ImageCreator;
import io.github.warleysr.autopix.qrcode.PixGenerator;
import io.github.warleysr.autopix.subscription.SubscriptionManager;

/**
 * Public integration API for Skript, Webhooks, and server integrations.
 * Provides modern asynchronous CompletionStage-based operations and backwards-compatible helpers.
 */
public final class AutoPixAPI {

    private static volatile String webhookSecretOverride;
    private static final ConcurrentHashMap<String, Object> WEBHOOK_PAYMENT_LOCKS = new ConcurrentHashMap<>();

    private AutoPixAPI() {
    }

    public static void setWebhookSecretForTesting(String secret) {
        webhookSecretOverride = secret;
    }

    public static String getConfiguredWebhookSecret() {
        if (webhookSecretOverride != null) {
            return webhookSecretOverride;
        }
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null && plugin.getConfig() != null) {
            String autoSecret = plugin.getConfig().getString("automatico.webhook.secret", "");
            if (autoSecret != null && !autoSecret.trim().isEmpty()) {
                return autoSecret.trim();
            }
            String cfgSecret = plugin.getConfig().getString("mercadopago.webhook.secret", "");
            if (cfgSecret != null && !cfgSecret.trim().isEmpty()) {
                return cfgSecret.trim();
            }
        }
        return null;
    }

    private static void requireMainThread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("AutoPixAPI deve ser chamada pela thread principal do servidor.");
        }
    }

    private static boolean isAvailable() {
        if (!OrderManager.isDatabaseReady() || OrderManager.isShuttingDown()) {
            return false;
        }
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null) {
            return plugin.isEnabled();
        }
        return true;
    }

    private static <T> void completeOnMainThread(AutoPix plugin, CompletableFuture<T> future, T value) {
        if (plugin != null && plugin.isEnabled() && Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> future.complete(value));
        } else {
            future.complete(value);
        }
    }

    private static void runAsyncOrNow(AutoPix plugin, Runnable task) {
        if (plugin != null && plugin.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    // =========================================================================
    // Modern Asynchronous API (CompletionStage / CompletableFuture)
    // =========================================================================

    public static CompletionStage<OrderCreationResult> createOrder(CheckoutRequest request) {
        CompletableFuture<OrderCreationResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future,
                    OrderCreationResult.failure("service_unavailable", "O banco de dados do AutoPix ainda não está pronto."));
            return future;
        }

        if (request == null) {
            completeOnMainThread(plugin, future,
                    OrderCreationResult.failure("invalid_request", "Requisição de checkout nula."));
            return future;
        }

        UUID playerUuid = request.getPlayerUuid();
        if (playerUuid == null) {
            completeOnMainThread(plugin, future,
                    OrderCreationResult.failure("missing_player", "UUID do jogador não informado."));
            return future;
        }

        runAsyncOrNow(plugin, () -> {
            try {
                if (!OrderManager.isDatabaseReady() || OrderManager.isShuttingDown()) {
                    completeOnMainThread(plugin, future,
                            OrderCreationResult.failure("service_unavailable", "Serviço temporariamente indisponível."));
                    return;
                }

                String idempotencyKey = request.getIdempotencyKey() != null && !request.getIdempotencyKey().trim().isEmpty()
                        ? request.getIdempotencyKey().trim()
                        : null;

                if (idempotencyKey != null) {
                    Order existingOrder = OrderManager.getOrderByIdempotencyKey(idempotencyKey);
                    if (existingOrder != null) {
                        Payment existingPayment = OrderManager.getPaymentByOrderId(existingOrder.getId());
                        completeOnMainThread(plugin, future, OrderCreationResult.success(existingOrder, existingPayment));
                        return;
                    }
                }

                List<OrderItem> orderItems = new ArrayList<>();
                BigDecimal subtotal = BigDecimal.ZERO;
                String couponCode = null;
                boolean isCustomDonation = request.getCustomDonationAmount() != null;

                if (isCustomDonation) {
                    CustomDonationConfig donationCfg = CatalogManager.getCustomDonationConfig();
                    if (donationCfg == null || !donationCfg.isEnabled()) {
                        completeOnMainThread(plugin, future,
                                OrderCreationResult.failure("donations_disabled", "Doações personalizadas estão desativadas."));
                        return;
                    }
                    BigDecimal validatedDonation;
                    try {
                        validatedDonation = donationCfg.validateAndNormalize(request.getCustomDonationAmount());
                    } catch (IllegalArgumentException ex) {
                        completeOnMainThread(plugin, future,
                                OrderCreationResult.failure("invalid_donation_amount", ex.getMessage()));
                        return;
                    }

                    DeliveryConfig deliv = donationCfg.getDelivery();
                    String delivType = deliv != null && deliv.getType() != null
                            ? deliv.getType().name().toLowerCase(Locale.ROOT)
                            : "skript";
                    String delivHandler = deliv != null && deliv.getHandler() != null
                            ? deliv.getHandler()
                            : "custom_donation_reward";
                    List<String> cmds = deliv != null ? deliv.getCommands() : Collections.emptyList();
                    String categoryId = donationCfg.getCategory() != null ? donationCfg.getCategory() : "donations";
                    String productTitle = String.format(Locale.ROOT, "Doacao Personalizada R$ %.2f", validatedDonation);

                    OrderItem donationItem = new OrderItem(
                            "custom_donation",
                            "custom",
                            productTitle,
                            "Doacao de valor livre validada pelo servidor",
                            categoryId,
                            "ONE_TIME",
                            null,
                            "BRL",
                            1,
                            validatedDonation,
                            validatedDonation,
                            delivType,
                            delivHandler,
                            null,
                            Collections.emptyList(),
                            cmds
                    );
                    orderItems.add(donationItem);
                    subtotal = validatedDonation;
                    if (idempotencyKey == null) {
                        idempotencyKey = "donation_" + playerUuid + "_" + validatedDonation.toPlainString() + "_" + System.nanoTime();
                    }
                } else {
                    Cart cart = CartManager.getCart(playerUuid);
                    List<CartItem> cartItems = request.getCustomItems() != null && !request.getCustomItems().isEmpty()
                            ? request.getCustomItems()
                            : new ArrayList<>(cart.getItems());

                    if (cartItems.isEmpty()) {
                        completeOnMainThread(plugin, future,
                                OrderCreationResult.failure("empty_cart", "O carrinho está vazio."));
                        return;
                    }

                    for (CartItem item : cartItems) {
                        if (item.getQuantity() <= 0) {
                            completeOnMainThread(plugin, future,
                                    OrderCreationResult.failure("invalid_quantity", "Quantidade inválida no carrinho."));
                            return;
                        }
                        OrderItem snapshotItem = revalidateCartItemForCheckout(item);
                        if (snapshotItem == null) {
                            completeOnMainThread(plugin, future,
                                    OrderCreationResult.failure("invalid_catalog_item",
                                            "O produto ou oferta '" + item.getProductId() + "' não está mais disponível ou é inválido."));
                            return;
                        }
                        subtotal = subtotal.add(snapshotItem.getLineTotalDecimal()).setScale(2, RoundingMode.HALF_UP);
                        orderItems.add(snapshotItem);
                    }

                    couponCode = request.getCoupon() != null ? request.getCoupon() : cart.getCoupon();
                    if (idempotencyKey == null) {
                        idempotencyKey = CartManager.getCheckoutIdempotencyKey(playerUuid);
                    }

                    Order existingByCartKey = OrderManager.getOrderByIdempotencyKey(idempotencyKey);
                    if (existingByCartKey != null) {
                        Payment existingPayment = OrderManager.getPaymentByOrderId(existingByCartKey.getId());
                        completeOnMainThread(plugin, future, OrderCreationResult.success(existingByCartKey, existingPayment));
                        return;
                    }
                }

                int discountPercent = 0;
                if (!isCustomDonation && couponCode != null && !couponCode.trim().isEmpty()) {
                    discountPercent = CartManager.validateCouponPercent(couponCode);
                }

                BigDecimal discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal calcTotal = subtotal.setScale(2, RoundingMode.HALF_UP);
                if (discountPercent > 0 && subtotal.compareTo(BigDecimal.ZERO) > 0) {
                    discount = subtotal.multiply(BigDecimal.valueOf(discountPercent))
                            .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
                    calcTotal = subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP);
                }

                final BigDecimal total = calcTotal;
                if (total.compareTo(new BigDecimal("0.01")) < 0) {
                    completeOnMainThread(plugin, future,
                            OrderCreationResult.failure("invalid_total", "O valor total do pedido deve ser de pelo menos R$ 0,01."));
                    return;
                }

                String playerName = resolvePlayerName(playerUuid);
                String summary = buildSummary(orderItems);

                boolean automaticMode = plugin == null || plugin.getConfig() == null
                        || plugin.getConfig().getBoolean("automatico.ativado", true);
                int maxMinutes = (plugin != null && plugin.getConfig() != null)
                        ? Math.max(1, plugin.getConfig().getInt("mapa.tempo-pagar", 30))
                        : 30;
                Instant expiresAt = Instant.now().plusSeconds(maxMinutes * 60L);

                PixData pixData = null;
                String qrPayload = null;
                String paymentId = null;

                if (automaticMode) {
                    pixData = MercadoPagoAPI.createPixPayment(plugin, playerName, summary, total.floatValue(), idempotencyKey);
                    if (pixData == null) {
                        completeOnMainThread(plugin, future,
                                OrderCreationResult.failure("mercadopago_error", "Falha ao gerar cobrança no Mercado Pago."));
                        return;
                    }
                    qrPayload = pixData.getQrCode();
                    paymentId = pixData.getPaymentId();
                } else {
                    try {
                        String pixKey = AutoPix.getPixKey() != null ? AutoPix.getPixKey() : "123456789";
                        String pixName = AutoPix.getPixName() != null ? AutoPix.getPixName() : "AutoPix";
                        qrPayload = PixGenerator.generatePayload(pixKey, pixName, summary, total.floatValue());
                    } catch (Exception ex) {
                        qrPayload = null;
                    }
                    paymentId = "manual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    if (qrPayload != null) {
                        pixData = new PixData(paymentId, qrPayload);
                    }
                }

                if (qrPayload == null || qrPayload.trim().isEmpty()) {
                    if (pixData != null && automaticMode) {
                        try { MercadoPagoAPI.cancelPayment(plugin, pixData.getPaymentId()); } catch (Exception ignored) {}
                    }
                    completeOnMainThread(plugin, future,
                            OrderCreationResult.failure("empty_qr", "QR code retornado vazio."));
                    return;
                }

                Payment payment = new Payment(
                        paymentId, paymentId, PaymentStatus.PENDING,
                        qrPayload, qrPayload, null, expiresAt);

                Order order = OrderManager.createOrderDetailed(
                        playerName, playerUuid, summary, total, subtotal, discount,
                        couponCode, expiresAt, pixData, orderItems, idempotencyKey);

                if (order == null) {
                    if (pixData != null && automaticMode) {
                        try { MercadoPagoAPI.cancelPayment(plugin, pixData.getPaymentId()); } catch (Exception ignored) {}
                    }
                    completeOnMainThread(plugin, future,
                            OrderCreationResult.failure("database_error", "Erro ao salvar o pedido no banco de dados."));
                    return;
                }

                OrderManager.savePayment(payment, order.getId(), total);

                final String finalQr = qrPayload;
                final PixData finalPix = pixData;
                final float finalTotalFloat = total.floatValue();
                final Order finalOrder = order;
                final String finalPlayerName = playerName;
                final boolean clearPlayerCart = !isCustomDonation;

                Runnable postCreateMainThread = () -> {
                    AutoPixEventDispatcher.dispatch(new OrderCreatedEvent(
                            finalOrder.getId(), finalPlayerName, playerUuid, summary, finalTotalFloat,
                            finalPix != null ? finalPix.getPaymentId() : null,
                            "pending", finalOrder.getCreated().getTime(), finalQr, orderItems));

                    if (clearPlayerCart) {
                        CartManager.clearCart(playerUuid);
                    }

                    if (Bukkit.getServer() != null && plugin != null) {
                        Player onlineP = Bukkit.getPlayer(playerUuid);
                        if (onlineP != null && onlineP.isOnline() && request.isGiveMap()) {
                            try {
                                BufferedImage qrImage = ImageCreator.generateQR(finalQr);
                                if (qrImage != null) {
                                    OrderProduct mapProduct = new OrderProduct(summary, finalTotalFloat, null,
                                            Collections.emptyList(), Collections.emptyList());
                                    org.bukkit.inventory.ItemStack map = ImageCreator.generateMap(qrImage, onlineP, mapProduct);
                                    if (!InventoryManager.deliverPaymentMapSafely(onlineP, map)) {
                                        MSG.sendMessage(onlineP, "mao-vazia");
                                    }
                                    SoundManager.play(onlineP, SoundManager.GENERATE_QR);
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    future.complete(OrderCreationResult.success(finalOrder, payment));
                };

                if (plugin != null && plugin.isEnabled() && Bukkit.getServer() != null) {
                    Bukkit.getScheduler().runTask(plugin, postCreateMainThread);
                } else {
                    postCreateMainThread.run();
                }
            } catch (Exception ex) {
                if (plugin != null) {
                    plugin.getLogger().warning("Erro durante checkout assíncrono: " + ex.getMessage());
                }
                completeOnMainThread(plugin, future, OrderCreationResult.failure("exception", ex.getMessage()));
            }
        });

        return future;
    }

    private static OrderItem revalidateCartItemForCheckout(CartItem item) {
        if (item == null) return null;
        Product catalogProduct = CatalogManager.getProduct(item.getProductId());
        if (catalogProduct != null) {
            if (!catalogProduct.isEnabled()) {
                return null;
            }
            BigDecimal authoritativePrice;
            String offerType = item.getOfferType();
            String duration = item.getDuration();
            String offerId = item.getOfferId() != null ? item.getOfferId() : "default";

            if (catalogProduct.hasOffers()) {
                Offer offer = catalogProduct.getOffer(offerId);
                if (offer == null || !offer.isEnabled()) {
                    return null;
                }
                authoritativePrice = offer.getPrice();
                offerType = offer.getType().name();
                duration = offer.getDurationString();
            } else {
                if (!"default".equalsIgnoreCase(offerId) && !offerId.equalsIgnoreCase(catalogProduct.getId())) {
                    return null;
                }
                authoritativePrice = catalogProduct.getPrice();
                offerType = "ONE_TIME";
            }

            if (authoritativePrice == null || authoritativePrice.compareTo(new BigDecimal("0.01")) < 0) {
                return null;
            }

            BigDecimal unitPrice = authoritativePrice.setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())).setScale(2, RoundingMode.HALF_UP);
            DeliveryConfig deliv = catalogProduct.getDelivery();
            String delivType = deliv != null && deliv.getType() != null
                    ? deliv.getType().name().toLowerCase(Locale.ROOT)
                    : item.getDeliveryType();
            String delivHandler = deliv != null ? deliv.getHandler() : item.getDeliveryHandler();
            List<String> cmds = deliv != null ? deliv.getCommands() : item.getCommands();

            return new OrderItem(
                    catalogProduct.getId(),
                    offerId,
                    catalogProduct.getName(),
                    catalogProduct.getDescription(),
                    catalogProduct.getCategory(),
                    offerType,
                    duration,
                    "BRL",
                    item.getQuantity(),
                    unitPrice,
                    lineTotal,
                    delivType,
                    delivHandler,
                    item.getCommercialSnapshot(),
                    item.getPreCommands(),
                    cmds
            );
        }

        if (item.getUnitPriceDecimal() == null || item.getUnitPriceDecimal().compareTo(new BigDecimal("0.01")) < 0) {
            return null;
        }
        return item.toOrderItem();
    }

    private static String resolvePlayerName(UUID playerUuid) {
        if (playerUuid == null) return "Unknown";
        if (Bukkit.getServer() != null) {
            try {
                Player online = Bukkit.getPlayer(playerUuid);
                if (online != null && online.getName() != null) {
                    return online.getName();
                }
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
                if (offlinePlayer != null && offlinePlayer.getName() != null) {
                    return offlinePlayer.getName();
                }
            } catch (Exception ignored) {
            }
        }
        return playerUuid.toString().substring(0, 8);
    }

    public static CompletionStage<Order> getOrder(String orderId) {
        CompletableFuture<Order> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, null);
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, OrderManager.getOrder(orderId)));
        return future;
    }

    public static CompletionStage<Payment> getPayment(String paymentId) {
        CompletableFuture<Payment> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, null);
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, OrderManager.getPayment(paymentId)));
        return future;
    }

    public static CompletionStage<String> getPixCopyPaste(String orderId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, null);
            return future;
        }
        runAsyncOrNow(plugin, () -> {
            Order order = OrderManager.getOrder(orderId);
            if (order == null) {
                completeOnMainThread(plugin, future, null);
                return;
            }
            PixData pd = OrderManager.getPixData(order);
            if (pd != null) {
                completeOnMainThread(plugin, future, pd.getQrCode());
                return;
            }
            Payment p = OrderManager.getPaymentByOrderId(order.getId());
            completeOnMainThread(plugin, future, p != null ? p.getCopyPasteCode() : null);
        });
        return future;
    }

    public static CompletionStage<CartResult> addToCart(UUID uuid, String productId, String offerId, int quantity) {
        CompletableFuture<CartResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, CartResult.failure("service_unavailable", null));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, CartManager.addToCart(uuid, productId, offerId, quantity)));
        return future;
    }

    public static CompletionStage<CartResult> removeFromCart(UUID uuid, String productId, String offerId, int quantity) {
        CompletableFuture<CartResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, CartResult.failure("service_unavailable", null));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, CartManager.removeFromCart(uuid, productId, offerId, quantity)));
        return future;
    }

    public static CompletionStage<CartResult> clearCart(UUID uuid) {
        CompletableFuture<CartResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, CartResult.failure("service_unavailable", null));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, CartManager.clearCart(uuid)));
        return future;
    }

    public static CompletionStage<Cart> getCart(UUID uuid) {
        CompletableFuture<Cart> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, null);
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, CartManager.getCart(uuid)));
        return future;
    }

    public static CompletionStage<CartResult> applyCoupon(UUID uuid, String couponCode) {
        CompletableFuture<CartResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, CartResult.failure("service_unavailable", null));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, CartManager.applyCoupon(uuid, couponCode)));
        return future;
    }

    public static CompletionStage<CartResult> removeCoupon(UUID uuid) {
        CompletableFuture<CartResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, CartResult.failure("service_unavailable", null));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, CartManager.removeCoupon(uuid)));
        return future;
    }

    public static CompletionStage<SubscriptionResult> createSubscription(UUID uuid, String productId, String planId) {
        CompletableFuture<SubscriptionResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, SubscriptionResult.failure("service_unavailable"));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, SubscriptionManager.createSubscription(uuid, productId, planId)));
        return future;
    }

    public static CompletionStage<SubscriptionResult> cancelSubscription(String subscriptionId, boolean cancelImmediately) {
        CompletableFuture<SubscriptionResult> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, SubscriptionResult.failure("service_unavailable"));
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, SubscriptionManager.cancelSubscription(subscriptionId, cancelImmediately)));
        return future;
    }

    public static CompletionStage<Subscription> getSubscription(String subscriptionId) {
        CompletableFuture<Subscription> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, null);
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, SubscriptionManager.getSubscription(subscriptionId)));
        return future;
    }

    public static CompletionStage<List<Subscription>> getPlayerSubscriptions(UUID uuid) {
        CompletableFuture<List<Subscription>> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, Collections.emptyList());
            return future;
        }
        runAsyncOrNow(plugin, () -> completeOnMainThread(plugin, future, SubscriptionManager.getPlayerSubscriptions(uuid)));
        return future;
    }

    public static CompletionStage<Boolean> cancelOrder(String orderId, String reason) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable()) {
            completeOnMainThread(plugin, future, false);
            return future;
        }
        runAsyncOrNow(plugin, () -> {
            try {
                int id = Integer.parseInt(orderId.trim());
                completeOnMainThread(plugin, future, OrderManager.cancelOrder(id, reason));
            } catch (Exception ex) {
                completeOnMainThread(plugin, future, false);
            }
        });
        return future;
    }

    public static CompletionStage<Boolean> confirmCustomDelivery(int orderId, int itemIndex, String claimToken) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!OrderManager.isDatabaseReady() || OrderManager.isShuttingDown()) {
            completeOnMainThread(plugin, future, false);
            return future;
        }
        runAsyncOrNow(plugin, () -> {
            boolean confirmed = DeliveryManager.confirmCustomDelivery(orderId, itemIndex, claimToken);
            completeOnMainThread(plugin, future, confirmed);
        });
        return future;
    }

    public static CompletionStage<Boolean> confirmCustomDelivery(String orderId, String orderItemId, String claimToken) {
        try {
            int parsedOrderId = Integer.parseInt(orderId != null ? orderId.trim() : "-1");
            int parsedItemIndex = Integer.parseInt(orderItemId != null ? orderItemId.trim() : "-1");
            return confirmCustomDelivery(parsedOrderId, parsedItemIndex, claimToken);
        } catch (NumberFormatException ex) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            completeOnMainThread(AutoPix.getInstance(), future, false);
            return future;
        }
    }

    public static CompletionStage<Boolean> failCustomDelivery(int orderId, int itemIndex, String claimToken, String reason) {
        return failCustomDelivery(orderId, itemIndex, claimToken, reason, true);
    }

    public static CompletionStage<Boolean> failCustomDelivery(int orderId, int itemIndex, String claimToken,
                                                              String reason, boolean retryable) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AutoPix plugin = AutoPix.getInstance();
        if (!OrderManager.isDatabaseReady() || OrderManager.isShuttingDown()) {
            completeOnMainThread(plugin, future, false);
            return future;
        }
        runAsyncOrNow(plugin, () -> {
            boolean recorded = DeliveryManager.failCustomDelivery(orderId, itemIndex, claimToken, reason, retryable);
            completeOnMainThread(plugin, future, recorded);
        });
        return future;
    }

    public static CompletionStage<Boolean> failCustomDelivery(String orderId, String orderItemId, String claimToken, String reason) {
        return failCustomDelivery(orderId, orderItemId, claimToken, reason, true);
    }

    public static CompletionStage<Boolean> failCustomDelivery(String orderId, String orderItemId, String claimToken,
                                                              String reason, boolean retryable) {
        try {
            int parsedOrderId = Integer.parseInt(orderId != null ? orderId.trim() : "-1");
            int parsedItemIndex = Integer.parseInt(orderItemId != null ? orderItemId.trim() : "-1");
            return failCustomDelivery(parsedOrderId, parsedItemIndex, claimToken, reason, retryable);
        } catch (NumberFormatException ex) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            completeOnMainThread(AutoPix.getInstance(), future, false);
            return future;
        }
    }

    // =========================================================================
    // Webhook Notification Handler (Authenticated, Provider-Verified & Idempotent)
    // =========================================================================

    /**
     * Legacy 3-argument webhook notification entry point.
     * Refuses execution unless a webhook secret is configured AND valid signature headers are provided
     * via the authenticated overload.
     */
    public static boolean processWebhookNotification(String topic, String paymentId, String idempotencyKey) {
        return processWebhookNotification(topic, paymentId, idempotencyKey, null, null, getConfiguredWebhookSecret());
    }

    /**
     * Authenticated webhook notification entry point using the configured webhook secret.
     */
    public static boolean processWebhookNotification(String topic, String paymentId, String idempotencyKey,
                                                     String xSignature, String xRequestId) {
        return processWebhookNotification(topic, paymentId, idempotencyKey, xSignature, xRequestId, getConfiguredWebhookSecret());
    }

    /**
     * Authenticated and independently verified webhook notification handler.
     *
     * Security guarantees:
     * 1. Rejects notifications when no webhook secret is configured or when HMAC-SHA256 signature verification fails.
     * 2. Deduplicates already-completed notifications by idempotency_key.
     * 3. Never marks a notification as PROCESSED before provider verification and order reconciliation succeed.
     * 4. Uses the webhook solely as a trigger to consult MercadoPagoAPI.getPayment() directly, validating
     *    that the payment exists in the provider, currency == BRL, external reference matches, and BigDecimal
     *    paid amount equals the expected order total.
     */
    public static boolean processWebhookNotification(String topic, String paymentId, String idempotencyKey,
                                                     String xSignature, String xRequestId, String webhookSecret) {
        if (!OrderManager.isDatabaseReady() || OrderManager.isShuttingDown()) {
            return false;
        }
        if (paymentId == null || paymentId.trim().isEmpty()) {
            return false;
        }
        if (topic != null && !topic.trim().isEmpty()) {
            String normalizedTopic = topic.trim().toLowerCase(Locale.ROOT);
            if (!normalizedTopic.equals("payment")
                    && !normalizedTopic.equals("payment.updated")
                    && !normalizedTopic.equals("payment.created")) {
                return false;
            }
        }

        String trimmedPaymentId = paymentId.trim();
        if (webhookSecret == null || webhookSecret.trim().isEmpty()) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Webhook recusado para pagamento #" + trimmedPaymentId
                        + ": segredo de webhook nao configurado.");
            }
            return false;
        }

        if (!WebhookValidator.validateSignature(xSignature, xRequestId, trimmedPaymentId, webhookSecret.trim())) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Webhook recusado para pagamento #" + trimmedPaymentId
                        + ": assinatura HMAC-SHA256 invalida ou ausente.");
            }
            return false;
        }

        String trimmedRequestId = xRequestId != null ? xRequestId.trim() : "";
        String reqKey = "wh_" + trimmedPaymentId + "_" + trimmedRequestId;
        String key = (idempotencyKey != null && !idempotencyKey.trim().isEmpty())
                ? idempotencyKey.trim()
                : reqKey;

        Object paymentLock = WEBHOOK_PAYMENT_LOCKS.computeIfAbsent(trimmedPaymentId, k -> new Object());
        synchronized (paymentLock) {
            String eventId = UUID.randomUUID().toString();
            try (Connection connection = OrderManager.getConnection()) {
                String checkSql = "SELECT event_id, status FROM autopix_webhook_events WHERE idempotency_key = ? OR idempotency_key = ?;";
                try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
                    checkStmt.setString(1, key);
                    checkStmt.setString(2, reqKey);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        String reusableEventId = null;
                        while (rs.next()) {
                            String existingStatus = rs.getString("status");
                            if ("PROCESSED".equalsIgnoreCase(existingStatus)) {
                                return true;
                            }
                            if (reusableEventId == null) {
                                reusableEventId = rs.getString("event_id");
                            }
                        }
                        if (reusableEventId != null) {
                            eventId = reusableEventId;
                            try (PreparedStatement updateProcessing = connection.prepareStatement(
                                    "UPDATE autopix_webhook_events SET status = 'PROCESSING', last_error = NULL WHERE event_id = ?;")) {
                                updateProcessing.setString(1, eventId);
                                updateProcessing.executeUpdate();
                            }
                        } else {
                            String insertSql = "INSERT INTO autopix_webhook_events "
                                    + "(event_id, idempotency_key, topic, payment_id, status, payload, processed_at, created_at) "
                                    + "VALUES (?, ?, ?, ?, 'PROCESSING', ?, NULL, CURRENT_TIMESTAMP);";
                            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                                insert.setString(1, eventId);
                                insert.setString(2, key);
                                insert.setString(3, topic != null ? topic : "payment");
                                insert.setString(4, trimmedPaymentId);
                                insert.setString(5, "payment_id=" + trimmedPaymentId
                                        + ";notification_key=" + key + ";request_id=" + trimmedRequestId);
                                insert.executeUpdate();
                            }
                        }
                    }
                }
            } catch (SQLException ex) {
                AutoPix plugin = AutoPix.getInstance();
                if (plugin != null) {
                    plugin.getLogger().warning("Erro ao registrar inicio de webhook para pagamento #"
                            + trimmedPaymentId + ": " + ex.getMessage());
                }
                return false;
            }

            boolean reconciled = false;
            String errorReason = null;
            try {
                reconciled = OrderManager.reconcilePaymentById(AutoPix.getInstance(), trimmedPaymentId);
                if (!reconciled) {
                    errorReason = "Reconciliacao recusada pelo provedor ou validacao de pagamento.";
                }
            } catch (Exception ex) {
                errorReason = ex.getMessage();
                reconciled = false;
            }

            try (Connection connection = OrderManager.getConnection()) {
                if (reconciled) {
                    String completeSql = "UPDATE autopix_webhook_events "
                            + "SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP, last_error = NULL "
                            + "WHERE event_id = ?;";
                    try (PreparedStatement stmt = connection.prepareStatement(completeSql)) {
                        stmt.setString(1, eventId);
                        if (stmt.executeUpdate() <= 0) {
                            return false;
                        }
                    }
                    return true;
                } else {
                    String failSql = "DELETE FROM autopix_webhook_events WHERE event_id = ? AND status <> 'PROCESSED';";
                    try (PreparedStatement stmt = connection.prepareStatement(failSql)) {
                        stmt.setString(1, eventId);
                        stmt.executeUpdate();
                    }
                    return false;
                }
            } catch (SQLException ex) {
                AutoPix plugin = AutoPix.getInstance();
                if (plugin != null) {
                    plugin.getLogger().warning("Erro ao finalizar status de webhook para pagamento #"
                            + trimmedPaymentId + " (" + errorReason + "): " + ex.getMessage());
                }
                return false;
            }
        }
    }

    // =========================================================================
    // Legacy / Bukkit Menu Compatibility Methods
    // =========================================================================

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

    public static boolean checkout(Player player, boolean giveMap) {
        requireMainThread();
        AutoPix plugin = AutoPix.getInstance();
        if (!isAvailable() || plugin == null || player == null || !player.isOnline()) return false;

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
        final UUID playerUuid = player.getUniqueId();
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
                if (!plugin.isEnabled() || !OrderManager.isDatabaseReady()) return;

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

    private static void fireCheckoutFailed(String playerName, UUID playerUuid, String reason) {
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
                .replace("{preco}", String.format(Locale.ROOT, "%.2f", item.getLineTotal()).replace('.', ','))
                .replace('&', '§');
    }
}
