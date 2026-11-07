package io.github.warleysr.autopix;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.api.OrderStatus;
import io.github.warleysr.autopix.api.Payment;
import io.github.warleysr.autopix.api.PaymentStatus;
import io.github.warleysr.autopix.delivery.DeliveryManager;
import io.github.warleysr.autopix.domain.DonorInfo;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.PaymentInfo;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.PaymentCancelledEvent;
import io.github.warleysr.autopix.events.PaymentRejectedEvent;
import io.github.warleysr.autopix.expansion.AutoPixExpansion;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.github.warleysr.autopix.subscription.SubscriptionManager;

public class OrderManager {

    public enum TransactionCheck {
        NOT_VALIDATED,
        ALREADY_VALIDATED,
        DB_ERROR
    }

    private static volatile HikariDataSource dataSource;
    private static volatile String activeDbType = "sqlite";

    private static final AtomicBoolean DATABASE_READY = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);
    private static final AtomicBoolean VALIDATION_RUNNING = new AtomicBoolean(false);
    private static final Set<String> CREATING_ORDER_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, UUID> ONLINE_PLAYERS = new ConcurrentHashMap<>();

    public static boolean isDatabaseReady() {
        HikariDataSource source = dataSource;
        return DATABASE_READY.get() && !SHUTTING_DOWN.get() && source != null && !source.isClosed();
    }

    public static void setDatabaseReady(boolean ready) {
        DATABASE_READY.set(ready);
    }

    public static boolean isShuttingDown() {
        return SHUTTING_DOWN.get();
    }

    public static void setShuttingDown(boolean shuttingDown) {
        SHUTTING_DOWN.set(shuttingDown);
    }

    protected static synchronized boolean startOrderManager(AutoPix ap) throws SQLException {
        DATABASE_READY.set(false);
        SHUTTING_DOWN.set(false);
        FileConfiguration cfg = ap.getConfig();
        String type = cfg.getString("database.type", "mysql").trim().toLowerCase(Locale.ROOT);

        if (!type.equals("postgresql") && !type.equals("postgres") && !type.equals("mysql") && !type.equals("sqlite")) {
            MSG.sendMessage(Bukkit.getConsoleSender(), "db-invalido");
            return false;
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("AutoPix-" + type);
        hikari.setAutoCommit(true);

        long connectionTimeout = clamp(cfg.getLong("database.pool.connection-timeout", 10000L), 1000L, 60000L);
        hikari.setConnectionTimeout(connectionTimeout);
        hikari.setValidationTimeout(Math.min(5000L, Math.max(1000L, connectionTimeout - 100L)));
        hikari.setIdleTimeout(clamp(cfg.getLong("database.pool.idle-timeout", 60000L), 10000L, 600000L));
        hikari.setMaxLifetime(clamp(cfg.getLong("database.pool.max-lifetime", 300000L), 30000L, 1800000L));
        hikari.setInitializationFailTimeout(connectionTimeout);

        int defaultPoolSize = type.equals("sqlite") ? 1 : 3;
        int maximumPoolSize = (int) clamp(cfg.getInt("database.pool.maximum-size", defaultPoolSize), 1, 10);
        int minimumIdle = (int) clamp(cfg.getInt("database.pool.minimum-idle", 0), 0, maximumPoolSize);
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setMinimumIdle(minimumIdle);

        String normalizedType;
        if (type.equals("postgresql") || type.equals("postgres")) {
            normalizedType = "postgresql";
            String host = required(cfg, "database.host");
            String database = required(cfg, "database.db");
            String user = required(cfg, "database.user");
            String password = required(cfg, "database.pass");
            int port = cfg.getInt("database.port", 5432);
            String sslMode = cfg.getString("database.sslmode", "require").trim().toLowerCase(Locale.ROOT);
            if (!sslMode.equals("require") && !sslMode.equals("verify-ca") && !sslMode.equals("verify-full")) {
                throw new SQLException("database.sslmode deve ser require, verify-ca ou verify-full.");
            }

            String channelBinding = cfg.getString("database.channel-binding", "require").trim().toLowerCase(Locale.ROOT);
            if (!channelBinding.equals("require") && !channelBinding.equals("prefer") && !channelBinding.equals("disable")) {
                throw new SQLException("database.channel-binding deve ser require, prefer ou disable.");
            }

            hikari.setDriverClassName("org.postgresql.Driver");
            hikari.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
            hikari.setUsername(user);
            hikari.setPassword(password);
            hikari.addDataSourceProperty("sslmode", sslMode);
            hikari.addDataSourceProperty("channelBinding", channelBinding);
            hikari.addDataSourceProperty("ApplicationName", "AutoPix");
            hikari.addDataSourceProperty("loginTimeout", "15");
            hikari.addDataSourceProperty("socketTimeout", "30");
            hikari.addDataSourceProperty("tcpKeepAlive", "true");
            hikari.addDataSourceProperty("reWriteBatchedInserts", "true");
        } else if (type.equals("mysql")) {
            normalizedType = "mysql";
            String host = required(cfg, "database.host");
            String database = required(cfg, "database.db");
            String user = required(cfg, "database.user");
            String password = required(cfg, "database.pass");
            int port = cfg.getInt("database.port", 3306);
            boolean useSsl = cfg.getBoolean("database.use-ssl", false);

            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?characterEncoding=utf8&useSSL=" + useSsl
                    + "&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=30000");
            hikari.setUsername(user);
            hikari.setPassword(password);
        } else {
            normalizedType = "sqlite";
            if (!ap.getDataFolder().exists()) {
                ap.getDataFolder().mkdirs();
            }
            File flatFile = new File(ap.getDataFolder(), "autopix.db");
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + flatFile.getAbsolutePath());
            hikari.setMaximumPoolSize(1);
            hikari.setMinimumIdle(0);
            hikari.setConnectionTestQuery("SELECT 1");
            hikari.setConnectionInitSql("PRAGMA busy_timeout = 5000;");
        }

        SQLException lastException = null;
        int maxAttempts = normalizedType.equals("postgresql") ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                initializePool(hikari, normalizedType);
                ap.getLogger().info("Banco de dados conectado (" + normalizedType + ") e migracoes verificadas.");
                return true;
            } catch (SQLException ex) {
                lastException = ex;
                if (attempt < maxAttempts) {
                    ap.getLogger().info("Aguardando reativacao do banco PostgreSQL/Neon (tentativa "
                            + attempt + "/" + maxAttempts + ")...");
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw lastException != null ? lastException : new SQLException("Falha ao inicializar banco de dados.");
    }

    public static synchronized void initializePool(HikariConfig hikari, String normalizedType) throws SQLException {
        String idDefinition = getIdDefinition(normalizedType);
        HikariDataSource candidate = null;
        try {
            candidate = new HikariDataSource(hikari);
            runMigrations(candidate, idDefinition, normalizedType);

            HikariDataSource previous = dataSource;
            dataSource = candidate;
            activeDbType = normalizedType;
            DATABASE_READY.set(true);
            SHUTTING_DOWN.set(false);
            candidate = null;
            if (previous != null) previous.close();
        } catch (SQLException exception) {
            if (candidate != null) candidate.close();
            throw exception;
        } catch (RuntimeException exception) {
            if (candidate != null) candidate.close();
            throw new SQLException("Falha ao iniciar o pool de conexoes do banco de dados.", exception);
        }
    }

    public static String getIdDefinition(String normalizedType) {
        if ("postgresql".equalsIgnoreCase(normalizedType) || "postgres".equalsIgnoreCase(normalizedType)) {
            return "INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
        }
        if ("mysql".equalsIgnoreCase(normalizedType)) {
            return "INTEGER PRIMARY KEY AUTO_INCREMENT";
        }
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    private static void runMigrations(HikariDataSource source, String idDefinition, String dbType) throws SQLException {
        try (Connection connection = source.getConnection()) {
            DatabaseMigrationRunner.migrate(connection, dbType, idDefinition);
        }
    }

    private static String required(FileConfiguration cfg, String path) throws SQLException {
        String value = cfg.getString(path);
        if (value == null || value.trim().isEmpty()) {
            throw new SQLException("Configuracao obrigatoria ausente: " + path);
        }
        return value.trim();
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static float roundPrice(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    public static long toCents(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static long toCents(BigDecimal value) {
        if (value == null) return 0L;
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static boolean tryAcquireCreateLock(String playerName) {
        if (playerName == null) return false;
        return CREATING_ORDER_PLAYERS.add(playerName.toLowerCase(Locale.ROOT));
    }

    public static void releaseCreateLock(String playerName) {
        if (playerName == null) return;
        CREATING_ORDER_PLAYERS.remove(playerName.toLowerCase(Locale.ROOT));
    }

    public static void markPlayerOnline(String playerName, UUID uuid) {
        if (playerName == null || uuid == null) return;
        ONLINE_PLAYERS.put(playerName.toLowerCase(Locale.ROOT), uuid);
    }

    public static void markPlayerOffline(String playerName) {
        if (playerName == null) return;
        ONLINE_PLAYERS.remove(playerName.toLowerCase(Locale.ROOT));
        CREATING_ORDER_PLAYERS.remove(playerName.toLowerCase(Locale.ROOT));
        SoundManager.clearPlayerRateLimits(playerName);
    }

    public static void syncOnlinePlayersFromServer() {
        ONLINE_PLAYERS.clear();
        if (Bukkit.getServer() == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ONLINE_PLAYERS.put(p.getName().toLowerCase(Locale.ROOT), p.getUniqueId());
        }
    }

    public static boolean isPlayerOnline(String playerName) {
        if (playerName == null) return false;
        return ONLINE_PLAYERS.containsKey(playerName.toLowerCase(Locale.ROOT));
    }

    public static UUID getOnlinePlayerUuid(String playerName) {
        if (playerName == null) return null;
        return ONLINE_PLAYERS.get(playerName.toLowerCase(Locale.ROOT));
    }

    private static volatile Thread simulatedMainThreadForTesting;

    public static void setSimulatedMainThreadForTesting(Thread thread) {
        simulatedMainThreadForTesting = thread;
    }

    public static boolean isOnMainThread() {
        Thread sim = simulatedMainThreadForTesting;
        if (sim != null) {
            return Thread.currentThread() == sim;
        }
        try {
            return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Connection getConnection() throws SQLException {
        if (isOnMainThread()) {
            throw new IllegalStateException("Operacao JDBC bloqueante detectada na main thread do Bukkit!");
        }
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) {
            throw new SQLException("O pool de conexoes do AutoPix nao esta inicializado.");
        }
        return source.getConnection();
    }

    public static synchronized void shutdown() {
        DATABASE_READY.set(false);
        SHUTTING_DOWN.set(true);
        HikariDataSource source = dataSource;
        dataSource = null;
        CREATING_ORDER_PLAYERS.clear();
        ONLINE_PLAYERS.clear();
        VALIDATION_RUNNING.set(false);
        if (source != null) source.close();
    }

    public static Order createOrder(Player p, String product, float price) {
        return createOrder(p.getName(), product, price);
    }

    public static Order createOrder(String playerName, String product, float price) {
        return createOrderWithPixData(playerName, product, price, null);
    }

    public static Order createOrderWithPixData(String playerName, String product, float price, PixData pixData) {
        return createOrderWithItems(playerName, product, price, pixData, null);
    }

    public static Order createOrderDetailed(String playerName, UUID playerUuid, String product,
                                            BigDecimal price, BigDecimal subtotal, BigDecimal discount,
                                            String couponCode, Instant expiresAt, PixData pixData, List<OrderItem> items) {
        return createOrderDetailed(playerName, playerUuid, product, price, subtotal, discount, couponCode, expiresAt, pixData, items, null);
    }

    public static Order createOrderDetailed(String playerName, UUID playerUuid, String product,
                                            BigDecimal price, BigDecimal subtotal, BigDecimal discount,
                                            String couponCode, Instant expiresAt, PixData pixData, List<OrderItem> items,
                                            String idempotencyKey) {
        if (playerName == null || product == null || product.trim().isEmpty()) return null;
        if (price == null || price.compareTo(new BigDecimal("0.01")) < 0 || price.stripTrailingZeros().scale() > 2) {
            return null;
        }
        BigDecimal finalPrice = price.setScale(2, RoundingMode.UNNECESSARY);

        String normalizedIdempKey = (idempotencyKey != null && !idempotencyKey.trim().isEmpty())
                ? idempotencyKey.trim()
                : null;
        if (normalizedIdempKey != null) {
            Order existing = getOrderByIdempotencyKey(normalizedIdempKey);
            if (existing != null) {
                return existing;
            }
        }

        Timestamp created = Timestamp.from(Instant.now());
        Timestamp expires = expiresAt != null ? Timestamp.from(expiresAt) : null;

        String insertOrderSql = "INSERT INTO autopix_orders "
                + "(player, player_uuid, product, price, subtotal, discount, coupon_code, currency, status, idempotency_key, created, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
        String insertPixSql = "INSERT INTO autopix_pix_data (payment_id, order_id, status, qr_code) VALUES (?, ?, ?, ?);";
        String insertItemSql = "INSERT INTO autopix_order_items "
                + "(order_id, item_index, product_id, offer_id, product, description, category, offer_type, duration, currency, "
                + "quantity, unit_price, line_total, delivery_type, delivery_handler, delivery_snapshot, pre_commands, commands) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStmt = prepareInsertIdStatement(connection, insertOrderSql)) {
                orderStmt.setString(1, playerName);
                orderStmt.setString(2, playerUuid != null ? playerUuid.toString() : null);
                orderStmt.setString(3, product.length() > 64 ? product.substring(0, 64) : product);
                orderStmt.setBigDecimal(4, finalPrice);
                orderStmt.setBigDecimal(5, subtotal != null ? subtotal.setScale(2, RoundingMode.HALF_UP) : finalPrice);
                orderStmt.setBigDecimal(6, discount != null ? discount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                orderStmt.setString(7, couponCode);
                orderStmt.setString(8, "BRL");
                orderStmt.setString(9, "PENDING");
                orderStmt.setString(10, normalizedIdempKey);
                orderStmt.setTimestamp(11, created);
                orderStmt.setTimestamp(12, expires);

                if (orderStmt.executeUpdate() == 0) {
                    connection.rollback();
                    return null;
                }

                int orderId;
                try (ResultSet keys = orderStmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        connection.rollback();
                        throw new SQLException("O banco nao retornou o ID do pedido criado.");
                    }
                    orderId = keys.getInt(1);
                }

                Order order = new Order(playerName, playerUuid, product, finalPrice, subtotal, discount, created.getTime());
                order.setId(orderId);
                order.setCouponSnapshot(couponCode);
                order.setExpiresAt(expiresAt);
                order.setIdempotencyKey(normalizedIdempKey);

                if (pixData != null) {
                    pixData.setOrderId(orderId);
                    try (PreparedStatement pixStmt = connection.prepareStatement(insertPixSql)) {
                        pixStmt.setString(1, pixData.getPaymentId());
                        pixStmt.setInt(2, orderId);
                        pixStmt.setString(3, pixData.getStatus());
                        pixStmt.setString(4, pixData.getQrCode());
                        if (pixStmt.executeUpdate() != 1) {
                            connection.rollback();
                            return null;
                        }
                    }
                }

                if (items != null && !items.isEmpty()) {
                    try (PreparedStatement itemStmt = connection.prepareStatement(insertItemSql)) {
                        int index = 0;
                        for (OrderItem item : items) {
                            itemStmt.setInt(1, orderId);
                            itemStmt.setInt(2, index++);
                            itemStmt.setString(3, item.getProductId());
                            itemStmt.setString(4, item.getOfferId() != null ? item.getOfferId() : "default");
                            itemStmt.setString(5, item.getProduct());
                            itemStmt.setString(6, item.getDescription());
                            itemStmt.setString(7, item.getCategory() != null ? item.getCategory() : "general");
                            itemStmt.setString(8, item.getOfferType() != null ? item.getOfferType() : "ONE_TIME");
                            itemStmt.setString(9, item.getDuration());
                            itemStmt.setString(10, item.getCurrency() != null ? item.getCurrency() : "BRL");
                            itemStmt.setInt(11, item.getQuantity());
                            itemStmt.setBigDecimal(12, item.getUnitPriceDecimal());
                            itemStmt.setBigDecimal(13, item.getLineTotalDecimal());
                            itemStmt.setString(14, item.getDeliveryType() != null ? item.getDeliveryType() : "autopix");
                            itemStmt.setString(15, item.getDeliveryHandler());
                            itemStmt.setString(16, item.getDeliverySnapshot());
                            itemStmt.setString(17, encodeStringList(item.getPreCommands()));
                            itemStmt.setString(18, encodeStringList(item.getCommands()));
                            itemStmt.addBatch();
                        }
                        itemStmt.executeBatch();
                    }
                    order.setItems(items);
                }

                connection.commit();
                return order;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            if (normalizedIdempKey != null) {
                Order existing = getOrderByIdempotencyKey(normalizedIdempKey);
                if (existing != null) {
                    return existing;
                }
            }
            logSqlError("Erro ao criar pedido no banco de dados", exception);
            return null;
        }
    }

    public static Order getOrderByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) return null;
        String sql = "SELECT * FROM autopix_orders WHERE idempotency_key = ? LIMIT 1;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, idempotencyKey.trim());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    Order order = readOrder(result);
                    order.setItems(getOrderItems(connection, order.getId()));
                    return order;
                }
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao buscar pedido por idempotency_key", exception);
        }
        return null;
    }

    /**
     * Creates an order, its optional Mercado Pago record and all product snapshots
     * in one database transaction.
     */
    public static Order createOrderWithItems(String playerName, String product, float price,
                                             PixData pixData, List<OrderItem> items) {
        if (!Float.isFinite(price) || price < 0.01f) return null;
        BigDecimal decPrice = new BigDecimal(Float.toString(price)).setScale(2, RoundingMode.HALF_UP);
        return createOrderWithItems(playerName, product, decPrice, pixData, items);
    }

    public static Order createOrderWithItems(String playerName, String product, BigDecimal price,
                                             PixData pixData, List<OrderItem> items) {
        if (price == null) return null;
        UUID playerUuid = getOnlinePlayerUuid(playerName);
        return createOrderDetailed(playerName, playerUuid, product, price, price, BigDecimal.ZERO, null, null, pixData, items);
    }

    private static String encodeStringList(List<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) {
                if (value != null) array.add(value);
            }
        }
        return array.toJSONString();
    }

    private static List<String> decodeStringList(String json) {
        List<String> values = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return values;
        try {
            Object parsed = new JSONParser().parse(json);
            if (parsed instanceof JSONArray) {
                for (Object value : (JSONArray) parsed) {
                    if (value != null) values.add(String.valueOf(value));
                }
            }
        } catch (Exception exception) {
            AutoPix instance = AutoPix.getInstance();
            if (instance != null) {
                instance.getLogger().warning("Snapshot de comandos de pedido invalido: " + exception.getMessage());
            }
        }
        return values;
    }

    public static List<OrderItem> getOrderItems(int orderId) {
        try (Connection connection = getConnection()) {
            return getOrderItems(connection, orderId);
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar itens do pedido #" + orderId, exception);
            return new ArrayList<>();
        }
    }

    public static List<OrderItem> getOrderItems(Connection connection, int orderId) throws SQLException {
        List<OrderItem> items = new ArrayList<>();
        String sql = "SELECT * FROM autopix_order_items WHERE order_id = ? ORDER BY item_index ASC;";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String product = result.getString("product");
                    String productId = result.getString("product_id");
                    if (productId == null || productId.trim().isEmpty()) {
                        productId = product;
                    }
                    String offerId = result.getString("offer_id");
                    if (offerId == null || offerId.trim().isEmpty()) {
                        offerId = "default";
                    }
                    String description = result.getString("description");
                    String category = result.getString("category");
                    if (category == null || category.trim().isEmpty()) {
                        category = "general";
                    }
                    String offerType = result.getString("offer_type");
                    if (offerType == null || offerType.trim().isEmpty()) {
                        offerType = "ONE_TIME";
                    }
                    String duration = result.getString("duration");
                    String currency = result.getString("currency");
                    if (currency == null || currency.trim().isEmpty()) {
                        currency = "BRL";
                    }
                    int quantity = result.getInt("quantity");
                    BigDecimal unitPrice = result.getBigDecimal("unit_price");
                    BigDecimal unitPriceDecimal = unitPrice != null
                            ? unitPrice.setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.valueOf(roundPrice(result.getDouble("unit_price"))).setScale(2, RoundingMode.HALF_UP);

                    BigDecimal lt = result.getBigDecimal("line_total");
                    BigDecimal lineTotal = lt != null
                            ? lt.setScale(2, RoundingMode.HALF_UP)
                            : unitPriceDecimal.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

                    String deliveryType = result.getString("delivery_type");
                    if (deliveryType == null || deliveryType.trim().isEmpty()) {
                        deliveryType = "autopix";
                    }
                    String deliveryHandler = result.getString("delivery_handler");
                    String deliverySnapshot = result.getString("delivery_snapshot");

                    items.add(new OrderItem(
                            productId, offerId, product, description, category,
                            offerType, duration, currency, quantity,
                            unitPriceDecimal, lineTotal,
                            deliveryType, deliveryHandler, deliverySnapshot,
                            decodeStringList(result.getString("pre_commands")),
                            decodeStringList(result.getString("commands"))));
                }
            }
        }
        return items;
    }

    private static PreparedStatement prepareInsertIdStatement(Connection connection, String sql) throws SQLException {
        if ("postgresql".equalsIgnoreCase(activeDbType)) {
            return connection.prepareStatement(sql, new String[]{"id"});
        }
        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    public static boolean savePixData(PixData pixData) {
        String sql = "INSERT INTO autopix_pix_data (payment_id, order_id, status, qr_code) VALUES (?, ?, ?, ?);";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pixData.getPaymentId());
            statement.setInt(2, pixData.getOrderId());
            statement.setString(3, pixData.getStatus());
            statement.setString(4, pixData.getQrCode());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            logSqlError("Erro ao salvar dados PIX do pedido #" + pixData.getOrderId(), exception);
            return false;
        }
    }

    public static List<Order> getOrders(String player) {
        return getOrders(player, 10);
    }

    public static List<Order> getOrders(String player, int limit) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM autopix_orders WHERE player = ? ORDER BY created DESC, id DESC LIMIT ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) orders.add(readOrder(result));
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao listar pedidos de " + player, exception);
        }
        return orders;
    }

    public static Order getLastOrder(String player) {
        List<Order> orders = getOrders(player, 1);
        return orders.isEmpty() ? null : orders.get(0);
    }

    public static Order getOrderById(int orderId) {
        String sql = "SELECT * FROM autopix_orders WHERE id = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    Order order = readOrder(result);
                    order.setItems(getOrderItems(connection, order.getId()));
                    return order;
                }
                return null;
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao buscar pedido #" + orderId, exception);
            return null;
        }
    }

    public static Order getOrder(String orderId) {
        if (orderId == null) return null;
        try {
            return getOrderById(Integer.parseInt(orderId.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static List<Order> getOrdersByPlayerUuid(UUID playerUuid, int limit) {
        List<Order> orders = new ArrayList<>();
        if (playerUuid == null) return orders;
        String sql = "SELECT * FROM autopix_orders WHERE player_uuid = ? ORDER BY created DESC, id DESC LIMIT ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) orders.add(readOrder(result));
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao listar pedidos do UUID " + playerUuid, exception);
        }
        return orders;
    }

    public static boolean updateOrderStatus(int orderId, OrderStatus status) {
        if (status == null) return false;
        String sql = "UPDATE autopix_orders SET status = ? WHERE id = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setInt(2, orderId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            logSqlError("Erro ao atualizar status do pedido #" + orderId, exception);
            return false;
        }
    }

    public static boolean cancelOrder(int orderId, String reason) {
        String sql = "UPDATE autopix_orders SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP WHERE id = ? AND pix IS NULL;";
        boolean updated;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            updated = statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            logSqlError("Erro ao cancelar pedido #" + orderId, exception);
            return false;
        }
        if (updated) {
            Order order = getOrderById(orderId);
            if (order != null) {
                PixData pd = getPixData(order);
                if (pd != null) cancelPendingPixData(pd);
            }
        }
        return updated;
    }

    public static Order readOrder(ResultSet result) throws SQLException {
        Timestamp created = result.getTimestamp("created");
        long createdMillis = created != null ? created.getTime() : System.currentTimeMillis();
        String playerName = result.getString("player");
        String productName = result.getString("product");
        BigDecimal priceDec = result.getBigDecimal("price");
        BigDecimal normalizedPrice = priceDec != null
                ? priceDec.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(result.getDouble("price")).setScale(2, RoundingMode.HALF_UP);
        Order order = new Order(playerName, null, productName, normalizedPrice, normalizedPrice,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), createdMillis);
        order.setId(result.getInt("id"));
        order.setTransaction(result.getString("pix"));
        try {
            order.setIdempotencyKey(result.getString("idempotency_key"));
        } catch (SQLException ignored) {
        }

        String uuidStr = result.getString("player_uuid");
        if (uuidStr != null && !uuidStr.trim().isEmpty()) {
            try {
                order.setPlayerUuid(UUID.fromString(uuidStr.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        String statusStr = result.getString("status");
        if (statusStr != null && !statusStr.trim().isEmpty()) {
            try {
                order.setStatus(OrderStatus.valueOf(statusStr.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        BigDecimal subtotal = result.getBigDecimal("subtotal");
        if (subtotal != null) order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        BigDecimal discount = result.getBigDecimal("discount");
        if (discount != null) order.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        String coupon = result.getString("coupon_code");
        if (coupon != null) order.setCouponSnapshot(coupon);
        String cur = result.getString("currency");
        if (cur != null) order.setCurrency(cur);
        Timestamp exp = result.getTimestamp("expires_at");
        if (exp != null) order.setExpiresAt(exp.toInstant());
        Timestamp paid = result.getTimestamp("paid_at");
        if (paid != null && (order.getStatus() == OrderStatus.CREATED || order.getStatus() == OrderStatus.PENDING)) {
            order.setStatus(OrderStatus.PAID);
        }

        return order;
    }

    public static PixData getPixData(Order order) {
        if (order == null) return null;
        String sql = "SELECT * FROM autopix_pix_data WHERE order_id = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, order.getId());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    PixData pixData = new PixData(result.getString("payment_id"), result.getString("qr_code"));
                    pixData.setOrderId(result.getInt("order_id"));
                    pixData.setStatus(result.getString("status"));
                    return pixData;
                }
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar dados PIX do pedido #" + order.getId(), exception);
        }
        return null;
    }

    public static PixData getPixDataByPaymentId(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) return null;
        String sql = "SELECT * FROM autopix_pix_data WHERE payment_id = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, paymentId.trim());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    PixData pixData = new PixData(result.getString("payment_id"), result.getString("qr_code"));
                    pixData.setOrderId(result.getInt("order_id"));
                    pixData.setStatus(result.getString("status"));
                    return pixData;
                }
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar dados PIX pelo payment_id " + paymentId, exception);
        }
        return null;
    }

    public static List<PixData> getAllPendingPixData() {
        List<PixData> data = new ArrayList<>();
        String sql = "SELECT * FROM autopix_pix_data WHERE status = 'pending';";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                PixData pixData = new PixData(result.getString("payment_id"), result.getString("qr_code"));
                pixData.setOrderId(result.getInt("order_id"));
                pixData.setStatus(result.getString("status"));
                data.add(pixData);
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao buscar pagamentos PIX pendentes", exception);
        }
        return data;
    }

    public static TransactionCheck checkTransactionStatus(String transactionId) {
        String sql = "SELECT id FROM autopix_orders WHERE pix = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, transactionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? TransactionCheck.ALREADY_VALIDATED : TransactionCheck.NOT_VALIDATED;
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao verificar transacao PIX", exception);
            return TransactionCheck.DB_ERROR;
        }
    }

    public static boolean isTransactionValidated(String transactionId) {
        return checkTransactionStatus(transactionId) == TransactionCheck.ALREADY_VALIDATED;
    }

    public static boolean setTransaction(Order order, String transactionId) {
        return approveManualOrder(order, transactionId);
    }

    public static boolean approveManualOrder(Order order, String transactionId) {
        if (order == null || transactionId == null || transactionId.trim().isEmpty()) {
            return false;
        }
        String updateOrder = "UPDATE autopix_orders SET pix = ?, status = 'PAID', paid_at = CURRENT_TIMESTAMP WHERE id = ? AND pix IS NULL;";
        String updatePayment = "UPDATE autopix_pix_data SET status = 'approved' WHERE order_id = ? AND status = 'pending';";
        String updatePaymentRecord = "UPDATE autopix_payments SET status = 'PAID', paid_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?;";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatement = connection.prepareStatement(updateOrder);
                 PreparedStatement paymentStatement = connection.prepareStatement(updatePayment);
                 PreparedStatement paymentRecordStatement = connection.prepareStatement(updatePaymentRecord)) {
                orderStatement.setString(1, transactionId.trim());
                orderStatement.setInt(2, order.getId());
                if (orderStatement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }

                paymentStatement.setInt(1, order.getId());
                paymentStatement.executeUpdate();

                paymentRecordStatement.setInt(1, order.getId());
                paymentRecordStatement.executeUpdate();

                List<OrderItem> orderItems = getOrderItems(connection, order.getId());
                UUID playerUuid = order.getPlayerUuid() != null
                        ? order.getPlayerUuid()
                        : getOnlinePlayerUuid(order.getPlayer());
                DeliveryManager.ensureOutboxQueued(connection, order.getId(), orderItems, playerUuid);

                connection.commit();
                order.setTransaction(transactionId.trim());
                order.setStatus(OrderStatus.PAID);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao aprovar manualmente o pedido #" + order.getId(), exception);
            return false;
        }
        SubscriptionManager.onOrderPaymentApproved(order.getId(), null, transactionId.trim(), order.getTotal());
        return true;
    }

    public static boolean approvePayment(PixData pixData, Order order, String transactionId) {
        if (pixData == null || order == null || transactionId == null || transactionId.trim().isEmpty()) {
            return false;
        }
        String updateOrder = "UPDATE autopix_orders SET pix = ?, status = 'PAID', paid_at = CURRENT_TIMESTAMP WHERE id = ? AND pix IS NULL;";
        String updatePayment = "UPDATE autopix_pix_data SET status = 'approved' WHERE payment_id = ? AND status = 'pending';";
        String updatePaymentRecord = "UPDATE autopix_payments SET status = 'PAID', paid_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE payment_id = ? OR order_id = ?;";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatement = connection.prepareStatement(updateOrder);
                 PreparedStatement paymentStatement = connection.prepareStatement(updatePayment);
                 PreparedStatement paymentRecordStatement = connection.prepareStatement(updatePaymentRecord)) {
                orderStatement.setString(1, transactionId.trim());
                orderStatement.setInt(2, order.getId());
                if (orderStatement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }

                paymentStatement.setString(1, pixData.getPaymentId());
                if (paymentStatement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }

                paymentRecordStatement.setString(1, pixData.getPaymentId());
                paymentRecordStatement.setInt(2, order.getId());
                paymentRecordStatement.executeUpdate();

                List<OrderItem> orderItems = getOrderItems(connection, order.getId());
                UUID playerUuid = order.getPlayerUuid() != null
                        ? order.getPlayerUuid()
                        : getOnlinePlayerUuid(order.getPlayer());
                DeliveryManager.ensureOutboxQueued(connection, order.getId(), orderItems, playerUuid);

                connection.commit();
                pixData.setStatus("approved");
                order.setTransaction(transactionId.trim());
                order.setStatus(OrderStatus.PAID);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao aprovar pagamento #" + pixData.getPaymentId(), exception);
            return false;
        }
        SubscriptionManager.onOrderPaymentApproved(
                order.getId(), pixData.getPaymentId(), transactionId.trim(), order.getTotal());
        return true;
    }

    public static boolean setPixDataStatus(PixData pixData, String status) {
        if (pixData == null || status == null || status.trim().isEmpty()) return false;
        String sql = "UPDATE autopix_pix_data SET status = ? WHERE payment_id = ? AND status <> 'approved';";
        String updatePaymentTbl = "UPDATE autopix_payments SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE payment_id = ? AND status <> 'PAID' AND status <> 'APPROVED';";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             PreparedStatement payStmt = connection.prepareStatement(updatePaymentTbl)) {
            statement.setString(1, status.trim());
            statement.setString(2, pixData.getPaymentId());
            boolean updated = statement.executeUpdate() > 0;
            if (updated) {
                pixData.setStatus(status.trim());
                payStmt.setString(1, status.trim().toUpperCase(Locale.ROOT));
                payStmt.setString(2, pixData.getPaymentId());
                payStmt.executeUpdate();
            }
            return updated;
        } catch (SQLException exception) {
            logSqlError("Erro ao atualizar status do pagamento #" + pixData.getPaymentId(), exception);
            return false;
        }
    }

    public static boolean cancelPendingPixData(PixData pixData) {
        if (pixData == null) return false;
        String sql = "UPDATE autopix_pix_data SET status = 'cancelled' WHERE payment_id = ? AND status = 'pending';";
        String updatePaymentTbl = "UPDATE autopix_payments SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP WHERE payment_id = ? AND status = 'PENDING';";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             PreparedStatement payStmt = connection.prepareStatement(updatePaymentTbl)) {
            statement.setString(1, pixData.getPaymentId());
            boolean updated = statement.executeUpdate() > 0;
            if (updated) {
                pixData.setStatus("cancelled");
                payStmt.setString(1, pixData.getPaymentId());
                payStmt.executeUpdate();
            }
            return updated;
        } catch (SQLException exception) {
            logSqlError("Erro ao cancelar pagamento pendente #" + pixData.getPaymentId(), exception);
            return false;
        }
    }

    public static boolean savePayment(Payment payment, int orderId, BigDecimal amount) {
        if (payment == null) return false;
        String sql = "INSERT INTO autopix_payments (payment_id, order_id, method, amount, status, qr_code, qr_code_base64, copy_paste, expires_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, payment.getId());
            statement.setInt(2, orderId);
            statement.setString(3, "pix");
            statement.setBigDecimal(4, amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            statement.setString(5, payment.getStatus().name());
            statement.setString(6, payment.getQrCode());
            statement.setString(7, null);
            statement.setString(8, payment.getCopyPasteCode());
            statement.setTimestamp(9, payment.getExpirationAt() != null ? Timestamp.from(payment.getExpirationAt()) : null);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            logSqlError("Erro ao salvar registro de pagamento #" + payment.getId(), exception);
            return false;
        }
    }

    public static Payment getPayment(String paymentId) {
        if (paymentId == null) return null;
        String sql = "SELECT * FROM autopix_payments WHERE payment_id = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, paymentId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    String id = result.getString("payment_id");
                    String statusStr = result.getString("status");
                    PaymentStatus status = PaymentStatus.PENDING;
                    try {
                        if (statusStr != null) status = PaymentStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
                    } catch (Exception ignored) {}
                    String qrCode = result.getString("qr_code");
                    String copyPaste = result.getString("copy_paste");
                    Timestamp exp = result.getTimestamp("expires_at");
                    Instant expInstant = exp != null ? exp.toInstant() : null;
                    return new Payment(id, id, status, qrCode, copyPaste, null, expInstant);
                }
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar pagamento #" + paymentId, exception);
        }
        return null;
    }

    public static Payment getPaymentByOrderId(int orderId) {
        String sql = "SELECT * FROM autopix_payments WHERE order_id = ?;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    String id = result.getString("payment_id");
                    String statusStr = result.getString("status");
                    PaymentStatus status = PaymentStatus.PENDING;
                    try {
                        if (statusStr != null) status = PaymentStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
                    } catch (Exception ignored) {}
                    String qrCode = result.getString("qr_code");
                    String copyPaste = result.getString("copy_paste");
                    Timestamp exp = result.getTimestamp("expires_at");
                    Instant expInstant = exp != null ? exp.toInstant() : null;
                    return new Payment(id, id, status, qrCode, copyPaste, null, expInstant);
                }
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar pagamento pelo pedido #" + orderId, exception);
        }
        return null;
    }

    /**
     * Reconciles a single payment by consulting the Mercado Pago API directly,
     * verifying currency, external reference, and exact BigDecimal amount,
     * and executing state transitions / outbox queuing regardless of whether the player is online.
     */
    public static boolean reconcilePaymentById(AutoPix ap, String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            return false;
        }
        PixData pd = getPixDataByPaymentId(paymentId.trim());
        if (pd == null) {
            return false;
        }
        return reconcilePixData(ap, pd);
    }

    public static boolean reconcilePixData(AutoPix ap, PixData pd) {
        if (pd == null) return false;
        Order order = getOrderById(pd.getOrderId());
        if (order == null) return false;

        if (order.isValidated()) {
            if (!"approved".equalsIgnoreCase(pd.getStatus())) {
                setPixDataStatus(pd, "approved");
            }
            return true;
        }

        PaymentInfo info = MercadoPagoAPI.getPayment(ap, pd.getPaymentId());
        if (info == null || info.getStatus() == null) {
            return false;
        }

        String mpStatus = info.getStatus().trim().toLowerCase(Locale.ROOT);
        if (!"approved".equals(mpStatus)) {
            if (!mpStatus.equalsIgnoreCase(pd.getStatus())) {
                if (setPixDataStatus(pd, mpStatus)) {
                    if ("cancelled".equals(mpStatus)) {
                        updateOrderStatusIfUnpaid(order.getId(), OrderStatus.CANCELLED);
                        SubscriptionManager.onOrderPaymentFailed(order.getId(), pd.getPaymentId(), mpStatus);
                    } else if ("rejected".equals(mpStatus) || "expired".equals(mpStatus)) {
                        updateOrderStatusIfUnpaid(
                                order.getId(), "expired".equals(mpStatus) ? OrderStatus.EXPIRED : OrderStatus.FAILED);
                        SubscriptionManager.onOrderPaymentFailed(order.getId(), pd.getPaymentId(), mpStatus);
                    }
                    handleNonApprovedStatusTransition(ap, order, pd, mpStatus, info.getPaidAmount());
                }
            }
            return true;
        }

        String currency = info.getCurrencyId();
        if (currency != null && !currency.trim().isEmpty() && !"BRL".equalsIgnoreCase(currency.trim())) {
            if (ap != null) {
                ap.getLogger().warning("Pagamento #" + pd.getPaymentId() + " rejeitado por moeda invalida: " + currency);
            }
            if (setPixDataStatus(pd, "currency_mismatch")) {
                updateOrderStatusIfUnpaid(order.getId(), OrderStatus.FAILED);
                SubscriptionManager.onOrderPaymentFailed(order.getId(), pd.getPaymentId(), "currency_mismatch");
                handleNonApprovedStatusTransition(ap, order, pd, "currency_mismatch", info.getPaidAmount());
            }
            return false;
        }

        String extRef = info.getExternalReference();
        if (extRef != null && !extRef.trim().isEmpty()) {
            String trimmedRef = extRef.trim();
            String expectedId = Integer.toString(order.getId());
            boolean validRef = trimmedRef.equals(expectedId)
                    || trimmedRef.equalsIgnoreCase("order:" + expectedId)
                    || trimmedRef.equalsIgnoreCase("order_" + expectedId)
                    || trimmedRef.equalsIgnoreCase("autopix:" + expectedId)
                    || trimmedRef.equalsIgnoreCase(order.getProduct())
                    || (order.getPlayer() != null && trimmedRef.equalsIgnoreCase(order.getPlayer()))
                    || (order.getIdempotencyKey() != null && trimmedRef.equalsIgnoreCase(order.getIdempotencyKey()));
            if (!validRef) {
                io.github.warleysr.autopix.subscription.SubscriptionInvoice linkedInv =
                        SubscriptionManager.getInvoiceByOrderId(order.getId());
                if (linkedInv != null) {
                    validRef = trimmedRef.equalsIgnoreCase(linkedInv.getSubscriptionId())
                            || trimmedRef.equalsIgnoreCase(linkedInv.getInvoiceId());
                }
            }
            if (!validRef) {
                if (ap != null) {
                    ap.getLogger().warning("Pagamento #" + pd.getPaymentId()
                            + " rejeitado por divergencia de referencia externa (pedido #" + order.getId()
                            + ", ref=" + trimmedRef + ").");
                }
                if (setPixDataStatus(pd, "reference_mismatch")) {
                    updateOrderStatusIfUnpaid(order.getId(), OrderStatus.FAILED);
                    SubscriptionManager.onOrderPaymentFailed(order.getId(), pd.getPaymentId(), "reference_mismatch");
                    handleNonApprovedStatusTransition(ap, order, pd, "reference_mismatch", info.getPaidAmount());
                }
                return false;
            }
        }

        if (toCents(order.getTotal()) != toCents(info.getPaidAmountDecimal())) {
            if (ap != null) {
                ap.getLogger().warning("Pagamento aprovado com valor divergente. Pedido #" + order.getId()
                        + " (esperado R$" + String.format(Locale.ROOT, "%.2f", order.getPrice())
                        + ", pago R$" + String.format(Locale.ROOT, "%.2f", info.getPaidAmount())
                        + ") foi mantido sem entrega; verifique no Mercado Pago.");
            }
            if (setPixDataStatus(pd, "amount_mismatch")) {
                updateOrderStatusIfUnpaid(order.getId(), OrderStatus.FAILED);
                SubscriptionManager.onOrderPaymentFailed(order.getId(), pd.getPaymentId(), "amount_mismatch");
                handleNonApprovedStatusTransition(ap, order, pd, "amount_mismatch", info.getPaidAmount());
            }
            return false;
        }

        if (info.getTransactionId() == null || info.getTransactionId().trim().isEmpty()) {
            return false;
        }

        List<OrderItem> orderItems = getOrderItems(order.getId());
        OrderProduct op = (orderItems.isEmpty() && ap != null) ? InventoryManager.getProductByOrder(order) : null;

        if (!approvePayment(pd, order, info.getTransactionId())) {
            return order.isValidated() || getOrderById(order.getId()).isValidated();
        }

        if (orderItems.isEmpty() && op != null) {
            deliverApprovedOrderOnMainThread(ap, order, op, pd.getPaymentId(), info.getTransactionId());
        } else {
            deliverApprovedOrderOnMainThread(ap, order, orderItems, pd.getPaymentId(), info.getTransactionId());
        }
        return true;
    }

    private static void updateOrderStatusIfUnpaid(int orderId, OrderStatus status) {
        String sql = "UPDATE autopix_orders SET status = ? WHERE id = ? AND pix IS NULL;";
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setInt(2, orderId);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            logSqlError("Erro ao atualizar status nao aprovado do pedido #" + orderId, ex);
        }
    }

    public static void validatePendings(AutoPix ap) {
        if (!isDatabaseReady() || SHUTTING_DOWN.get()) {
            return;
        }
        if (!VALIDATION_RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            List<PixData> pendings = getAllPendingPixData();
            if (pendings.isEmpty()) return;

            for (PixData pd : pendings) {
                if (SHUTTING_DOWN.get()) break;
                reconcilePixData(ap, pd);
            }
        } finally {
            VALIDATION_RUNNING.set(false);
        }
    }

    private static void handleNonApprovedStatusTransition(AutoPix ap, Order order, PixData pd,
                                                          String newStatus, double paidAmount) {
        if (ap != null) {
            refreshStatsCacheAsync(ap);
        }
        UUID playerUuid = order.getPlayerUuid() != null
                ? order.getPlayerUuid()
                : getOnlinePlayerUuid(order.getPlayer());

        Runnable task = () -> {
            Player p = Bukkit.getServer() != null ? Bukkit.getPlayerExact(order.getPlayer()) : null;
            if ("cancelled".equalsIgnoreCase(newStatus)) {
                if (p != null && p.isOnline() && ap != null) {
                    InventoryManager.removeUnpaidMaps(p);
                    SoundManager.play(p, SoundManager.PAYMENT_CANCELLED);
                    MSG.sendActionBar(p, "actionbar-cancelado");
                    InventoryManager.refreshOpenInventory(p);
                }
                AutoPixEventDispatcher.dispatch(new PaymentCancelledEvent(
                        order.getId(), order.getPlayer(), playerUuid,
                        order.getProduct(), order.getPrice(), pd.getPaymentId(),
                        newStatus, "external"));
            } else if ("rejected".equalsIgnoreCase(newStatus)
                    || "amount_mismatch".equalsIgnoreCase(newStatus)
                    || "currency_mismatch".equalsIgnoreCase(newStatus)
                    || "reference_mismatch".equalsIgnoreCase(newStatus)) {
                if (p != null && p.isOnline() && ap != null) {
                    InventoryManager.removeUnpaidMaps(p);
                    SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                    MSG.sendTitle(p, "titulo-rejeitado", "subtitulo-rejeitado", 10, 60, 20,
                            "{id}", Integer.toString(order.getId()),
                            "{produto}", order.getProduct(),
                            "{status}", newStatus);
                    MSG.sendMessage(p, "pagamento-rejeitado-chat",
                            "{id}", Integer.toString(order.getId()),
                            "{produto}", order.getProduct(),
                            "{status}", newStatus);
                    InventoryManager.refreshOpenInventory(p);
                }
                AutoPixEventDispatcher.dispatch(new PaymentRejectedEvent(
                        order.getId(), order.getPlayer(), playerUuid,
                        order.getProduct(), order.getPrice(), paidAmount,
                        pd.getPaymentId(), newStatus, newStatus));
            }
        };

        if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTask(ap);
        } else {
            task.run();
        }
    }

    public static void recoverPendingDeliveries(AutoPix ap) {
        DeliveryManager.recoverPendingDeliveries(ap);
    }

    public static void deliverApprovedOrderOnMainThread(AutoPix ap, Order order, OrderProduct op,
                                                        String paymentId, String transactionId) {
        List<OrderItem> legacyItems = null;
        if (op != null) {
            legacyItems = List.of(new OrderItem(
                    op.getProduct(), "default", op.getProduct(), null, "legacy",
                    "ONE_TIME", null, "BRL", 1,
                    BigDecimal.valueOf(roundPrice(op.getPrice())).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(roundPrice(op.getPrice())).setScale(2, RoundingMode.HALF_UP),
                    "autopix", null, null,
                    op.getPreCommands(), op.getCommands()));
        }
        deliverApprovedOrderInternal(ap, order, legacyItems, paymentId, transactionId);
    }

    public static void deliverApprovedOrderOnMainThread(AutoPix ap, Order order, List<OrderItem> items,
                                                        String paymentId, String transactionId) {
        deliverApprovedOrderInternal(ap, order, items, paymentId, transactionId);
    }

    private static void deliverApprovedOrderInternal(AutoPix ap, Order order,
                                                     List<OrderItem> orderItems, String paymentId,
                                                     String transactionId) {
        DeliveryManager.deliverApprovedOrder(ap, order, orderItems, paymentId, transactionId);
    }

    protected static void processOrderExpirations(AutoPix ap) {
        if (!isDatabaseReady() || SHUTTING_DOWN.get()) return;
        int maxMinutes = Math.max(1, ap.getConfig().getInt("mapa.tempo-pagar", 30));
        boolean automaticMode = ap.getConfig().getBoolean("automatico.ativado", true);

        for (Map.Entry<String, UUID> entry : ONLINE_PLAYERS.entrySet()) {
            if (SHUTTING_DOWN.get()) break;
            UUID playerUuid = entry.getValue();
            Order order = getLastOrderByCaseInsensitivePlayer(entry.getKey());
            if (order == null || order.isValidated()) continue;

            long diffMs = Math.max(0L, System.currentTimeMillis() - order.getCreated().getTime());
            long elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMs);

            PixData pd = getPixData(order);
            if (elapsedMinutes < maxMinutes) {
                if (pd != null && pd.isPending()) {
                    long remainingMinutes = Math.max(1L, maxMinutes - elapsedMinutes);
                    Bukkit.getScheduler().runTask(ap, () -> {
                        Player p = Bukkit.getPlayer(playerUuid);
                        if (p != null && p.isOnline()) {
                            if (InventoryManager.findPaymentMapSlot(p) >= 0) {
                                MSG.sendActionBar(p, "actionbar-pendente",
                                        "{produto}", order.getProduct(),
                                        "{tempo}", Long.toString(remainingMinutes));
                            }
                            InventoryManager.refreshOpenInventory(p);
                        }
                    });
                }
                continue;
            }

            if (pd == null || !pd.isPending()) {
                Bukkit.getScheduler().runTask(ap, () -> {
                    Player p = Bukkit.getPlayer(playerUuid);
                    if (p != null && p.isOnline()) {
                        InventoryManager.removeUnpaidMaps(p);
                    }
                });
                continue;
            }

            if (automaticMode) {
                PaymentInfo currentInfo = MercadoPagoAPI.getPayment(ap, pd.getPaymentId());
                if (currentInfo == null || currentInfo.getStatus() == null) {
                    continue;
                }

                String currentStatus = currentInfo.getStatus().trim().toLowerCase(Locale.ROOT);
                if ("approved".equals(currentStatus)) {
                    reconcilePixData(ap, pd);
                    continue;
                }

                if ("cancelled".equals(currentStatus) || "rejected".equals(currentStatus)) {
                    reconcilePixData(ap, pd);
                    continue;
                }

                if (!"pending".equals(currentStatus)
                        && !"in_process".equals(currentStatus)
                        && !"authorized".equals(currentStatus)) {
                    continue;
                }

                try {
                    if (!MercadoPagoAPI.cancelPayment(ap, pd.getPaymentId())) {
                        continue;
                    }
                } catch (Exception exception) {
                    ap.getLogger().warning("Erro ao cancelar pedido expirado #" + order.getId()
                            + " no Mercado Pago: " + exception.getMessage());
                    continue;
                }

                PaymentInfo confirmedInfo = MercadoPagoAPI.getPayment(ap, pd.getPaymentId());
                if (confirmedInfo == null || confirmedInfo.getStatus() == null) {
                    continue;
                }

                String confirmedStatus = confirmedInfo.getStatus().trim().toLowerCase(Locale.ROOT);
                if ("approved".equals(confirmedStatus)) {
                    reconcilePixData(ap, pd);
                    continue;
                }
                if (!"cancelled".equals(confirmedStatus)) {
                    continue;
                }
            }

            if (!cancelPendingPixData(pd)) {
                continue;
            }
            updateOrderStatusIfUnpaid(order.getId(), OrderStatus.EXPIRED);
            SubscriptionManager.onOrderPaymentFailed(order.getId(), pd.getPaymentId(), "expired");

            refreshStatsCacheAsync(ap);

            Bukkit.getScheduler().runTask(ap, () -> {
                Player p = Bukkit.getPlayer(playerUuid);
                if (p != null && p.isOnline()) {
                    InventoryManager.removeUnpaidMaps(p);
                    SoundManager.play(p, SoundManager.ORDER_EXPIRED);
                    MSG.sendTitle(p, "titulo-expirado", "subtitulo-expirado", 10, 60, 20,
                            "{id}", Integer.toString(order.getId()),
                            "{produto}", order.getProduct(),
                            "{tempo}", Integer.toString(maxMinutes));
                    MSG.sendActionBar(p, "actionbar-expirado");
                    MSG.sendMessage(p, "pedido-expirado-chat",
                            "{id}", Integer.toString(order.getId()),
                            "{produto}", order.getProduct(),
                            "{tempo}", Integer.toString(maxMinutes));
                    InventoryManager.refreshOpenInventory(p);
                }

                AutoPixEventDispatcher.dispatch(new PaymentCancelledEvent(
                        order.getId(), order.getPlayer(), playerUuid,
                        order.getProduct(), order.getPrice(), pd.getPaymentId(),
                        "cancelled", "expired"));
            });
        }
    }

    private static Order getLastOrderByCaseInsensitivePlayer(String lowerPlayerName) {
        String sql = "SELECT * FROM autopix_orders WHERE LOWER(player) = ? ORDER BY created DESC, id DESC LIMIT 1;";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lowerPlayerName.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOrder(result) : null;
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar ultimo pedido de " + lowerPlayerName, exception);
            return null;
        }
    }

    public static List<DonorInfo> getTopDonors() {
        AutoPix instance = AutoPix.getInstance();
        int topLimit = instance != null
                ? Math.max(1, Math.min(100, instance.getConfig().getInt("top-doadores", 5)))
                : 5;
        return getTopDonors(topLimit);
    }

    public static List<DonorInfo> getTopDonors(int topLimit) {
        List<DonorInfo> topDonors = new ArrayList<>();
        String sql = "SELECT player AS donor, SUM(price) AS total, COUNT(*) AS orders_count FROM autopix_orders "
                + "WHERE pix IS NOT NULL GROUP BY player ORDER BY total DESC LIMIT ?;";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, Math.min(100, topLimit)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    topDonors.add(new DonorInfo(
                            result.getString("donor"),
                            roundPrice(result.getDouble("total")),
                            result.getInt("orders_count")));
                }
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao consultar ranking de doadores", exception);
        }
        return topDonors;
    }

    public static void refreshStatsCacheAsync(AutoPix ap) {
        if (ap == null || Bukkit.getServer() == null || !ap.isEnabled()) {
            refreshStatsCacheNow(5);
            return;
        }
        int topLimit = Math.max(1, Math.min(100, ap.getConfig().getInt("top-doadores", 5)));
        Bukkit.getScheduler().runTaskAsynchronously(ap, () -> refreshStatsCacheNow(topLimit));
    }

    public static void refreshStatsCacheNow(int topLimit) {
        List<DonorInfo> donors = getTopDonors(topLimit);
        AutoPixExpansion.updateTopDonorsCache(donors);

        double totalRevenue = 0.0;
        int approvedCount = 0;
        int pendingCount = 0;
        Map<String, AutoPixExpansion.PlayerStats> playerStats = new HashMap<>();

        String approvedSql = "SELECT COUNT(*) AS cnt, COALESCE(SUM(price), 0) AS rev FROM autopix_orders WHERE pix IS NOT NULL;";
        String pendingSql = "SELECT COUNT(*) AS cnt FROM autopix_pix_data WHERE status = 'pending';";
        String perPlayerSql = "SELECT player, COUNT(*) AS cnt, COALESCE(SUM(price), 0) AS spent FROM autopix_orders WHERE pix IS NOT NULL GROUP BY player;";

        try (Connection connection = getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement(approvedSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    approvedCount = rs.getInt("cnt");
                    totalRevenue = rs.getDouble("rev");
                }
            }
            try (PreparedStatement stmt = connection.prepareStatement(pendingSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    pendingCount = rs.getInt("cnt");
                }
            }
            try (PreparedStatement stmt = connection.prepareStatement(perPlayerSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String pName = rs.getString("player");
                    if (pName != null) {
                        playerStats.put(
                                pName.toLowerCase(Locale.ROOT),
                                new AutoPixExpansion.PlayerStats(rs.getInt("cnt"), rs.getDouble("spent")));
                    }
                }
            }
            AutoPixExpansion.updateGlobalStatsCache(totalRevenue, approvedCount, pendingCount);
            AutoPixExpansion.updatePlayerStatsCache(playerStats);
        } catch (SQLException exception) {
            logSqlError("Erro ao atualizar cache de estatisticas", exception);
        }
    }

    private static void logSqlError(String context, SQLException exception) {
        AutoPix instance = AutoPix.getInstance();
        if (instance != null) {
            instance.getLogger().warning(context + ": " + exception.getMessage());
        }
    }
}
