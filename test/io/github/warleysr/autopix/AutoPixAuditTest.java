package io.github.warleysr.autopix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.warleysr.autopix.api.AutoPixAPI;
import io.github.warleysr.autopix.api.Cart;
import io.github.warleysr.autopix.api.CartResult;
import io.github.warleysr.autopix.api.CheckoutRequest;
import io.github.warleysr.autopix.api.OrderCreationResult;
import io.github.warleysr.autopix.api.OrderStatus;
import io.github.warleysr.autopix.api.Payment;
import io.github.warleysr.autopix.api.PaymentStatus;
import io.github.warleysr.autopix.api.Subscription;
import io.github.warleysr.autopix.api.SubscriptionInvoice;
import io.github.warleysr.autopix.api.SubscriptionResult;
import io.github.warleysr.autopix.api.SubscriptionStatus;
import io.github.warleysr.autopix.cart.CartManager;
import io.github.warleysr.autopix.catalog.CatalogManager;
import io.github.warleysr.autopix.catalog.Category;
import io.github.warleysr.autopix.catalog.CustomDonationConfig;
import io.github.warleysr.autopix.catalog.DeliveryConfig;
import io.github.warleysr.autopix.catalog.DeliveryType;
import io.github.warleysr.autopix.catalog.Offer;
import io.github.warleysr.autopix.catalog.OfferType;
import io.github.warleysr.autopix.catalog.Product;
import io.github.warleysr.autopix.delivery.DeliveryManager;
import io.github.warleysr.autopix.discord.DiscordNotifier;
import io.github.warleysr.autopix.domain.CartItem;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.PaymentInfo;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.DeliveryCompletedEvent;
import io.github.warleysr.autopix.events.DeliveryNeedsReviewEvent;
import io.github.warleysr.autopix.events.DeliveryStartedEvent;
import io.github.warleysr.autopix.events.OrderCreatedEvent;
import io.github.warleysr.autopix.events.PaymentApprovedEvent;
import io.github.warleysr.autopix.events.PaymentCancelledEvent;
import io.github.warleysr.autopix.events.PaymentRejectedEvent;
import io.github.warleysr.autopix.events.SubscriptionCancelledEvent;
import io.github.warleysr.autopix.events.SubscriptionCreatedEvent;
import io.github.warleysr.autopix.events.SubscriptionPaymentFailedEvent;
import io.github.warleysr.autopix.events.SubscriptionRenewedEvent;
import io.github.warleysr.autopix.expansion.AutoPixExpansion;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.github.warleysr.autopix.mercadopago.WebhookValidator;
import io.github.warleysr.autopix.qrcode.ImageCreator;
import io.github.warleysr.autopix.qrcode.PixGenerator;
import io.github.warleysr.autopix.subscription.SubscriptionManager;

class AutoPixAuditTest {

    @TempDir
    Path tempDir;

    private File sqliteFile;
    private final Map<String, PaymentInfo> providerPayments = new ConcurrentHashMap<>();
    private final AtomicInteger generatedPaymentCounter = new AtomicInteger(1000);

    @BeforeEach
    void setUp() throws Exception {
        sqliteFile = tempDir.resolve("autopix-test.db").toFile();
        initSqlitePool(sqliteFile);
        AutoPixEventDispatcher.clearTestListeners();
        providerPayments.clear();
        MercadoPagoAPI.clearTestOverrides();
        AutoPixAPI.setWebhookSecretForTesting(null);
        CartManager.clearAll();

        File catalogFile = new File("catalog.yml");
        if (catalogFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(catalogFile);
            ConfigurationSection root = yaml.isConfigurationSection("catalog")
                    ? yaml.getConfigurationSection("catalog")
                    : yaml;
            CatalogManager.setSnapshotForTesting(CatalogManager.parseAndValidate(root, null));
        }
    }

    @AfterEach
    void tearDown() {
        MercadoPagoAPI.clearTestOverrides();
        AutoPixAPI.setWebhookSecretForTesting(null);
        CartManager.clearAll();
        OrderManager.shutdown();
        AutoPixEventDispatcher.clearTestListeners();
    }

