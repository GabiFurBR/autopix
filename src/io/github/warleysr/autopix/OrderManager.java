package io.github.warleysr.autopix;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
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

import javax.imageio.ImageIO;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import io.github.warleysr.autopix.discord.DiscordNotifier;
import io.github.warleysr.autopix.domain.DonorInfo;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.PaymentInfo;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.PaymentApprovedEvent;
import io.github.warleysr.autopix.events.PaymentCancelledEvent;
import io.github.warleysr.autopix.events.PaymentRejectedEvent;
import io.github.warleysr.autopix.expansion.AutoPixExpansion;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.github.warleysr.autopix.qrcode.ImageCreator;

public class OrderManager {

    public enum TransactionCheck {
        NOT_VALIDATED,
        ALREADY_VALIDATED,
        DB_ERROR
    }

    private static volatile HikariDataSource dataSource;
    private static volatile String activeDbType = "sqlite";

    private static final AtomicBoolean VALIDATION_RUNNING = new AtomicBoolean(false);
    private static final Set<String> CREATING_ORDER_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, UUID> ONLINE_PLAYERS = new ConcurrentHashMap<>();

    protected static synchronized boolean startOrderManager(AutoPix ap) throws SQLException {
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
                ap.getLogger().info("Banco de dados conectado (" + normalizedType + ") e tabelas verificadas.");
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
            createTables(candidate, idDefinition, normalizedType);

            HikariDataSource previous = dataSource;
            dataSource = candidate;
            activeDbType = normalizedType;
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

    private static void createTables(HikariDataSource source, String idDefinition, String dbType) throws SQLException {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_orders "
                    + "(id " + idDefinition + ", player VARCHAR(36) NOT NULL, "
                    + "product VARCHAR(64) NOT NULL, price DECIMAL(10, 2) NOT NULL, "
                    + "created TIMESTAMP NOT NULL, pix VARCHAR(64) UNIQUE NULL);");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_pix_data "
                    + "(payment_id VARCHAR(128) PRIMARY KEY, order_id INTEGER NOT NULL UNIQUE, "
                    + "status VARCHAR(32), qr_code TEXT NOT NULL);");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_deliveries "
                    + "(order_id INTEGER PRIMARY KEY, status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at TIMESTAMP NOT NULL, last_error TEXT NULL);");
            ensureSchemaUpgraded(statement, dbType);
        }
    }

    private static void ensureSchemaUpgraded(Statement statement, String dbType) {
        if ("postgresql".equalsIgnoreCase(dbType)) {
            executeSafeAlter(statement, "ALTER TABLE autopix_orders ALTER COLUMN player TYPE VARCHAR(36);");
            executeSafeAlter(statement, "ALTER TABLE autopix_orders ALTER COLUMN product TYPE VARCHAR(64);");
            executeSafeAlter(statement, "ALTER TABLE autopix_orders ALTER COLUMN pix TYPE VARCHAR(64);");
            executeSafeAlter(statement, "ALTER TABLE autopix_pix_data ALTER COLUMN status TYPE VARCHAR(32);");
            executeSafeAlter(statement, "ALTER TABLE autopix_pix_data ALTER COLUMN qr_code TYPE TEXT;");
            executeSafeAlter(statement, "ALTER TABLE autopix_pix_data DROP CONSTRAINT IF EXISTS autopix_pix_data_qr_code_key;");
        } else if ("mysql".equalsIgnoreCase(dbType)) {
            executeSafeAlter(statement, "ALTER TABLE autopix_orders MODIFY COLUMN player VARCHAR(36) NOT NULL;");
            executeSafeAlter(statement, "ALTER TABLE autopix_orders MODIFY COLUMN product VARCHAR(64) NOT NULL;");
            executeSafeAlter(statement, "ALTER TABLE autopix_orders MODIFY COLUMN pix VARCHAR(64) NULL;");
            executeSafeAlter(statement, "ALTER TABLE autopix_pix_data MODIFY COLUMN status VARCHAR(32);");
            executeSafeAlter(statement, "ALTER TABLE autopix_pix_data MODIFY COLUMN qr_code TEXT NOT NULL;");
        }
    }

    private static void executeSafeAlter(Statement statement, String sql) {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException ignored) {
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

    private static Connection getConnection() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) {
            throw new SQLException("O pool de conexoes do AutoPix nao esta inicializado.");
        }
        return source.getConnection();
    }

    public static synchronized void shutdown() {
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
        if (playerName == null || product == null) return null;
        float roundedPrice = roundPrice(price);
        Timestamp created = Timestamp.from(Instant.now());
        String insertOrderSql = "INSERT INTO autopix_orders (player, product, price, created) VALUES (?, ?, ?, ?);";
        String insertPixSql = "INSERT INTO autopix_pix_data (payment_id, order_id, status, qr_code) VALUES (?, ?, ?, ?);";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStmt = prepareInsertIdStatement(connection, insertOrderSql)) {
                orderStmt.setString(1, playerName);
                orderStmt.setString(2, product);
                orderStmt.setBigDecimal(3, BigDecimal.valueOf(roundedPrice).setScale(2, RoundingMode.HALF_UP));
                orderStmt.setTimestamp(4, created);
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

                Order order = new Order(playerName, product, roundedPrice, created.getTime());
                order.setId(orderId);

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

                connection.commit();
                return order;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao criar pedido no banco de dados", exception);
            return null;
        }
    }

    private static void insertDeliveryOutbox(Connection connection, int orderId) throws SQLException {
        String sql = "INSERT INTO autopix_deliveries (order_id, status, attempts, updated_at, last_error) "
                + "VALUES (?, 'pending', 0, CURRENT_TIMESTAMP, NULL);";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            statement.executeUpdate();
        }
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
                return result.next() ? readOrder(result) : null;
            }
        } catch (SQLException exception) {
            logSqlError("Erro ao buscar pedido #" + orderId, exception);
            return null;
        }
    }

    private static Order readOrder(ResultSet result) throws SQLException {
        Timestamp created = result.getTimestamp("created");
        long createdMillis = created != null ? created.getTime() : System.currentTimeMillis();
        Order order = new Order(result.getString("player"), result.getString("product"),
                roundPrice(result.getDouble("price")), createdMillis);
        order.setId(result.getInt("id"));
        order.setTransaction(result.getString("pix"));
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
        String updateOrder = "UPDATE autopix_orders SET pix = ? WHERE id = ? AND pix IS NULL;";
        String updatePayment = "UPDATE autopix_pix_data SET status = 'approved' WHERE order_id = ? AND status = 'pending';";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatement = connection.prepareStatement(updateOrder);
                 PreparedStatement paymentStatement = connection.prepareStatement(updatePayment)) {
                orderStatement.setString(1, transactionId.trim());
                orderStatement.setInt(2, order.getId());
                if (orderStatement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }

                paymentStatement.setInt(1, order.getId());
                paymentStatement.executeUpdate();

                insertDeliveryOutbox(connection, order.getId());
                connection.commit();
                order.setTransaction(transactionId.trim());
                return true;
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
    }

    public static boolean approvePayment(PixData pixData, Order order, String transactionId) {
        if (pixData == null || order == null || transactionId == null || transactionId.trim().isEmpty()) {
            return false;
        }
        String updateOrder = "UPDATE autopix_orders SET pix = ? WHERE id = ? AND pix IS NULL;";
        String updatePayment = "UPDATE autopix_pix_data SET status = 'approved' WHERE payment_id = ? AND status = 'pending';";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatement = connection.prepareStatement(updateOrder);
                 PreparedStatement paymentStatement = connection.prepareStatement(updatePayment)) {
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

                insertDeliveryOutbox(connection, order.getId());
                connection.commit();
                pixData.setStatus("approved");
                order.setTransaction(transactionId.trim());
                return true;
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
    }

    public static boolean setPixDataStatus(PixData pixData, String status) {
        if (pixData == null || status == null || status.trim().isEmpty()) return false;
        String sql = "UPDATE autopix_pix_data SET status = ? WHERE payment_id = ? AND status <> 'approved';";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.trim());
            statement.setString(2, pixData.getPaymentId());
            boolean updated = statement.executeUpdate() > 0;
            if (updated) pixData.setStatus(status.trim());
            return updated;
        } catch (SQLException exception) {
            logSqlError("Erro ao atualizar status do pagamento #" + pixData.getPaymentId(), exception);
            return false;
        }
    }

    public static boolean cancelPendingPixData(PixData pixData) {
        if (pixData == null) return false;
        String sql = "UPDATE autopix_pix_data SET status = 'cancelled' WHERE payment_id = ? AND status = 'pending';";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pixData.getPaymentId());
            boolean updated = statement.executeUpdate() > 0;
            if (updated) pixData.setStatus("cancelled");
            return updated;
        } catch (SQLException exception) {
            logSqlError("Erro ao cancelar pagamento pendente #" + pixData.getPaymentId(), exception);
            return false;
        }
    }

    protected static void validatePendings(AutoPix ap) {
        if (!VALIDATION_RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            List<PixData> pendings = getAllPendingPixData();
            if (pendings.isEmpty()) return;

            for (PixData pd : pendings) {
                Order order = getOrderById(pd.getOrderId());
                if (order == null) continue;
                if (order.isValidated()) {
                    setPixDataStatus(pd, "approved");
                    continue;
                }

                if (!isPlayerOnline(order.getPlayer())) continue;

                OrderProduct op = InventoryManager.getProductByOrder(order);
                if (op == null) continue;

                PaymentInfo info = MercadoPagoAPI.getPayment(ap, pd.getPaymentId());
                if (info == null || info.getStatus() == null) continue;

                String mpStatus = info.getStatus().trim().toLowerCase(Locale.ROOT);
                if (!"approved".equals(mpStatus)) {
                    if (!mpStatus.equalsIgnoreCase(pd.getStatus())) {
                        if (setPixDataStatus(pd, mpStatus)) {
                            handleNonApprovedStatusTransition(ap, order, pd, mpStatus, info.getPaidAmount());
                        }
                    }
                    continue;
                }

                if (toCents(order.getPrice()) != toCents(info.getPaidAmount())) {
                    ap.getLogger().warning("Pagamento aprovado com valor divergente. Pedido #" + order.getId()
                            + " (esperado R$" + String.format(Locale.ROOT, "%.2f", order.getPrice())
                            + ", pago R$" + String.format(Locale.ROOT, "%.2f", info.getPaidAmount())
                            + ") foi mantido sem entrega; verifique no Mercado Pago.");
                    if (setPixDataStatus(pd, "amount_mismatch")) {
                        handleNonApprovedStatusTransition(ap, order, pd, "amount_mismatch", info.getPaidAmount());
                    }
                    continue;
                }

                if (info.getTransactionId() == null || info.getTransactionId().trim().isEmpty()) continue;
                if (!isPlayerOnline(order.getPlayer())) continue;

                if (!approvePayment(pd, order, info.getTransactionId())) continue;

                deliverApprovedOrderOnMainThread(ap, order, op, pd.getPaymentId(), info.getTransactionId());
            }
        } finally {
            VALIDATION_RUNNING.set(false);
        }
    }

    private static void handleNonApprovedStatusTransition(AutoPix ap, Order order, PixData pd,
                                                          String newStatus, double paidAmount) {
        refreshStatsCacheAsync(ap);
        UUID playerUuid = getOnlinePlayerUuid(order.getPlayer());

        new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayerExact(order.getPlayer());
                if ("cancelled".equalsIgnoreCase(newStatus)) {
                    if (p != null && p.isOnline()) {
                        InventoryManager.removeUnpaidMaps(p);
                        SoundManager.play(p, SoundManager.PAYMENT_CANCELLED);
                        MSG.sendActionBar(p, "actionbar-cancelado");
                        InventoryManager.refreshOpenInventory(p);
                    }
                    AutoPixEventDispatcher.dispatch(new PaymentCancelledEvent(
                            order.getId(), order.getPlayer(), playerUuid,
                            order.getProduct(), order.getPrice(), pd.getPaymentId(),
                            newStatus, "external"));
                } else if ("rejected".equalsIgnoreCase(newStatus) || "amount_mismatch".equalsIgnoreCase(newStatus)) {
                    if (p != null && p.isOnline()) {
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
            }
        }.runTask(ap);
    }

    private static boolean claimDelivery(int orderId) {
        String sql = "UPDATE autopix_deliveries SET status = 'processing', attempts = attempts + 1, "
                + "updated_at = CURRENT_TIMESTAMP, last_error = NULL WHERE order_id = ? AND status = 'pending';";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            logSqlError("Erro ao reservar entrega do pedido #" + orderId, exception);
            return false;
        }
    }

    private static void completeDeliveryAsync(AutoPix ap, int orderId, String status, String error) {
        Bukkit.getScheduler().runTaskAsynchronously(ap, () -> {
            String sql = "UPDATE autopix_deliveries SET status = ?, updated_at = CURRENT_TIMESTAMP, last_error = ? "
                    + "WHERE order_id = ? AND status = 'processing';";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, error);
                statement.setInt(3, orderId);
                if (statement.executeUpdate() != 1) {
                    ap.getLogger().warning("Nao foi possivel finalizar o registro de entrega do pedido #" + orderId
                            + ". Verifique autopix_deliveries.");
                }
            } catch (SQLException exception) {
                logSqlError("Erro ao finalizar entrega do pedido #" + orderId, exception);
            }
        });
    }

    private static void markDeliveryForReview(int orderId, String reason) {
        String sql = "UPDATE autopix_deliveries SET status = 'needs_review', updated_at = CURRENT_TIMESTAMP, "
                + "last_error = ? WHERE order_id = ? AND status IN ('pending', 'processing');";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason);
            statement.setInt(2, orderId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            logSqlError("Erro ao sinalizar entrega para revisao #" + orderId, exception);
        }
    }

    public static void recoverPendingDeliveries(AutoPix ap) {
        if (ap == null || !ap.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(ap, () -> {
            String interruptedSql = "UPDATE autopix_deliveries SET status = 'needs_review', "
                    + "updated_at = CURRENT_TIMESTAMP, last_error = 'Servidor reiniciou durante a entrega; "
                    + "verifique se os comandos de recompensa foram executados antes de repetir.' "
                    + "WHERE status = 'processing';";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(interruptedSql)) {
                int interrupted = statement.executeUpdate();
                if (interrupted > 0) {
                    ap.getLogger().warning(interrupted + " entrega(s) interrompida(s) precisam de revisao manual. "
                            + "Nao serao repetidas automaticamente para evitar recompensas duplicadas.");
                }
            } catch (SQLException exception) {
                logSqlError("Erro ao recuperar entregas interrompidas", exception);
                return;
            }

            List<Integer> pendingIds = new ArrayList<>();
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT order_id FROM autopix_deliveries WHERE status = 'pending' ORDER BY order_id ASC;");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) pendingIds.add(result.getInt("order_id"));
            } catch (SQLException exception) {
                logSqlError("Erro ao listar entregas pendentes", exception);
                return;
            }

            for (Integer orderId : pendingIds) {
                Order order = getOrderById(orderId);
                if (order == null || !order.isValidated()) {
                    markDeliveryForReview(orderId, "Pedido nao encontrado ou sem aprovacao registrada.");
                    continue;
                }
                OrderProduct product = InventoryManager.getProductByOrder(order);
                if (product == null) {
                    markDeliveryForReview(orderId, "Produto nao existe mais na configuracao menu.*.produtos.");
                    ap.getLogger().warning("Entrega do pedido #" + orderId
                            + " exige revisao: produto ausente na configuracao.");
                    continue;
                }
                PixData pixData = getPixData(order);
                deliverApprovedOrderOnMainThread(ap, order, product,
                        pixData != null ? pixData.getPaymentId() : null, order.getTransaction());
            }
        });
    }

    public static void deliverApprovedOrderOnMainThread(AutoPix ap, Order order, OrderProduct op,
                                                        String paymentId, String transactionId) {
        if (ap == null || order == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(ap, () -> {
            if (!claimDelivery(order.getId())) return;

            refreshStatsCacheAsync(ap);
            DiscordNotifier.notifyPaymentApproved(ap, order);
            UUID playerUuid = getOnlinePlayerUuid(order.getPlayer());

            Bukkit.getScheduler().runTask(ap, () -> {
                try {
                    Player p = Bukkit.getPlayerExact(order.getPlayer());
                    String formattedPrice = String.format(Locale.ROOT, "%.2f", order.getPrice()).replace('.', ',');

                    if (p != null && p.isOnline()) {
                        try {
                            int mapSlot = InventoryManager.findPaymentMapSlot(p);
                            if (mapSlot >= 0) {
                                try (InputStream successStream = ap.getResource("success.png")) {
                                    if (successStream != null) {
                                        BufferedImage gif = ImageIO.read(successStream);
                                        ItemStack successMap = ImageCreator.generateMap(gif, p, null);
                                        p.getInventory().setItem(mapSlot, successMap);
                                    }
                                }
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        if (p.isOnline()) InventoryManager.removeUnpaidMaps(p);
                                    }
                                }.runTaskLater(ap, 100L);
                            } else {
                                InventoryManager.removeUnpaidMaps(p);
                            }
                        } catch (Exception exception) {
                            ap.getLogger().warning("Nao foi possivel atualizar o mapa de pagamento de "
                                    + p.getName() + ": " + exception.getMessage());
                        }

                        SoundManager.play(p, SoundManager.PAYMENT_APPROVED);
                        MSG.sendTitle(p, "titulo-aprovado", "subtitulo-aprovado", 10, 70, 20,
                                "{produto}", order.getProduct(),
                                "{preco}", formattedPrice,
                                "{id}", Integer.toString(order.getId()));
                        MSG.sendActionBar(p, "actionbar-aprovado",
                                "{produto}", order.getProduct(), "{preco}", formattedPrice);
                        MSG.sendMessage(p, "pagamento-aprovado-chat",
                                "{produto}", order.getProduct(),
                                "{preco}", formattedPrice,
                                "{id}", Integer.toString(order.getId()));
                        InventoryManager.refreshOpenInventory(p);
                    }

                    if (op != null && op.getCommands() != null) {
                        for (String command : op.getCommands()) {
                            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                    command.replace("{player}", order.getPlayer()).replace('&', '\u00a7'));
                            if (!dispatched) {
                                throw new IllegalStateException("Comando de recompensa nao foi aceito: "
                                        + command.replace("{player}", order.getPlayer()));
                            }
                        }
                    }

                    AutoPixEventDispatcher.dispatch(new PaymentApprovedEvent(
                            order.getId(), order.getPlayer(),
                            p != null ? p.getUniqueId() : playerUuid,
                            order.getProduct(), order.getPrice(), paymentId, transactionId));
                    completeDeliveryAsync(ap, order.getId(), "delivered", null);
                } catch (Exception exception) {
                    completeDeliveryAsync(ap, order.getId(), "needs_review", exception.getMessage());
                    ap.getLogger().severe("Entrega do pedido #" + order.getId()
                            + " falhou ou ficou incerta. Verifique antes de repetir: " + exception.getMessage());
                }
            });
        });
    }

    protected static void processOrderExpirations(AutoPix ap) {
        int maxMinutes = Math.max(1, ap.getConfig().getInt("mapa.tempo-pagar", 30));
        boolean automaticMode = ap.getConfig().getBoolean("automatico.ativado", true);

        for (Map.Entry<String, UUID> entry : ONLINE_PLAYERS.entrySet()) {
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
                // Never close the local record before confirming the remote payment state.
                // A payment can be approved between the expiration check and the cancellation request.
                PaymentInfo currentInfo = MercadoPagoAPI.getPayment(ap, pd.getPaymentId());
                if (currentInfo == null || currentInfo.getStatus() == null) {
                    continue; // Keep it pending so the next cycle can reconcile with Mercado Pago.
                }

                String currentStatus = currentInfo.getStatus().trim().toLowerCase(Locale.ROOT);
                if ("approved".equals(currentStatus)) {
                    validatePendings(ap);
                    continue;
                }

                if ("cancelled".equals(currentStatus) || "rejected".equals(currentStatus)) {
                    if (setPixDataStatus(pd, currentStatus)) {
                        handleNonApprovedStatusTransition(ap, order, pd, currentStatus, currentInfo.getPaidAmount());
                    }
                    continue;
                }

                // Mercado Pago only allows cancellation from these states.
                if (!"pending".equals(currentStatus)
                        && !"in_process".equals(currentStatus)
                        && !"authorized".equals(currentStatus)) {
                    continue; // Unknown/non-cancellable state: do not invent a local cancellation.
                }

                try {
                    if (!MercadoPagoAPI.cancelPayment(ap, pd.getPaymentId())) {
                        continue; // Retry/reconcile later; do not hide a possibly paid transaction.
                    }
                } catch (Exception exception) {
                    ap.getLogger().warning("Erro ao cancelar pedido expirado #" + order.getId()
                            + " no Mercado Pago: " + exception.getMessage());
                    continue;
                }

                // Confirm the final state after the PUT. HTTP success alone is not our source of truth.
                PaymentInfo confirmedInfo = MercadoPagoAPI.getPayment(ap, pd.getPaymentId());
                if (confirmedInfo == null || confirmedInfo.getStatus() == null) {
                    continue;
                }

                String confirmedStatus = confirmedInfo.getStatus().trim().toLowerCase(Locale.ROOT);
                if ("approved".equals(confirmedStatus)) {
                    validatePendings(ap);
                    continue;
                }
                if (!"cancelled".equals(confirmedStatus)) {
                    continue;
                }
            }

            // In automatic mode this runs only after Mercado Pago confirmed cancellation.
            // In manual mode there is no remote payment record to cancel.
            if (!cancelPendingPixData(pd)) {
                continue;
            }

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
