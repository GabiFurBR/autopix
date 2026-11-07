package io.github.warleysr.autopix;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Versioned, dialect-aware, idempotent database schema migration runner.
 * Supports SQLite, PostgreSQL, and MySQL without swallowing SQL errors.
 */
public final class DatabaseMigrationRunner {

    public static final int LATEST_SCHEMA_VERSION = 6;
    private static final Logger FALLBACK_LOGGER = Logger.getLogger("AutoPix-Migrations");
    private static final Object MIGRATION_LOCK = new Object();

    private DatabaseMigrationRunner() {
    }

    public static void migrate(Connection connection, String dbType, String idDefinition) throws SQLException {
        if (connection == null) {
            throw new SQLException("Conexao nula fornecida para o executor de migracoes.");
        }
        synchronized (MIGRATION_LOCK) {
            String dialect = dbType != null ? dbType.trim().toLowerCase(Locale.ROOT) : "sqlite";
            ensureMigrationsTable(connection);
            Set<Integer> appliedVersions = getAppliedVersions(connection);

            runMigration(connection, appliedVersions, 1, "v1_base_legacy_tables",
                    conn -> migrateV1BaseLegacyTables(conn, dialect, idDefinition));

            runMigration(connection, appliedVersions, 2, "v2_orders_extended_columns",
                    conn -> migrateV2OrdersExtendedColumns(conn, dialect));

            runMigration(connection, appliedVersions, 3, "v3_order_items_and_carts_snapshots",
                    conn -> migrateV3OrderItemsAndCarts(conn, dialect));

            runMigration(connection, appliedVersions, 4, "v4_multi_item_deliveries_outbox",
                    conn -> migrateV4DeliveriesOutbox(conn, dialect));

            runMigration(connection, appliedVersions, 5, "v5_payments_subscriptions_webhooks",
                    conn -> migrateV5PaymentsSubscriptionsWebhooks(conn, dialect));

            runMigration(connection, appliedVersions, 6, "v6_schema_indexes",
                    conn -> migrateV6Indexes(conn, dialect));
        }
    }

    @FunctionalInterface
    public interface MigrationStep {
        void execute(Connection connection) throws SQLException;
    }

    public static boolean runVersionedMigration(Connection connection, int version,
                                                String description, MigrationStep step) throws SQLException {
        if (connection == null) {
            throw new SQLException("Conexao nula fornecida para o executor de migracoes.");
        }
        synchronized (MIGRATION_LOCK) {
            ensureMigrationsTable(connection);
            Set<Integer> appliedVersions = getAppliedVersions(connection);
            if (appliedVersions.contains(version)) {
                return false;
            }
            runMigration(connection, appliedVersions, version, description, step);
            return true;
        }
    }