    private static void initSqlitePool(File dbFile) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("AutoPix-Test-SQLite");
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setConnectionInitSql("PRAGMA busy_timeout = 5000;");
        OrderManager.initializePool(cfg, "sqlite");
    }

    private void enableProviderHooks() {
        MercadoPagoAPI.setPaymentCreationOverride((ap, playerName, productName, price, externalReference, idempotencyKey) -> {
            String payId = "mp_pay_" + generatedPaymentCounter.incrementAndGet();
            String qr = "00020101021226580014BR.GOV.BCB.PIX01" + payId;
            providerPayments.put(payId, new PaymentInfo(
                    payId, null, "pending", price, "BRL", externalReference));
            return new PixData(payId, qr);
        });
        MercadoPagoAPI.setPaymentLookupOverride((ap, paymentId) -> providerPayments.get(paymentId));
    }

    // =========================================================================
    // Preserved Core Tests
    // =========================================================================

    @Test
    void testCartOrderSnapshotsSurvivePoolRestart() throws Exception {
        PixData pixData = new PixData("PAY-CART-SNAPSHOT", "QR-CART-SNAPSHOT");
        List<OrderItem> items = List.of(
                new OrderItem(
                        "vip_ouro", "30d", "VIP Ouro", "Acesso VIP Ouro por 30 dias", "vips",
                        "TEMPORARY", "30d", "BRL", 2,
                        new BigDecimal("12.50"), new BigDecimal("25.00"),
                        "hybrid", "vip_ouro_handler", "{\"tier\":\"gold\"}",
                        List.of("say pre {player} {quantidade}"),
                        List.of("lp user {player} parent add vip_ouro")),
                new OrderItem(
                        "chave_lendaria", "default", "Chave Lendaria", "Chave da caixa lendaria", "gems",
                        "ONE_TIME", null, "BRL", 1,
                        new BigDecimal("14.99"), new BigDecimal("14.99"),
                        "autopix", null, "{\"crate\":\"legendary\"}",
                        List.of(),
                        List.of("crate key give {player} lendaria {quantidade}")));

        Order created = OrderManager.createOrderWithItems(
                "PlayerCarrinho", "Carrinho (3 itens)", 39.99f, pixData, items);
        assertNotNull(created);
        assertEquals(2, OrderManager.getOrderItems(created.getId()).size());

        OrderManager.shutdown();
        initSqlitePool(sqliteFile);

        List<OrderItem> restored = OrderManager.getOrderItems(created.getId());
        assertEquals(2, restored.size());

        OrderItem first = restored.get(0);
        assertEquals("vip_ouro", first.getProductId());
        assertEquals("30d", first.getOfferId());
        assertEquals("VIP Ouro", first.getProduct());
        assertEquals("Acesso VIP Ouro por 30 dias", first.getDescription());
        assertEquals("vips", first.getCategory());
        assertEquals("temporary", first.getOfferType());
        assertEquals("30d", first.getDuration());
        assertEquals("BRL", first.getCurrency());
        assertEquals(2, first.getQuantity());
        assertEquals(new BigDecimal("12.50"), first.getUnitPriceDecimal());
        assertEquals(new BigDecimal("25.00"), first.getLineTotalDecimal());
        assertEquals("hybrid", first.getDeliveryType());
        assertEquals("vip_ouro_handler", first.getDeliveryHandler());
        assertEquals("{\"tier\":\"gold\"}", first.getDeliverySnapshot());
        assertEquals("say pre {player} {quantidade}", first.getPreCommands().get(0));
        assertEquals("lp user {player} parent add vip_ouro", first.getCommands().get(0));

        OrderItem second = restored.get(1);
        assertEquals("chave_lendaria", second.getProductId());
        assertEquals("Chave Lendaria", second.getProduct());
        assertEquals(1, second.getQuantity());
        assertEquals("crate key give {player} lendaria {quantidade}", second.getCommands().get(0));
        assertEquals(39.99f, OrderManager.getOrderById(created.getId()).getPrice(), 0.001f);
    }

    @Test
    void testDatabaseIdDefinitionsAndAtomicOrderCreationWithLongFields() {
        assertEquals("INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY",
                OrderManager.getIdDefinition("postgresql"));
        assertEquals("INTEGER PRIMARY KEY AUTO_INCREMENT",
                OrderManager.getIdDefinition("mysql"));
        assertEquals("INTEGER PRIMARY KEY AUTOINCREMENT",
                OrderManager.getIdDefinition("sqlite"));

        String longProductName = "VIP_SUPREMO_ETERNAL_2026_COMBO";
        String longQrCode = "00020101021226940014BR.GOV.BCB.PIX2572pix.mercadopago.com/qr/v2/"
                + "a".repeat(550) + "6304ABCD";
        PixData pixData = new PixData("9876543210", longQrCode);

        Order created = OrderManager.createOrderWithPixData("JogadorTeste", longProductName, 49.90f, pixData);
        assertNotNull(created);
        assertTrue(created.getId() > 0);
        assertEquals(created.getId(), pixData.getOrderId());

        Order loaded = OrderManager.getOrderById(created.getId());
        assertNotNull(loaded);
        assertEquals("JogadorTeste", loaded.getPlayer());
        assertEquals(longProductName, loaded.getProduct());
        assertEquals(49.90f, loaded.getPrice(), 0.001f);
        assertFalse(loaded.isValidated());

        PixData loadedPix = OrderManager.getPixData(loaded);
        assertNotNull(loadedPix);
        assertEquals("9876543210", loadedPix.getPaymentId());
        assertEquals(longQrCode, loadedPix.getQrCode());
        assertTrue(loadedPix.isPending());
    }

    @Test
    void testApprovedPaymentCreatesPersistentDeliveryOutbox() throws Exception {
        PixData pixData = new PixData("PAY-OUTBOX-1", "QR-OUTBOX-1");
        Order order = OrderManager.createOrderWithPixData("PlayerOutbox", "VIP1", 12.50f, pixData);
        assertNotNull(order);

        assertTrue(OrderManager.approvePayment(
                pixData, order, "E5555555555555555555555555555555"));

        String sql = "SELECT status, attempts FROM autopix_deliveries WHERE order_id = ?";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, order.getId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "A aprovacao precisa criar um registro persistente de entrega.");
                assertEquals("pending", result.getString("status"));
                assertEquals(0, result.getInt("attempts"));
            }
        }

        OrderManager.shutdown();
        initSqlitePool(sqliteFile);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, order.getId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("pending", result.getString("status"));
            }
        }
    }

    @Test
    void testPixPayloadAndQrCodeGeneration() throws Exception {
        String payloadShortKey = PixGenerator.generatePayload("123456789", "Servidor", "VIP1", 10.50f);
        assertNotNull(payloadShortKey);
        assertTrue(payloadShortKey.contains("0014BR.GOV.BCB.PIX0109123456789"));

        BufferedImage qrImage = ImageCreator.generateQR(payloadShortKey);
        assertNotNull(qrImage);
        assertEquals(128, qrImage.getWidth());
        assertEquals(128, qrImage.getHeight());
    }

    @Test
    void testMercadoPagoPayloadAndResponseParsing() throws Exception {
        String jsonWithoutNotif = MercadoPagoAPI.buildCreatePaymentJson("Steve", "VIP1", 19.99f, "");
        JSONObject parsedWithout = (JSONObject) new JSONParser().parse(jsonWithoutNotif);
        assertFalse(parsedWithout.containsKey("notification_url"));
        assertEquals("VIP1", parsedWithout.get("description"));

        String jsonWithNotif = MercadoPagoAPI.buildCreatePaymentJson(
                "Steve", "VIP1", 19.99f, "https://example.com/webhook");
        JSONObject parsedWith = (JSONObject) new JSONParser().parse(jsonWithNotif);
        assertEquals("https://example.com/webhook", parsedWith.get("notification_url"));

        String mpApprovedResponse = "{"
                + "\"id\": 123456,"
                + "\"status\": \"approved\","
                + "\"currency_id\": \"BRL\","
                + "\"external_reference\": \"order:42\","
                + "\"transaction_amount\": 19.99,"
                + "\"transaction_details\": {"
                + "  \"transaction_id\": \"PIXE00416968202301162037NF2oRtQ73bY\","
                + "  \"total_paid_amount\": 19.99"
                + "}"
                + "}";
        PaymentInfo info = MercadoPagoAPI.parsePaymentInfo("123456", mpApprovedResponse);
        assertNotNull(info);
        assertEquals("approved", info.getStatus());
        assertEquals("E00416968202301162037NF2oRtQ73bY", info.getTransactionId());
        assertEquals(19.99, info.getPaidAmount(), 0.001);
        assertEquals(new BigDecimal("19.99"), info.getPaidAmountDecimal());
        assertEquals("BRL", info.getCurrencyId());
        assertEquals("order:42", info.getExternalReference());

        String mpApprovedWithoutTxId = "{"
                + "\"id\": 777888,"
                + "\"status\": \"approved\","
                + "\"transaction_amount\": 5.00,"
                + "\"transaction_details\": {\"total_paid_amount\": 5.00}"
                + "}";
        PaymentInfo fallbackInfo = MercadoPagoAPI.parsePaymentInfo("777888", mpApprovedWithoutTxId);
        assertNotNull(fallbackInfo);
        assertEquals("MP-777888", fallbackInfo.getTransactionId());
    }

    @Test
    void testDiscountRoundingAndAmountMismatchDetection() {
        float originalPrice = 19.99f;
        int discountPct = 15;
        float discountedPrice = OrderManager.roundPrice((1.0 - (discountPct / 100.0)) * originalPrice);
        assertEquals(16.99f, discountedPrice, 0.0001f);
        assertEquals(1699L, OrderManager.toCents(discountedPrice));
        assertEquals(OrderManager.toCents(discountedPrice), OrderManager.toCents(16.99));
        assertNotEquals(OrderManager.toCents(discountedPrice), OrderManager.toCents(16.98));
    }

    @Test
    void testConcurrentIdempotentApprovalPreventsDuplicateReward() throws Exception {
        PixData pixData = new PixData("PAY-CONCURRENT-1", "000201010212...");
        Order order = OrderManager.createOrderWithPixData("Alex", "VIP1", 25.00f, pixData);
        assertNotNull(order);

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    PixData localPd = new PixData("PAY-CONCURRENT-1", "000201010212...");
                    localPd.setOrderId(order.getId());
                    Order localOrder = new Order("Alex", "VIP1", 25.00f, order.getCreated().getTime());
                    localOrder.setId(order.getId());
                    if (OrderManager.approvePayment(localPd, localOrder, "E1111111111111111111111111111111")) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        assertEquals(1, successCount.get(), "Exatamente 1 thread deve aprovar o pagamento (idempotencia estrita).");

        PixData afterPix = OrderManager.getPixData(order);
        assertNotNull(afterPix);
        assertEquals("approved", afterPix.getStatus());

        assertFalse(OrderManager.cancelPendingPixData(afterPix),
                "Um pagamento ja aprovado jamais pode ser cancelado por expiracao.");
        assertFalse(OrderManager.setPixDataStatus(afterPix, "cancelled"),
                "setPixDataStatus nao pode sobrescrever status approved.");
        assertEquals("approved", OrderManager.getPixData(order).getStatus());
    }

    @Test
    void testRejectionCancellationAndPendingRecoveryAcrossRestart() throws Exception {
        PixData rejectedPd = new PixData("PAY-REJ-1", "QR-REJ-1");
        Order rejOrder = OrderManager.createOrderWithPixData("PlayerRej", "VIP1", 10.0f, rejectedPd);
        assertTrue(OrderManager.setPixDataStatus(rejectedPd, "rejected"));
        assertEquals("rejected", OrderManager.getPixData(rejOrder).getStatus());

        PixData pendingPd = new PixData("PAY-PEND-2", "QR-PEND-2");
        Order pendOrder = OrderManager.createOrderWithPixData("PlayerPend", "VIP1", 15.0f, pendingPd);
        assertEquals(1, OrderManager.getAllPendingPixData().size());

        OrderManager.shutdown();
        initSqlitePool(sqliteFile);

        List<PixData> recoveredPendings = OrderManager.getAllPendingPixData();
        assertEquals(1, recoveredPendings.size());
        assertEquals("PAY-PEND-2", recoveredPendings.get(0).getPaymentId());

        assertTrue(OrderManager.approvePayment(
                recoveredPendings.get(0), pendOrder, "E2222222222222222222222222222222"));
        assertEquals(0, OrderManager.getAllPendingPixData().size());
    }

    @Test
    void testPlaceholderExpansionAndStatsCache() {
        PixData pd1 = new PixData("PAY-STATS-1", "QR-1");
        Order o1 = OrderManager.createOrderWithPixData("Gabi", "VIP1", 30.00f, pd1);
        assertTrue(OrderManager.approvePayment(pd1, o1, "E3333333333333333333333333333333"));

        PixData pd2 = new PixData("PAY-STATS-2", "QR-2");
        Order o2 = OrderManager.createOrderWithPixData("Gabi", "VIP1", 20.50f, pd2);
        assertTrue(OrderManager.approvePayment(pd2, o2, "E4444444444444444444444444444444"));

        PixData pd3 = new PixData("PAY-STATS-3", "QR-3");
        OrderManager.createOrderWithPixData("OutroJogador", "VIP1", 10.00f, pd3);

        OrderManager.refreshStatsCacheNow(5);

        AutoPixExpansion expansion = new AutoPixExpansion();
        assertEquals("50,50", expansion.onRequest(null, "total_arrecadado"));
        assertEquals("R$ 50,50", expansion.onRequest(null, "total_arrecadado_formatado"));
        assertEquals("2", expansion.onRequest(null, "pagamentos_aprovados"));
        assertEquals("1", expansion.onRequest(null, "pedidos_pendentes"));
        assertEquals("Gabi", expansion.onRequest(null, "top_1_nome"));
        assertEquals("50,50", expansion.onRequest(null, "top_1_valor"));
        assertEquals("2", expansion.onRequest(null, "top_1_compras"));
        assertEquals("---", expansion.onRequest(null, "top_2_nome"));
    }

    @Test
    void testBukkitEventsAndDiscordWebhookJson() throws Exception {
        List<Event> captured = new ArrayList<>();
        AutoPixEventDispatcher.registerTestListener(captured::add);

        UUID uuid = UUID.randomUUID();
        AutoPixEventDispatcher.dispatch(new OrderCreatedEvent(1, "Gabi", uuid, "VIP1", 10.0, "PAY-1", "pending", 1000L));
        AutoPixEventDispatcher.dispatch(new PaymentApprovedEvent(1, "Gabi", uuid, "VIP1", 10.0, "PAY-1", "E123"));
        AutoPixEventDispatcher.dispatch(new PaymentRejectedEvent(2, "Gabi", uuid, "VIP1", 10.0, 5.0, "PAY-2", "amount_mismatch", "amount_mismatch"));
        AutoPixEventDispatcher.dispatch(new PaymentCancelledEvent(3, "Gabi", uuid, "VIP1", 10.0, "PAY-3", "cancelled", "manual"));

        assertEquals(4, captured.size());
        assertTrue(captured.get(0) instanceof OrderCreatedEvent);
        assertTrue(captured.get(1) instanceof PaymentApprovedEvent);
        assertTrue(captured.get(2) instanceof PaymentRejectedEvent);
        assertTrue(captured.get(3) instanceof PaymentCancelledEvent);

        String webhookJson = DiscordNotifier.buildWebhookJson("AutoPix", "Titulo", "Descricao", 3066993);
        JSONObject parsed = (JSONObject) new JSONParser().parse(webhookJson);
        assertEquals("AutoPix", parsed.get("username"));
        assertNotNull(parsed.get("embeds"));
    }

    @Test
    void testLegacyMaterialResolution() {
        assertEquals(Material.RED_WOOL, InventoryManager.resolveMaterial("wool", 14));
        assertEquals(Material.LIME_WOOL, InventoryManager.resolveMaterial("wool", 5));
        assertEquals(Material.DIAMOND_SWORD, InventoryManager.resolveMaterial("diamond_sword", 0));
    }

    @Test
    void testCatalogDomainModels() {
        DeliveryConfig delivery = new DeliveryConfig(DeliveryType.HYBRID, List.of("lp user {player} parent add vip"), "custom_vip");
        Offer offer = new Offer("monthly", "vip_emerald", OfferType.SUBSCRIPTION, new BigDecimal("49.90"), "30d", true);
        Product product = new Product("vip_emerald", "VIP Esmeralda", "vips", true, new BigDecimal("49.90"), List.of(offer), delivery);
        Category category = new Category("vips", "VIPs e Planos", true, List.of("vip_emerald"));

        assertEquals("vip_emerald", product.getId());
        assertEquals("VIP Esmeralda", product.getName());
        assertEquals("vips", product.getCategory());
        assertEquals(new BigDecimal("49.90"), product.getPrice());
        assertEquals(DeliveryType.HYBRID, product.getDeliveryType());
        assertEquals("custom_vip", product.getDelivery().getHandler());
        assertEquals(1, product.getOffers().size());
        assertEquals(OfferType.SUBSCRIPTION, offer.getType());
        assertEquals("vips", category.getId());
        assertEquals("VIPs e Planos", category.getName());
    }

    @Test
    void testPersistentCartAcrossDatabaseRestart() {
        UUID playerUuid = UUID.randomUUID();
        DeliveryConfig deliv = new DeliveryConfig(DeliveryType.AUTOPIX, List.of("give {player} diamond 64"), null);
        Offer offer = new Offer("default", "gem_pack", OfferType.ONE_TIME, new BigDecimal("19.99"), null, true);
        Product product = new Product("gem_pack", "Pacote de Gemas", "gems", true, new BigDecimal("19.99"), List.of(offer), deliv);
        CatalogManager.registerProductForTesting(product);

        CartManager.clearCart(playerUuid);
        var result = CartManager.addToCart(playerUuid, "gem_pack", "default", 3);
        assertTrue(result.isSuccessful());

        Cart cartBefore = CartManager.getCart(playerUuid);
        assertEquals(3, cartBefore.getItemCount());
        assertEquals(new BigDecimal("59.97"), cartBefore.getSubtotal());
        assertEquals(new BigDecimal("59.97"), cartBefore.getTotal());

        OrderManager.shutdown();
        try {
            initSqlitePool(sqliteFile);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        CartManager.clearAll();

        Cart cartAfter = CartManager.getCart(playerUuid);
        assertEquals(3, cartAfter.getItemCount());
        assertEquals(new BigDecimal("59.97"), cartAfter.getSubtotal());
        assertEquals(new BigDecimal("59.97"), cartAfter.getTotal());
        assertFalse(cartAfter.getItems().isEmpty());
        CartItem restoredItem = cartAfter.getItems().iterator().next();
        assertEquals("gem_pack", restoredItem.getProductId());
        assertEquals(3, restoredItem.getQuantity());
    }

    @Test
    void testDetailedOrderSnapshotAndIdempotency() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        OrderItem item = new OrderItem("vip_gold", "default", "VIP Gold", "vips", 1,
                new BigDecimal("35.00"), new BigDecimal("35.00"), "autopix", null, "30d",
                List.of(), List.of("lp user {player} parent add gold"));

        Instant exp = Instant.now().plusSeconds(1800);
        Order order = OrderManager.createOrderDetailed(
                "GabiPlayer", playerUuid, "VIP Gold", new BigDecimal("35.00"),
                new BigDecimal("35.00"), BigDecimal.ZERO, "NATAL10", exp, null, List.of(item));

        assertNotNull(order);
        assertTrue(order.getId() > 0);
        assertEquals(playerUuid, order.getPlayerUuid());
        assertEquals("NATAL10", order.getCouponSnapshot());
        assertEquals(new BigDecimal("35.00"), order.getTotal());

        Payment payment = new Payment("pay_detail_1", "pay_detail_1", PaymentStatus.PENDING, "QR_DETAIL", "COPY_DETAIL", null, exp);
        assertTrue(OrderManager.savePayment(payment, order.getId(), new BigDecimal("35.00")));

        Payment retrieved = OrderManager.getPayment("pay_detail_1");
        assertNotNull(retrieved);
        assertEquals(PaymentStatus.PENDING, retrieved.getStatus());
        assertEquals("COPY_DETAIL", retrieved.getCopyPasteCode());

        boolean approved = OrderManager.approveManualOrder(order, "TRANS_DETAIL_999");
        assertTrue(approved);

        Order reloadedOrder = OrderManager.getOrderById(order.getId());
        assertNotNull(reloadedOrder);
        assertEquals("TRANS_DETAIL_999", reloadedOrder.getTransaction());
        assertEquals(OrderStatus.PAID, reloadedOrder.getStatus());
    }

    // =========================================================================
    // 4.1. Subscription Real Billing & Lifecycle Tests
    // =========================================================================

    @Test
    void testSubscriptionCreationRemainsPendingUntilProviderConfirmsPayment() {
        enableProviderHooks();
        UUID playerUuid = UUID.randomUUID();
        DeliveryConfig deliv = new DeliveryConfig(DeliveryType.HYBRID, List.of("say sub {player}"), "vip_sub_handler");
        Offer offer = new Offer("monthly", "sub_vip", OfferType.SUBSCRIPTION, new BigDecimal("29.90"), "30d", true);
        Product product = new Product("sub_vip", "VIP Mensal Recorrente", "vips", true, new BigDecimal("29.90"), List.of(offer), deliv);
        CatalogManager.registerProductForTesting(product);

        // 1. Creation without prior payment MUST keep subscription and invoice in PENDING state
        SubscriptionResult created = SubscriptionManager.createSubscription(playerUuid, "sub_vip", "monthly");
        assertTrue(created.isSuccessful());
        Subscription sub = created.getSubscription();
        SubscriptionInvoice initialInvoice = created.getInvoice();
        Order initialOrder = created.getOrder();
        Payment initialPayment = created.getPayment();

        assertNotNull(sub);
        assertNotNull(initialInvoice);
        assertNotNull(initialOrder);
        assertNotNull(initialPayment);
        assertEquals(SubscriptionStatus.PENDING, sub.getStatus(), "Assinatura jamais pode nascer ACTIVE sem pagamento.");
        assertNull(sub.getNextBillingAt(), "Assinatura PENDING ainda nao possui periodo ativo.");
        assertEquals("PENDING", initialInvoice.getStatus(), "Fatura jamais pode nascer PAID.");
        assertNull(initialInvoice.getPaidAt());
        assertEquals(1, initialInvoice.getCycleNumber());
        assertEquals(new BigDecimal("29.90"), initialInvoice.getAmount());
        assertEquals(initialOrder.getId(), initialInvoice.getOrderId());

        // 2. Provider confirmation activates subscription and marks invoice PAID
        providerPayments.put(initialPayment.getId(), new PaymentInfo(
                initialPayment.getId(), "E_SUB_TX_001", "approved",
                new BigDecimal("29.90"), "BRL", Integer.toString(initialOrder.getId())));

        assertTrue(OrderManager.reconcilePaymentById(null, initialPayment.getId()));

        Subscription activeSub = SubscriptionManager.getSubscription(sub.getId());
        SubscriptionInvoice paidInvoice = SubscriptionManager.getInvoice(initialInvoice.getInvoiceId());
        assertNotNull(activeSub);
        assertNotNull(paidInvoice);
        assertEquals(SubscriptionStatus.ACTIVE, activeSub.getStatus());
        assertNotNull(activeSub.getNextBillingAt());
        assertEquals("PAID", paidInvoice.getStatus());
        assertNotNull(paidInvoice.getPaidAt());

        Instant firstPeriodEnd = activeSub.getNextBillingAt();

        // 3. Renewal creates a PENDING invoice and real charge without advancing period prematurely
        SubscriptionResult renewalAttempt = SubscriptionManager.renewSubscription(sub.getId());
        assertTrue(renewalAttempt.isSuccessful());
        SubscriptionInvoice renewalInvoice = renewalAttempt.getInvoice();
        Payment renewalPayment = renewalAttempt.getPayment();
        Order renewalOrder = renewalAttempt.getOrder();

        assertNotNull(renewalInvoice);
        assertNotNull(renewalPayment);
        assertNotNull(renewalOrder);
        assertEquals(2, renewalInvoice.getCycleNumber());
        assertEquals("PENDING", renewalInvoice.getStatus());
        assertEquals(firstPeriodEnd, SubscriptionManager.getSubscription(sub.getId()).getNextBillingAt(),
                "O periodo da assinatura nao pode avancar antes da confirmacao do pagamento da renovacao.");

        // 4. Provider confirmation of renewal payment advances the period and marks invoice #2 PAID
        providerPayments.put(renewalPayment.getId(), new PaymentInfo(
                renewalPayment.getId(), "E_SUB_TX_002", "approved",
                new BigDecimal("29.90"), "BRL", Integer.toString(renewalOrder.getId())));

        assertTrue(OrderManager.reconcilePaymentById(null, renewalPayment.getId()));

        Subscription renewedSub = SubscriptionManager.getSubscription(sub.getId());
        SubscriptionInvoice paidRenewalInv = SubscriptionManager.getInvoice(renewalInvoice.getInvoiceId());
        assertEquals(SubscriptionStatus.ACTIVE, renewedSub.getStatus());
        assertEquals("PAID", paidRenewalInv.getStatus());
        assertTrue(renewedSub.getNextBillingAt().isAfter(firstPeriodEnd),
                "Apos pagamento confirmado da renovacao, o fim do periodo deve ser estendido.");
        assertEquals(2, SubscriptionManager.getSubscriptionInvoices(sub.getId()).size());
    }

    @Test
    void testSubscriptionPaymentFailureAndConcurrentRenewalProtection() throws Exception {
        enableProviderHooks();
        UUID playerUuid = UUID.randomUUID();
        DeliveryConfig deliv = new DeliveryConfig(DeliveryType.AUTOPIX, List.of("say sub {player}"), null);
        Offer offer = new Offer("monthly", "sub_vip_fail", OfferType.SUBSCRIPTION, new BigDecimal("39.90"), "30d", true);
        Product product = new Product("sub_vip_fail", "VIP Mensal", "vips", true, new BigDecimal("39.90"), List.of(offer), deliv);
        CatalogManager.registerProductForTesting(product);

        // Initial subscription with rejected payment stays unactivated (UNPAID)
        SubscriptionResult unpaidCreated = SubscriptionManager.createSubscription(playerUuid, "sub_vip_fail", "monthly");
        assertTrue(unpaidCreated.isSuccessful());
        String unpaidPayId = unpaidCreated.getPayment().getId();
        providerPayments.put(unpaidPayId, new PaymentInfo(
                unpaidPayId, null, "rejected", new BigDecimal("39.90"), "BRL", null));
        assertTrue(OrderManager.reconcilePaymentById(null, unpaidPayId));

        Subscription afterInitialReject = SubscriptionManager.getSubscription(unpaidCreated.getSubscription().getId());
        assertEquals(SubscriptionStatus.UNPAID, afterInitialReject.getStatus());
        assertEquals("REJECTED", SubscriptionManager.getInvoice(unpaidCreated.getInvoice().getInvoiceId()).getStatus());

        // Create and activate a second subscription, then test concurrent renewals & payment failure on renewal
        SubscriptionResult activeCreated = SubscriptionManager.createSubscription(UUID.randomUUID(), "sub_vip_fail", "monthly");
        String initPayId = activeCreated.getPayment().getId();
        providerPayments.put(initPayId, new PaymentInfo(
                initPayId, "E_SUB_INIT_OK", "approved", new BigDecimal("39.90"), "BRL", null));
        assertTrue(OrderManager.reconcilePaymentById(null, initPayId));
        String subId = activeCreated.getSubscription().getId();
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionManager.getSubscription(subId).getStatus());

        // Concurrent calls to renewSubscription must NOT create duplicate invoices or duplicate charges
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SubscriptionResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return SubscriptionManager.renewSubscription(subId);
            }));
        }
        ready.await();
        go.countDown();

        List<SubscriptionResult> results = new ArrayList<>();
        for (Future<SubscriptionResult> f : futures) {
            results.add(f.get());
        }
        pool.shutdownNow();

        List<SubscriptionInvoice> invoices = SubscriptionManager.getSubscriptionInvoices(subId);
        assertEquals(2, invoices.size(), "Chamadas concorrentes de renovacao jamais podem criar faturas duplicadas para o mesmo ciclo.");
        SubscriptionInvoice cycle2Invoice = invoices.get(0).getCycleNumber() == 2 ? invoices.get(0) : invoices.get(1);
        assertEquals("PENDING", cycle2Invoice.getStatus());

        // Rejecting the renewal payment moves ACTIVE subscription to PAST_DUE (never extends period)
        Payment cycle2Payment = OrderManager.getPaymentByOrderId(cycle2Invoice.getOrderId());
        assertNotNull(cycle2Payment);
        providerPayments.put(cycle2Payment.getId(), new PaymentInfo(
                cycle2Payment.getId(), null, "rejected", new BigDecimal("39.90"), "BRL", null));
        assertTrue(OrderManager.reconcilePaymentById(null, cycle2Payment.getId()));

        Subscription pastDueSub = SubscriptionManager.getSubscription(subId);
        assertEquals(SubscriptionStatus.PAST_DUE, pastDueSub.getStatus());
        assertEquals("REJECTED", SubscriptionManager.getInvoice(cycle2Invoice.getInvoiceId()).getStatus());

        // Cancellation test
        SubscriptionResult cancelled = SubscriptionManager.cancelSubscription(subId, true);
        assertTrue(cancelled.isSuccessful());
        assertEquals(SubscriptionStatus.CANCELLED, cancelled.getSubscription().getStatus());
        assertNotNull(cancelled.getSubscription().getCancelledAt());
    }

    // =========================================================================
    // 4.2. Webhooks, Signature Authentication & Independent Verification Tests
    // =========================================================================

    @Test
    void testWebhookAuthenticationAndIndependentProviderVerification() throws Exception {
        enableProviderHooks();
        String secret = "mp_webhook_test_secret_2026";
        AutoPixAPI.setWebhookSecretForTesting(secret);

        PixData pd = new PixData("mp_pay_webhook_1", "QR_WH_1");
        Order order = OrderManager.createOrderWithPixData("WebhookPlayer", "VIP1", 49.90f, pd);
        assertNotNull(order);

        String reqId = "req-111-222";
        long ts = System.currentTimeMillis() / 1000L;
        String validSignature = WebhookValidator.computeSignatureHeader(reqId, "mp_pay_webhook_1", ts, secret);

        // 1. Unauthenticated / invalid signature notification is refused
        assertFalse(AutoPixAPI.processWebhookNotification("payment", "mp_pay_webhook_1", "idemp_unauth"));
        assertFalse(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_webhook_1", "idemp_bad_sig", "ts=" + ts + ",v1=00000000", reqId, secret));

        // 2. Nonexistent paymentId in provider is refused and NOT marked as PROCESSED
        String ghostSig = WebhookValidator.computeSignatureHeader(reqId, "mp_pay_nonexistent", ts, secret);
        assertFalse(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_nonexistent", "idemp_ghost", ghostSig, reqId, secret));

        // 3. Transient provider failure on attempt #1 does NOT block retry on attempt #2
        providerPayments.remove("mp_pay_webhook_1"); // Simulates provider network failure / not found yet
        assertFalse(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_webhook_1", "idemp_retry_1", validSignature, reqId, secret));
        assertFalse(OrderManager.getOrderById(order.getId()).isValidated());

        // Now provider returns valid approved payment -> retry with SAME idempotencyKey succeeds!
        providerPayments.put("mp_pay_webhook_1", new PaymentInfo(
                "mp_pay_webhook_1", "E_WH_TX_1", "approved",
                new BigDecimal("49.90"), "BRL", Integer.toString(order.getId())));
        assertTrue(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_webhook_1", "idemp_retry_1", validSignature, reqId, secret));
        assertTrue(OrderManager.getOrderById(order.getId()).isValidated());

        // 4. Duplicate notification with same idempotencyKey is safely deduplicated
        assertTrue(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_webhook_1", "idemp_retry_1", validSignature, reqId, secret));

        // 5. Out-of-order late cancellation notification does NOT revert an already approved payment
        providerPayments.put("mp_pay_webhook_1", new PaymentInfo(
                "mp_pay_webhook_1", null, "cancelled",
                new BigDecimal("49.90"), "BRL", Integer.toString(order.getId())));
        String lateSig = WebhookValidator.computeSignatureHeader("req-late", "mp_pay_webhook_1", ts, secret);
        assertTrue(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_webhook_1", "idemp_late_cancel", lateSig, "req-late", secret));
        assertEquals(OrderStatus.PAID, OrderManager.getOrderById(order.getId()).getStatus());
        assertEquals("approved", OrderManager.getPixData(order).getStatus());
    }

    @Test
    void testWebhookRejectsAmountCurrencyAndReferenceMismatches() {
        enableProviderHooks();
        String secret = "mp_webhook_secret_mismatch";
        AutoPixAPI.setWebhookSecretForTesting(secret);
        long ts = System.currentTimeMillis() / 1000L;

        // 1. Amount mismatch
        PixData pdAmount = new PixData("mp_pay_amt_mis", "QR_AMT");
        Order orderAmount = OrderManager.createOrderWithPixData("PlayerAmt", "VIP1", 100.00f, pdAmount);
        providerPayments.put("mp_pay_amt_mis", new PaymentInfo(
                "mp_pay_amt_mis", "E_AMT_1", "approved",
                new BigDecimal("10.00"), "BRL", Integer.toString(orderAmount.getId())));
        String sigAmt = WebhookValidator.computeSignatureHeader("req-amt", "mp_pay_amt_mis", ts, secret);
        assertFalse(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_amt_mis", "idemp_amt", sigAmt, "req-amt", secret));
        assertFalse(OrderManager.getOrderById(orderAmount.getId()).isValidated());
        assertEquals("amount_mismatch", OrderManager.getPixData(orderAmount).getStatus());
        assertEquals(OrderStatus.FAILED, OrderManager.getOrderById(orderAmount.getId()).getStatus());

        // 2. Currency mismatch (USD instead of BRL)
        PixData pdCur = new PixData("mp_pay_cur_mis", "QR_CUR");
        Order orderCur = OrderManager.createOrderWithPixData("PlayerCur", "VIP1", 50.00f, pdCur);
        providerPayments.put("mp_pay_cur_mis", new PaymentInfo(
                "mp_pay_cur_mis", "E_CUR_1", "approved",
                new BigDecimal("50.00"), "USD", Integer.toString(orderCur.getId())));
        String sigCur = WebhookValidator.computeSignatureHeader("req-cur", "mp_pay_cur_mis", ts, secret);
        assertFalse(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_cur_mis", "idemp_cur", sigCur, "req-cur", secret));
        assertFalse(OrderManager.getOrderById(orderCur.getId()).isValidated());
        assertEquals("currency_mismatch", OrderManager.getPixData(orderCur).getStatus());

        // 3. External reference mismatch (pointing to another order)
        PixData pdRef = new PixData("mp_pay_ref_mis", "QR_REF");
        Order orderRef = OrderManager.createOrderWithPixData("PlayerRef", "VIP1", 50.00f, pdRef);
        providerPayments.put("mp_pay_ref_mis", new PaymentInfo(
                "mp_pay_ref_mis", "E_REF_1", "approved",
                new BigDecimal("50.00"), "BRL", "order:999999"));
        String sigRef = WebhookValidator.computeSignatureHeader("req-ref", "mp_pay_ref_mis", ts, secret);
        assertFalse(AutoPixAPI.processWebhookNotification(
                "payment", "mp_pay_ref_mis", "idemp_ref", sigRef, "req-ref", secret));
        assertFalse(OrderManager.getOrderById(orderRef.getId()).isValidated());
        assertEquals("reference_mismatch", OrderManager.getPixData(orderRef).getStatus());
    }

    // =========================================================================
    // 4.3. Cart, VIP Offers, Custom Donations & Checkout Idempotency Tests
    // =========================================================================

    @Test
    void testVipOffersSelectionAndRejectionOfInvalidOrZeroPriceOffers() {
        UUID playerUuid = UUID.randomUUID();
        DeliveryConfig deliv = new DeliveryConfig(DeliveryType.HYBRID, List.of("lp user {player} parent add vip"), "vip_activation");
        Offer offer30d = new Offer("30d", "vip_multi", OfferType.TEMPORARY, new BigDecimal("29.90"), "30d", true);
        Offer offer90d = new Offer("90d", "vip_multi", OfferType.TEMPORARY, new BigDecimal("74.90"), "90d", true);
        Offer offerLifetime = new Offer("lifetime", "vip_multi", OfferType.ONE_TIME, new BigDecimal("149.90"), null, true);
        Offer offerDisabled = new Offer("disabled_plan", "vip_multi", OfferType.TEMPORARY, new BigDecimal("9.90"), "7d", false);

        Product vipProduct = new Product(
                "vip_multi", "VIP Multi Ofertas", "VIP completo", "vips", true,
                BigDecimal.ZERO, List.of(offer30d, offer90d, offerLifetime, offerDisabled), deliv);
        CatalogManager.registerProductForTesting(vipProduct);

        assertTrue(vipProduct.hasOffers());
        assertEquals(new BigDecimal("29.90"), vipProduct.getDisplayPrice());

        // Adding "default" or nonexistent offer to a multi-offer VIP must fail (no silent fallback)
        CartResult badDefault = CartManager.addToCart(playerUuid, "vip_multi", "default", 1);
        assertFalse(badDefault.isSuccessful());

        CartResult badDisabled = CartManager.addToCart(playerUuid, "vip_multi", "disabled_plan", 1);
        assertFalse(badDisabled.isSuccessful());

        // Adding valid offers ("30d" and "lifetime") uses their exact configured prices
        CartResult add30d = CartManager.addToCart(playerUuid, "vip_multi", "30d", 1);
        assertTrue(add30d.isSuccessful());

        CartResult addLife = CartManager.addToCart(playerUuid, "vip_multi", "lifetime", 1);
        assertTrue(addLife.isSuccessful());

        Cart cart = CartManager.getCart(playerUuid);
        assertEquals(2, cart.getItemCount());
        assertEquals(new BigDecimal("179.80"), cart.getTotal());
    }

    @Test
    void testCustomDonationValidationAndCheckoutIdempotency() throws Exception {
        enableProviderHooks();
        CustomDonationConfig donationCfg = CatalogManager.getCustomDonationConfig();
        assertNotNull(donationCfg);
        assertTrue(donationCfg.isEnabled());

        // Valid amounts
        assertEquals(new BigDecimal("25.50"), donationCfg.validateAndNormalize("25,50"));
        assertEquals(new BigDecimal("100.00"), donationCfg.validateAndNormalize("100"));

        // Invalid amounts (below min, above max, zero, negative, >2 decimals, non-numeric)
        assertThrows(IllegalArgumentException.class, () -> donationCfg.validateAndNormalize("0.50"));
        assertThrows(IllegalArgumentException.class, () -> donationCfg.validateAndNormalize("5000.00"));
        assertThrows(IllegalArgumentException.class, () -> donationCfg.validateAndNormalize("0"));
        assertThrows(IllegalArgumentException.class, () -> donationCfg.validateAndNormalize("-15.00"));
        assertThrows(IllegalArgumentException.class, () -> donationCfg.validateAndNormalize("19.999"));
        assertThrows(IllegalArgumentException.class, () -> donationCfg.validateAndNormalize("abc"));

        // Server-side checkout for Custom Donation
        UUID donorUuid = UUID.randomUUID();
        CheckoutRequest validDonationReq = new CheckoutRequest(
                donorUuid, "idemp_donation_4275", null, false, new BigDecimal("42.75"), "custom_donation", null);
        OrderCreationResult res1 = AutoPixAPI.createOrder(validDonationReq).toCompletableFuture().get();
        assertTrue(res1.isSuccessful());
        assertNotNull(res1.getOrder());
        assertNotNull(res1.getPayment());
        assertEquals(new BigDecimal("42.75"), res1.getOrder().getTotal());

        // Checkout idempotency: repeating the same idempotencyKey returns the exact same Order without creating a duplicate
        OrderCreationResult res2 = AutoPixAPI.createOrder(validDonationReq).toCompletableFuture().get();
        assertTrue(res2.isSuccessful());
        assertEquals(res1.getOrder().getId(), res2.getOrder().getId());

        // Invalid custom donation in CheckoutRequest is rejected by AutoPixAPI.createOrder
        CheckoutRequest invalidDonationReq = CheckoutRequest.customDonation(donorUuid, new BigDecimal("0.10"));
        OrderCreationResult badRes = AutoPixAPI.createOrder(invalidDonationReq).toCompletableFuture().get();
        assertFalse(badRes.isSuccessful());
        assertEquals("invalid_donation_amount", badRes.getErrorCode());
    }

    // =========================================================================
    // 4.4. Multi-Item Deliveries, Concurrency, Crash Recovery & Offline Reconnect
    // =========================================================================

    @Test
    void testMultiItemDeliveryConcurrencyAndModes() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        List<OrderItem> items = List.of(
                new OrderItem(
                        "money_small", "default", "Pacote Coins", "Coins", "money",
                        "ONE_TIME", null, "BRL", 1,
                        new BigDecimal("10.00"), new BigDecimal("10.00"),
                        "autopix", null, null,
                        List.of(), List.of("eco give {player} 100000")),
                new OrderItem(
                        "donation_heart", "default", "Doacao Coracao", "Doacao", "donations",
                        "ONE_TIME", null, "BRL", 1,
                        new BigDecimal("10.00"), new BigDecimal("10.00"),
                        "skript", "donation_heart_reward", null,
                        List.of(), List.of()),
                new OrderItem(
                        "vip_basic", "30d", "VIP Basic", "VIP 30d", "vips",
                        "TEMPORARY", "30d", "BRL", 1,
                        new BigDecimal("19.90"), new BigDecimal("19.90"),
                        "hybrid", "vip_basic_activation", null,
                        List.of(), List.of("lp user {player} parent addtemp vip 30d")));

        PixData pd = new PixData("PAY-MULTI-DELIV", "QR-MULTI");
        Order order = OrderManager.createOrderDetailed(
                "MultiPlayer", playerUuid, "Carrinho (3 itens)",
                new BigDecimal("39.90"), new BigDecimal("39.90"), BigDecimal.ZERO,
                null, Instant.now().plusSeconds(1800), pd, items);
        assertNotNull(order);
        assertTrue(OrderManager.approvePayment(pd, order, "E_MULTI_TX_001"));

        List<String> executedCommands = new CopyOnWriteArrayList<>();
        List<DeliveryStartedEvent> startedEvents = new CopyOnWriteArrayList<>();
        List<DeliveryCompletedEvent> completedEvents = new CopyOnWriteArrayList<>();

        AutoPixEventDispatcher.registerTestListener(ev -> {
            if (ev instanceof DeliveryStartedEvent) startedEvents.add((DeliveryStartedEvent) ev);
            if (ev instanceof DeliveryCompletedEvent) completedEvents.add((DeliveryCompletedEvent) ev);
        });

        // Execute delivery concurrently across 12 threads
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalDelivered = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int count = DeliveryManager.processOrderDeliveryItemsSync(
                            order, items, playerUuid, executedCommands::add);
                    totalDelivered.addAndGet(count);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        // All 3 items delivered exactly once across all threads
        assertEquals(3, totalDelivered.get(), "Cada um dos 3 itens deve ser entregue exatamente 1 vez.");
        assertEquals(2, executedCommands.size(), "Apenas os itens AUTOPIX e HYBRID possuem comandos de console.");
        assertEquals(3, startedEvents.size());
        assertEquals(3, completedEvents.size());
    }

    @Test
    void testInterruptedDeliveryRecoveryStagesPreventBlindDuplicateExecution() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        List<OrderItem> items = List.of(
                new OrderItem("item_pre_exec", "default", "Item Pre Exec", "money", 1,
                        new BigDecimal("10.00"), new BigDecimal("10.00"), "autopix", null, null,
                        List.of(), List.of("eco give {player} 500")),
                new OrderItem("item_mid_exec", "default", "Item Mid Exec", "money", 1,
                        new BigDecimal("10.00"), new BigDecimal("10.00"), "autopix", null, null,
                        List.of(), List.of("eco give {player} 1000")));

        PixData pd = new PixData("PAY-RECOVERY-STAGE", "QR-REC");
        Order order = OrderManager.createOrderDetailed(
                "CrashPlayer", playerUuid, "Carrinho Crash",
                new BigDecimal("20.00"), new BigDecimal("20.00"), BigDecimal.ZERO,
                null, Instant.now().plusSeconds(1800), pd, items);
        assertTrue(OrderManager.approvePayment(pd, order, "E_CRASH_TX_1"));

        // Simulate crash:
        // Item 0 crashed in STAGE_CLAIMED (before any command ran) -> safe to reset to 'pending'
        // Item 1 crashed in STAGE_EXECUTING_COMMANDS (command may have already run) -> must go to 'needs_review'
        try (Connection conn = OrderManager.getConnection();
             PreparedStatement stmt0 = conn.prepareStatement(
                     "UPDATE autopix_deliveries SET status = 'processing', execution_stage = ? WHERE order_id = ? AND item_index = 0;");
             PreparedStatement stmt1 = conn.prepareStatement(
                     "UPDATE autopix_deliveries SET status = 'processing', execution_stage = ? WHERE order_id = ? AND item_index = 1;")) {
            stmt0.setString(1, DeliveryManager.STAGE_CLAIMED);
            stmt0.setInt(2, order.getId());
            stmt0.executeUpdate();

            stmt1.setString(1, DeliveryManager.STAGE_EXECUTING_COMMANDS);
            stmt1.setInt(2, order.getId());
            stmt1.executeUpdate();
        }

        List<DeliveryNeedsReviewEvent> reviewEvents = new ArrayList<>();
        AutoPixEventDispatcher.registerTestListener(ev -> {
            if (ev instanceof DeliveryNeedsReviewEvent) reviewEvents.add((DeliveryNeedsReviewEvent) ev);
        });

        int movedToReview = DeliveryManager.recoverInterruptedDeliveriesNow();
        assertEquals(1, movedToReview, "Item interrompido durante execucao de comandos deve ir para needs_review.");
        assertEquals(1, reviewEvents.size());

        // Now run delivery for remaining pending items: only item 0 is delivered; item 1 is NOT re-executed
        List<String> executed = new ArrayList<>();
        int deliveredAfterRecovery = DeliveryManager.processOrderDeliveryItemsSync(order, items, playerUuid, executed::add);
        assertEquals(1, deliveredAfterRecovery);
        assertEquals(1, executed.size());
        assertEquals("eco give CrashPlayer 500", executed.get(0));
    }

    @Test
    void testOfflinePlayerPaymentApprovalAndDeliveryUponReconnect() {
        enableProviderHooks();
        UUID offlineUuid = UUID.randomUUID();
        String playerName = "OfflineBuyer";
        OrderManager.markPlayerOffline(playerName);

        List<OrderItem> items = List.of(
                new OrderItem("stars_small", "default", "50 Estrelas", "stars", 1,
                        new BigDecimal("19.90"), new BigDecimal("19.90"), "autopix", null, null,
                        List.of(), List.of("stars give {player} 50")));

        PixData pd = new PixData("mp_pay_offline_1", "QR_OFFLINE");
        Order order = OrderManager.createOrderDetailed(
                playerName, offlineUuid, "50 Estrelas",
                new BigDecimal("19.90"), new BigDecimal("19.90"), BigDecimal.ZERO,
                null, Instant.now().plusSeconds(1800), pd, items);
        assertNotNull(order);

        providerPayments.put("mp_pay_offline_1", new PaymentInfo(
                "mp_pay_offline_1", "E_OFFLINE_TX_1", "approved",
                new BigDecimal("19.90"), "BRL", Integer.toString(order.getId())));

        // Validate pendings while player is offline
        OrderManager.validatePendings(null);

        // Order MUST be approved and queued in autopix_deliveries as 'pending'
        Order approvedOrder = OrderManager.getOrderById(order.getId());
        assertTrue(approvedOrder.isValidated(), "Pedido de jogador offline deve ser validado e aprovado.");
        assertEquals(OrderStatus.PAID, approvedOrder.getStatus());

        List<Integer> pendingForPlayer = DeliveryManager.findPendingOrderIdsForPlayer(offlineUuid, playerName);
        assertTrue(pendingForPlayer.contains(order.getId()), "Entrega deve aguardar na fila persistente ate o jogador conectar.");

        // Player reconnects -> pending delivery is executed!
        OrderManager.markPlayerOnline(playerName, offlineUuid);
        List<String> reconnectCommands = new ArrayList<>();
        int delivered = DeliveryManager.processOrderDeliveryItemsSync(
                approvedOrder, OrderManager.getOrderItems(order.getId()), offlineUuid, reconnectCommands::add);
        assertEquals(1, delivered);
        assertEquals("stars give OfflineBuyer 50", reconnectCommands.get(0));
        assertTrue(DeliveryManager.findPendingOrderIdsForPlayer(offlineUuid, playerName).isEmpty());
    }

    // =========================================================================
    // 4.5. Database Migrations (Legacy Schema Upgrade & Data Preservation)
    // =========================================================================

    @Test
    void testLegacySchemaMigrationPreservesDataAndUpgradesDeliveriesPrimaryKey() throws Exception {
        File legacyDbFile = tempDir.resolve("legacy-autopix.db").toFile();
        String jdbcUrl = "jdbc:sqlite:" + legacyDbFile.getAbsolutePath();

        // 1. Create the old legacy schema manually (where autopix_deliveries had order_id INTEGER PRIMARY KEY)
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE autopix_orders ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "player VARCHAR(16) NOT NULL, "
                    + "product VARCHAR(32) NOT NULL, "
                    + "price DECIMAL(10, 2) NOT NULL, "
                    + "created TIMESTAMP NOT NULL, "
                    + "pix VARCHAR(64) UNIQUE NULL);");
            stmt.executeUpdate("CREATE TABLE autopix_pix_data ("
                    + "payment_id VARCHAR(128) PRIMARY KEY, "
                    + "order_id INTEGER NOT NULL UNIQUE, "
                    + "status VARCHAR(16), "
                    + "qr_code VARCHAR(512) NOT NULL UNIQUE);");
            stmt.executeUpdate("CREATE TABLE autopix_deliveries ("
                    + "order_id INTEGER PRIMARY KEY, "
                    + "status VARCHAR(32) NOT NULL, "
                    + "attempts INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at TIMESTAMP NOT NULL, "
                    + "last_error TEXT NULL);");

            // Insert legacy records
            stmt.executeUpdate("INSERT INTO autopix_orders (id, player, product, price, created, pix) "
                    + "VALUES (77, 'LegacyPlayer', 'VIP_ANTIGO', 25.00, CURRENT_TIMESTAMP, 'E_LEGACY_77');");
            stmt.executeUpdate("INSERT INTO autopix_pix_data (payment_id, order_id, status, qr_code) "
                    + "VALUES ('PAY_LEG_77', 77, 'approved', 'QR_LEG_77');");
            stmt.executeUpdate("INSERT INTO autopix_deliveries (order_id, status, attempts, updated_at, last_error) "
                    + "VALUES (77, 'completed', 1, CURRENT_TIMESTAMP, NULL);");
        }

        // 2. Run pool initialization + DatabaseMigrationRunner on the legacy database (and re-run to test idempotency)
        initSqlitePool(legacyDbFile);
        initSqlitePool(legacyDbFile);

        // 3. Verify legacy order and delivery were preserved
        Order legacyOrder = OrderManager.getOrderById(77);
        assertNotNull(legacyOrder);
        assertEquals("LegacyPlayer", legacyOrder.getPlayer());
        assertEquals("VIP_ANTIGO", legacyOrder.getProduct());
        assertEquals("E_LEGACY_77", legacyOrder.getTransaction());

        try (Connection conn = OrderManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT delivery_id, order_id, item_index, status FROM autopix_deliveries WHERE order_id = 77;")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "O registro legado em autopix_deliveries deve ser preservado na migracao.");
                assertEquals("deliv_77_0", rs.getString("delivery_id"));
                assertEquals("completed", rs.getString("status"));
            }
        }

        // 4. Verify upgraded autopix_deliveries now supports multiple items for the same order_id
        List<OrderItem> multiItems = List.of(
                new OrderItem("item_a", 1, 10.0f, List.of(), List.of("say a")),
                new OrderItem("item_b", 1, 15.0f, List.of(), List.of("say b")));
        PixData newPd = new PixData("PAY_AFTER_MIG", "QR_AFTER_MIG");
        Order newOrder = OrderManager.createOrderWithItems("NewPlayer", "Combo", 25.0f, newPd, multiItems);
        assertNotNull(newOrder);
        assertTrue(OrderManager.approvePayment(newPd, newOrder, "E_NEW_AFTER_MIG"));

        try (Connection conn = OrderManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) AS cnt FROM autopix_deliveries WHERE order_id = ?;")) {
            stmt.setInt(1, newOrder.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("cnt"), "Tabela migrada deve permitir multiplos itens de entrega por pedido.");
            }
        }
    }

    // =========================================================================
    // 4.6. Skript Integration & Static Contract Verification Tests
    // =========================================================================

    @Test
    void testSkriptFilesFormattingAndJavaApiAlignment() throws Exception {
        File skriptDir = new File("scripts/neorankup/loja");
        assertTrue(skriptDir.exists() && skriptDir.isDirectory(), "Diretorio scripts/neorankup/loja deve existir.");

        String[] expectedFiles = {
                "config.sk", "main.sk", "menus.sk", "categorias.sk",
                "carrinho.sk", "checkout.sk", "pagamentos.sk", "assinaturas.sk", "entregas.sk"
        };

        for (String fileName : expectedFiles) {
            File skFile = new File(skriptDir, fileName);
            assertTrue(skFile.exists(), "Arquivo Skript obrigatorio ausente: " + fileName);
            List<String> lines = Files.readAllLines(skFile.toPath(), StandardCharsets.UTF_8);
            assertFalse(lines.isEmpty(), "Arquivo Skript vazio: " + fileName);

            boolean insideIndentedBlock = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                assertFalse(trimmed.startsWith("#"),
                        "Arquivos .sk finais nao devem conter comentarios (" + fileName + ":" + (i + 1) + ")");

                if (trimmed.isEmpty()) {
                    // Blank line is only allowed before a top-level declaration (non-indented line)
                    if (i + 1 < lines.size()) {
                        String nextLine = lines.get(i + 1);
                        boolean nextIsTopLevel = !nextLine.isEmpty()
                                && !Character.isWhitespace(nextLine.charAt(0));
                        assertTrue(nextIsTopLevel || !insideIndentedBlock,
                                "Linha em branco interna encontrada dentro de bloco em " + fileName + ":" + (i + 1));
                    }
                } else {
                    insideIndentedBlock = Character.isWhitespace(line.charAt(0));
                }
            }
        }

        // Verify categorias.sk has abrirMenuOfertas and custom donation button
        String categoriasContent = Files.readString(new File(skriptDir, "categorias.sk").toPath(), StandardCharsets.UTF_8);
        assertTrue(categoriasContent.contains("function abrirMenuOfertas("));
        assertTrue(categoriasContent.contains(".hasOffers()"));
        assertTrue(categoriasContent.contains(".getDisplayPrice()"));
        assertTrue(categoriasContent.contains("iniciarDoacaoPersonalizada("));

        // Verify checkout.sk has iniciarDoacaoPersonalizada and CheckoutRequest.customDonation
        String checkoutContent = Files.readString(new File(skriptDir, "checkout.sk").toPath(), StandardCharsets.UTF_8);
        assertTrue(checkoutContent.contains("function iniciarDoacaoPersonalizada("));
        assertTrue(checkoutContent.contains("CheckoutRequest.customDonation("));

        // Verify all methods invoked by Skript exist on the Java classes
        assertNotNull(CatalogManager.class.getMethod("getProductsByCategory", String.class));
        assertNotNull(CatalogManager.class.getMethod("getCustomDonationConfig"));
        assertNotNull(Product.class.getMethod("hasOffers"));
        assertNotNull(Product.class.getMethod("getDisplayPrice"));
        assertNotNull(CheckoutRequest.class.getMethod("customDonation", UUID.class, BigDecimal.class));
        assertNotNull(PaymentApprovedEvent.class.getMethod("getPrice"));
        assertNotNull(DeliveryStartedEvent.class.getMethod("getDeliveryType"));
        assertNotNull(SubscriptionResult.class.getMethod("getOrder"));
        assertNotNull(SubscriptionResult.class.getMethod("getPayment"));

        // Verify catalog.yml loads all 6 required categories and valid products
        for (String catId : CatalogManager.REQUIRED_CATEGORIES) {
            assertNotNull(CatalogManager.getCategory(catId), "Categoria obrigatoria ausente: " + catId);
            assertFalse(CatalogManager.getProductsByCategory(catId).isEmpty(),
                    "Categoria " + catId + " deve possuir produtos carregados.");
        }
    }
}