    private static void ensureMigrationsTable(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_schema_migrations ("
                    + "version INTEGER PRIMARY KEY, "
                    + "description VARCHAR(255) NOT NULL, "
                    + "applied_at TIMESTAMP NOT NULL"
                    + ");");
        }
    }

    public static Set<Integer> getAppliedVersions(Connection connection) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM autopix_schema_migrations;")) {
            while (rs.next()) {
                versions.add(rs.getInt("version"));
            }
        }
        return versions;
    }

    private static void runMigration(Connection connection, Set<Integer> appliedVersions,
                                     int version, String description, MigrationStep step) throws SQLException {
        if (appliedVersions.contains(version)) {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            step.execute(connection);

            String sql = "INSERT INTO autopix_schema_migrations (version, description, applied_at) VALUES (?, ?, ?);";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setInt(1, version);
                stmt.setString(2, description);
                stmt.setTimestamp(3, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
            }
            connection.commit();
            appliedVersions.add(version);
            logInfo("Migracao de banco V" + version + " (" + description + ") aplicada com sucesso.");
        } catch (SQLException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            logSevere("Falha critica na migracao de banco V" + version + " (" + description + "): " + ex.getMessage());
            throw new SQLException("Falha na migracao V" + version + " (" + description + "): " + ex.getMessage(), ex.getSQLState(), ex.getErrorCode(), ex);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void migrateV1BaseLegacyTables(Connection conn, String dialect, String idDefinition) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_orders ("
                    + "id " + idDefinition + ", "
                    + "player VARCHAR(36) NOT NULL, "
                    + "product VARCHAR(64) NOT NULL, "
                    + "price DECIMAL(10, 2) NOT NULL, "
                    + "created TIMESTAMP NOT NULL, "
                    + "pix VARCHAR(64) UNIQUE NULL"
                    + ");");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_pix_data ("
                    + "payment_id VARCHAR(128) PRIMARY KEY, "
                    + "order_id INTEGER NOT NULL UNIQUE, "
                    + "status VARCHAR(32), "
                    + "qr_code TEXT NOT NULL"
                    + ");");

            if ("postgresql".equalsIgnoreCase(dialect)) {
                stmt.executeUpdate("ALTER TABLE autopix_orders ALTER COLUMN player TYPE VARCHAR(36);");
                stmt.executeUpdate("ALTER TABLE autopix_orders ALTER COLUMN product TYPE VARCHAR(64);");
                stmt.executeUpdate("ALTER TABLE autopix_orders ALTER COLUMN pix TYPE VARCHAR(64);");
                stmt.executeUpdate("ALTER TABLE autopix_pix_data ALTER COLUMN status TYPE VARCHAR(32);");
                stmt.executeUpdate("ALTER TABLE autopix_pix_data ALTER COLUMN qr_code TYPE TEXT;");
                stmt.executeUpdate("ALTER TABLE autopix_pix_data DROP CONSTRAINT IF EXISTS autopix_pix_data_qr_code_key;");
            } else if ("mysql".equalsIgnoreCase(dialect)) {
                stmt.executeUpdate("ALTER TABLE autopix_orders MODIFY COLUMN player VARCHAR(36) NOT NULL;");
                stmt.executeUpdate("ALTER TABLE autopix_orders MODIFY COLUMN product VARCHAR(64) NOT NULL;");
                stmt.executeUpdate("ALTER TABLE autopix_orders MODIFY COLUMN pix VARCHAR(64) NULL;");
                stmt.executeUpdate("ALTER TABLE autopix_pix_data MODIFY COLUMN status VARCHAR(32);");
                stmt.executeUpdate("ALTER TABLE autopix_pix_data MODIFY COLUMN qr_code TEXT NOT NULL;");
            }
        }
    }

    private static void migrateV2OrdersExtendedColumns(Connection conn, String dialect) throws SQLException {
        addColumnIfMissing(conn, "autopix_orders", "player_uuid", "VARCHAR(36) NULL");
        addColumnIfMissing(conn, "autopix_orders", "status", "VARCHAR(32) DEFAULT 'PENDING'");
        addColumnIfMissing(conn, "autopix_orders", "subtotal", "DECIMAL(10, 2) NULL");
        addColumnIfMissing(conn, "autopix_orders", "discount", "DECIMAL(10, 2) NULL");
        addColumnIfMissing(conn, "autopix_orders", "coupon_code", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_orders", "currency", "VARCHAR(8) DEFAULT 'BRL'");
        addColumnIfMissing(conn, "autopix_orders", "expires_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, "autopix_orders", "paid_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, "autopix_orders", "cancelled_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, "autopix_orders", "updated_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, "autopix_orders", "idempotency_key", "VARCHAR(128) NULL");
    }

    private static void migrateV3OrderItemsAndCarts(Connection conn, String dialect) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_order_items ("
                    + "order_id INTEGER NOT NULL, "
                    + "item_index INTEGER NOT NULL, "
                    + "product_id VARCHAR(128) NULL, "
                    + "offer_id VARCHAR(64) NULL, "
                    + "product VARCHAR(128) NOT NULL, "
                    + "description TEXT NULL, "
                    + "category VARCHAR(64) NULL, "
                    + "offer_type VARCHAR(32) NULL, "
                    + "duration VARCHAR(64) NULL, "
                    + "currency VARCHAR(8) DEFAULT 'BRL', "
                    + "quantity INTEGER NOT NULL, "
                    + "unit_price DECIMAL(10, 2) NOT NULL, "
                    + "line_total DECIMAL(10, 2) NULL, "
                    + "delivery_type VARCHAR(32) NULL, "
                    + "delivery_handler VARCHAR(64) NULL, "
                    + "delivery_snapshot TEXT NULL, "
                    + "pre_commands TEXT NOT NULL, "
                    + "commands TEXT NOT NULL, "
                    + "PRIMARY KEY (order_id, item_index)"
                    + ");");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_carts ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "player_uuid VARCHAR(36) NOT NULL UNIQUE, "
                    + "coupon_id VARCHAR(64) NULL, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL"
                    + ");");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_cart_items ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "cart_id VARCHAR(64) NOT NULL, "
                    + "product_id VARCHAR(128) NOT NULL, "
                    + "offer_id VARCHAR(64) NOT NULL, "
                    + "product_name VARCHAR(128) NULL, "
                    + "description TEXT NULL, "
                    + "category VARCHAR(64) NULL, "
                    + "offer_type VARCHAR(32) NULL, "
                    + "duration VARCHAR(64) NULL, "
                    + "unit_price DECIMAL(10, 2) NULL, "
                    + "currency VARCHAR(8) DEFAULT 'BRL', "
                    + "delivery_type VARCHAR(32) NULL, "
                    + "delivery_handler VARCHAR(64) NULL, "
                    + "pre_commands TEXT NULL, "
                    + "commands TEXT NULL, "
                    + "commercial_snapshot TEXT NULL, "
                    + "quantity INTEGER NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL, "
                    + "UNIQUE (cart_id, product_id, offer_id)"
                    + ");");
        }

        addColumnIfMissing(conn, "autopix_order_items", "product_id", "VARCHAR(128) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "offer_id", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "description", "TEXT NULL");
        addColumnIfMissing(conn, "autopix_order_items", "category", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "offer_type", "VARCHAR(32) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "duration", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "currency", "VARCHAR(8) DEFAULT 'BRL'");
        addColumnIfMissing(conn, "autopix_order_items", "line_total", "DECIMAL(10, 2) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "delivery_type", "VARCHAR(32) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "delivery_handler", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_order_items", "delivery_snapshot", "TEXT NULL");

        addColumnIfMissing(conn, "autopix_cart_items", "product_name", "VARCHAR(128) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "description", "TEXT NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "category", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "offer_type", "VARCHAR(32) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "duration", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "unit_price", "DECIMAL(10, 2) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "currency", "VARCHAR(8) DEFAULT 'BRL'");
        addColumnIfMissing(conn, "autopix_cart_items", "delivery_type", "VARCHAR(32) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "delivery_handler", "VARCHAR(64) NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "pre_commands", "TEXT NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "commands", "TEXT NULL");
        addColumnIfMissing(conn, "autopix_cart_items", "commercial_snapshot", "TEXT NULL");
    }

    private static void migrateV4DeliveriesOutbox(Connection conn, String dialect) throws SQLException {
        String createNewDeliveriesSql = "CREATE TABLE IF NOT EXISTS autopix_deliveries ("
                + "delivery_id VARCHAR(128) PRIMARY KEY, "
                + "order_id INTEGER NOT NULL, "
                + "item_index INTEGER NOT NULL DEFAULT 0, "
                + "player_uuid VARCHAR(36) NULL, "
                + "status VARCHAR(32) NOT NULL, "
                + "delivery_type VARCHAR(32) NOT NULL DEFAULT 'autopix', "
                + "handler VARCHAR(64) NULL, "
                + "idempotency_key VARCHAR(128) NOT NULL UNIQUE, "
                + "claim_token VARCHAR(128) NULL, "
                + "claimed_at TIMESTAMP NULL, "
                + "execution_stage VARCHAR(32) NULL, "
                + "payload TEXT NULL, "
                + "attempts INTEGER NOT NULL DEFAULT 0, "
                + "updated_at TIMESTAMP NOT NULL, "
                + "last_error TEXT NULL, "
                + "UNIQUE (order_id, item_index)"
                + ");";

        // MySQL commits DDL implicitly. If the process stops after renaming the legacy table
        // or halfway through copying rows, JDBC rollback cannot restore the previous state.
        // Recover that intermediate state before starting the migration again.
        boolean hasLegacyBackup = tableExists(conn, "autopix_deliveries_legacy_backup");
        boolean hasCurrentTable = tableExists(conn, "autopix_deliveries");
        if (hasLegacyBackup && !hasCurrentTable) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE autopix_deliveries_legacy_backup RENAME TO autopix_deliveries;");
            }
            hasCurrentTable = true;
        } else if (hasLegacyBackup) {
            if (!columnExists(conn, "autopix_deliveries", "delivery_id")
                    || isPrimaryKeyColumn(conn, "autopix_deliveries", "order_id")) {
                throw new SQLException("Estado de migracao V4 inconsistente: tabela atual e backup legado coexistem, mas a tabela atual nao possui o esquema multi-item. Backup preservado para recuperacao manual.");
            }
            List<LegacyDeliveryRow> backupRows = readLegacyDeliveryRows(conn, "autopix_deliveries_legacy_backup");
            copyLegacyDeliveryRows(conn, backupRows);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE autopix_deliveries_legacy_backup;");
            }
        }

        if (!tableExists(conn, "autopix_deliveries")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createNewDeliveriesSql);
            }
            return;
        }

        boolean hasDeliveryId = columnExists(conn, "autopix_deliveries", "delivery_id");
        boolean orderIdIsPk = isPrimaryKeyColumn(conn, "autopix_deliveries", "order_id");

        if (!hasDeliveryId || orderIdIsPk) {
            // Migrate legacy single-delivery-per-order table to multi-item table preserving all rows
            List<LegacyDeliveryRow> existingRows = readLegacyDeliveryRows(conn, "autopix_deliveries");

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE IF EXISTS autopix_deliveries_legacy_backup;");
                stmt.executeUpdate("ALTER TABLE autopix_deliveries RENAME TO autopix_deliveries_legacy_backup;");
                stmt.executeUpdate(createNewDeliveriesSql);
            }

            if (!existingRows.isEmpty()) {
                String insertSql = "INSERT INTO autopix_deliveries "
                        + "(delivery_id, order_id, item_index, player_uuid, status, delivery_type, handler, "
                        + "idempotency_key, claim_token, claimed_at, execution_stage, payload, attempts, updated_at, last_error) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?);";
                try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                    for (LegacyDeliveryRow r : existingRows) {
                        String delivId = "deliv_" + r.orderId + "_" + r.itemIndex;
                        ins.setString(1, delivId);
                        ins.setInt(2, r.orderId);
                        ins.setInt(3, r.itemIndex);
                        ins.setString(4, r.playerUuid);
                        ins.setString(5, r.status);
                        ins.setString(6, r.deliveryType);
                        ins.setString(7, r.handler);
                        ins.setString(8, delivId);
                        ins.setString(9, r.payload);
                        ins.setInt(10, r.attempts);
                        ins.setTimestamp(11, r.updatedAt);
                        ins.setString(12, r.lastError);
                        ins.executeUpdate();
                    }
                }
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE autopix_deliveries_legacy_backup;");
            }
        } else {
            addColumnIfMissing(conn, "autopix_deliveries", "item_index", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "autopix_deliveries", "player_uuid", "VARCHAR(36) NULL");
            addColumnIfMissing(conn, "autopix_deliveries", "delivery_type", "VARCHAR(32) NOT NULL DEFAULT 'autopix'");
            addColumnIfMissing(conn, "autopix_deliveries", "handler", "VARCHAR(64) NULL");
            addColumnIfMissing(conn, "autopix_deliveries", "idempotency_key", "VARCHAR(128) NULL");
            addColumnIfMissing(conn, "autopix_deliveries", "claim_token", "VARCHAR(128) NULL");
            addColumnIfMissing(conn, "autopix_deliveries", "claimed_at", "TIMESTAMP NULL");
            addColumnIfMissing(conn, "autopix_deliveries", "execution_stage", "VARCHAR(32) NULL");
            addColumnIfMissing(conn, "autopix_deliveries", "payload", "TEXT NULL");
        }
    }

    private static List<LegacyDeliveryRow> readLegacyDeliveryRows(Connection conn, String tableName) throws SQLException {
        List<LegacyDeliveryRow> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + ";")) {
            Set<String> cols = getResultSetColumns(rs);
            while (rs.next()) {
                LegacyDeliveryRow row = new LegacyDeliveryRow();
                row.orderId = rs.getInt("order_id");
                row.itemIndex = cols.contains("item_index") ? rs.getInt("item_index") : 0;
                row.playerUuid = cols.contains("player_uuid") ? rs.getString("player_uuid") : null;
                row.status = rs.getString("status") != null ? rs.getString("status") : "pending";
                row.deliveryType = cols.contains("delivery_type") && rs.getString("delivery_type") != null
                        ? rs.getString("delivery_type") : "autopix";
                row.handler = cols.contains("handler") ? rs.getString("handler") : null;
                row.payload = cols.contains("payload") ? rs.getString("payload") : null;
                row.attempts = cols.contains("attempts") ? rs.getInt("attempts") : 0;
                row.updatedAt = cols.contains("updated_at") && rs.getTimestamp("updated_at") != null
                        ? rs.getTimestamp("updated_at") : Timestamp.from(Instant.now());
                row.lastError = cols.contains("last_error") ? rs.getString("last_error") : null;
                rows.add(row);
            }
        }
        return rows;
    }

    private static void copyLegacyDeliveryRows(Connection conn, List<LegacyDeliveryRow> rows) throws SQLException {
        String insertSql = "INSERT INTO autopix_deliveries "
                + "(delivery_id, order_id, item_index, player_uuid, status, delivery_type, handler, "
                + "idempotency_key, claim_token, claimed_at, execution_stage, payload, attempts, updated_at, last_error) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?);";
        for (LegacyDeliveryRow row : rows) {
            if (deliveryRowExists(conn,
                    "SELECT delivery_id FROM autopix_deliveries WHERE order_id = ? AND item_index = ?;",
                    row.orderId, row.itemIndex)) {
                continue;
            }
            String deliveryId = "deliv_" + row.orderId + "_" + row.itemIndex;
            try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                ins.setString(1, deliveryId);
                ins.setInt(2, row.orderId);
                ins.setInt(3, row.itemIndex);
                ins.setString(4, row.playerUuid);
                ins.setString(5, row.status);
                ins.setString(6, row.deliveryType);
                ins.setString(7, row.handler);
                ins.setString(8, deliveryId);
                ins.setString(9, row.payload);
                ins.setInt(10, row.attempts);
                ins.setTimestamp(11, row.updatedAt);
                ins.setString(12, row.lastError);
                ins.executeUpdate();
            }
        }
    }

    private static void migrateV5PaymentsSubscriptionsWebhooks(Connection conn, String dialect) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_payments ("
                    + "payment_id VARCHAR(128) PRIMARY KEY, "
                    + "order_id INTEGER NOT NULL, "
                    + "method VARCHAR(32) NOT NULL, "
                    + "amount DECIMAL(10, 2) NOT NULL, "
                    + "currency VARCHAR(8) DEFAULT 'BRL', "
                    + "status VARCHAR(32) NOT NULL, "
                    + "external_reference VARCHAR(128) NULL, "
                    + "qr_code TEXT NULL, "
                    + "qr_code_base64 TEXT NULL, "
                    + "copy_paste TEXT NULL, "
                    + "paid_at TIMESTAMP NULL, "
                    + "expires_at TIMESTAMP NULL, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL"
                    + ");");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_subscriptions ("
                    + "subscription_id VARCHAR(128) PRIMARY KEY, "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "product_id VARCHAR(128) NOT NULL, "
                    + "plan_id VARCHAR(64) NOT NULL, "
                    + "provider_subscription_id VARCHAR(128) NULL, "
                    + "status VARCHAR(32) NOT NULL, "
                    + "price DECIMAL(10, 2) NOT NULL, "
                    + "currency VARCHAR(8) DEFAULT 'BRL', "
                    + "interval_type VARCHAR(32) NOT NULL, "
                    + "interval_count INTEGER NOT NULL DEFAULT 1, "
                    + "current_period_start TIMESTAMP NULL, "
                    + "current_period_end TIMESTAMP NULL, "
                    + "next_billing_at TIMESTAMP NULL, "
                    + "grace_until TIMESTAMP NULL, "
                    + "cancelled_at TIMESTAMP NULL, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL"
                    + ");");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_subscription_invoices ("
                    + "invoice_id VARCHAR(128) PRIMARY KEY, "
                    + "subscription_id VARCHAR(128) NOT NULL, "
                    + "cycle_number INTEGER NOT NULL DEFAULT 1, "
                    + "order_id INTEGER NULL, "
                    + "provider_payment_id VARCHAR(128) NULL, "
                    + "amount DECIMAL(10, 2) NOT NULL, "
                    + "currency VARCHAR(8) DEFAULT 'BRL', "
                    + "status VARCHAR(32) NOT NULL, "
                    + "billing_date TIMESTAMP NOT NULL, "
                    + "due_date TIMESTAMP NOT NULL, "
                    + "paid_at TIMESTAMP NULL, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "UNIQUE (subscription_id, cycle_number)"
                    + ");");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS autopix_webhook_events ("
                    + "event_id VARCHAR(128) PRIMARY KEY, "
                    + "idempotency_key VARCHAR(128) UNIQUE, "
                    + "topic VARCHAR(64) NULL, "
                    + "payment_id VARCHAR(128) NULL, "
                    + "status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING', "
                    + "payload TEXT NULL, "
                    + "error_message TEXT NULL, "
                    + "last_error TEXT NULL, "
                    + "processed_at TIMESTAMP NULL, "
                    + "created_at TIMESTAMP NOT NULL"
                    + ");");
        }

        addColumnIfMissing(conn, "autopix_payments", "currency", "VARCHAR(8) DEFAULT 'BRL'");
        addColumnIfMissing(conn, "autopix_payments", "external_reference", "VARCHAR(128) NULL");

        addColumnIfMissing(conn, "autopix_subscriptions", "provider_subscription_id", "VARCHAR(128) NULL");
        addColumnIfMissing(conn, "autopix_subscriptions", "currency", "VARCHAR(8) DEFAULT 'BRL'");
        addColumnIfMissing(conn, "autopix_subscriptions", "grace_until", "TIMESTAMP NULL");

        addColumnIfMissing(conn, "autopix_subscription_invoices", "cycle_number", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(conn, "autopix_subscription_invoices", "provider_payment_id", "VARCHAR(128) NULL");
        addColumnIfMissing(conn, "autopix_subscription_invoices", "currency", "VARCHAR(8) DEFAULT 'BRL'");

        addColumnIfMissing(conn, "autopix_webhook_events", "payment_id", "VARCHAR(128) NULL");
        addColumnIfMissing(conn, "autopix_webhook_events", "status", "VARCHAR(32) NOT NULL DEFAULT 'PROCESSED'");
        addColumnIfMissing(conn, "autopix_webhook_events", "error_message", "TEXT NULL");
        addColumnIfMissing(conn, "autopix_webhook_events", "last_error", "TEXT NULL");
    }

    private static void migrateV6Indexes(Connection conn, String dialect) throws SQLException {
        createIndexIfMissing(conn, "autopix_orders", "idx_autopix_orders_player", "player", false);
        createIndexIfMissing(conn, "autopix_orders", "idx_autopix_orders_uuid", "player_uuid", false);
        createIndexIfMissing(conn, "autopix_orders", "idx_autopix_orders_status", "status", false);
        createIndexIfMissing(conn, "autopix_orders", "idx_autopix_orders_idemp", "idempotency_key", true);
        createIndexIfMissing(conn, "autopix_pix_data", "idx_autopix_pix_status", "status", false);
        createIndexIfMissing(conn, "autopix_deliveries", "idx_autopix_deliv_status", "status", false);
        createIndexIfMissing(conn, "autopix_deliveries", "idx_autopix_deliv_order_item", "order_id, item_index", true);
        createIndexIfMissing(conn, "autopix_cart_items", "idx_autopix_cart_items_cart", "cart_id", false);
        createIndexIfMissing(conn, "autopix_subscriptions", "idx_autopix_subs_player", "player_uuid", false);
        createIndexIfMissing(conn, "autopix_subscriptions", "idx_autopix_subs_status", "status", false);
        createIndexIfMissing(conn, "autopix_subscription_invoices", "idx_autopix_invoices_sub", "subscription_id", false);
        createIndexIfMissing(conn, "autopix_subscription_invoices", "idx_autopix_invoices_sub_cycle", "subscription_id, cycle_number", true);
    }

    public static boolean tableExists(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String candidate : new String[]{tableName, tableName.toLowerCase(Locale.ROOT), tableName.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, candidate, new String[]{"TABLE"})) {
                while (rs.next()) {
                    String found = rs.getString("TABLE_NAME");
                    if (found != null && found.equalsIgnoreCase(tableName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String tableCandidate : new String[]{tableName, tableName.toLowerCase(Locale.ROOT), tableName.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tableCandidate, null)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null && col.equalsIgnoreCase(columnName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isPrimaryKeyColumn(Connection conn, String tableName, String columnName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        List<String> pkCols = new ArrayList<>();
        for (String tableCandidate : new String[]{tableName, tableName.toLowerCase(Locale.ROOT), tableName.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getPrimaryKeys(conn.getCatalog(), null, tableCandidate)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null) {
                        pkCols.add(col.toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (!pkCols.isEmpty()) break;
        }
        return pkCols.size() == 1 && pkCols.get(0).equalsIgnoreCase(columnName);
    }

    public static boolean indexExists(Connection conn, String tableName, String indexName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String tableCandidate : new String[]{tableName, tableName.toLowerCase(Locale.ROOT), tableName.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), null, tableCandidate, false, false)) {
                while (rs.next()) {
                    String idx = rs.getString("INDEX_NAME");
                    if (idx != null && idx.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addColumnIfMissing(Connection conn, String tableName, String columnName, String definition) throws SQLException {
        if (columnExists(conn, tableName, columnName)) {
            return;
        }
        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition + ";";
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private static void createIndexIfMissing(Connection conn, String tableName, String indexName, String columns, boolean unique) throws SQLException {
        if (indexExists(conn, tableName, indexName)) {
            return;
        }
        String uniqueKeyword = unique ? "UNIQUE " : "";
        String sql = "CREATE " + uniqueKeyword + "INDEX " + indexName + " ON " + tableName + "(" + columns + ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private static Set<String> getResultSetColumns(ResultSet rs) throws SQLException {
        Set<String> cols = new HashSet<>();
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        for (int i = 1; i <= count; i++) {
            String name = meta.getColumnName(i);
            if (name != null) {
                cols.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return cols;
    }

    private static void logInfo(String message) {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null) {
            plugin.getLogger().info(message);
        } else {
            FALLBACK_LOGGER.info(message);
        }
    }

    private static void logSevere(String message) {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null) {
            plugin.getLogger().severe(message);
        } else {
            FALLBACK_LOGGER.severe(message);
        }
    }

    private static final class LegacyDeliveryRow {
        int orderId;
        int itemIndex;
        String playerUuid;
        String status;
        String deliveryType;
        String handler;
        String payload;
        int attempts;
        Timestamp updatedAt;
        String lastError;
    }
}
