package io.github.warleysr.autopix.delivery;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.discord.DiscordNotifier;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.DeliveryCompletedEvent;
import io.github.warleysr.autopix.events.DeliveryNeedsReviewEvent;
import io.github.warleysr.autopix.events.DeliveryStartedEvent;
import io.github.warleysr.autopix.events.PaymentApprovedEvent;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.qrcode.ImageCreator;

/**
 * Atomic, idempotent multi-item delivery manager supporting AUTOPIX, SKRIPT, and HYBRID
 * delivery strategies, with persistent outbox queueing for offline players, claim tokens,
 * execution stage tracking, and safe crash recovery.
 */
public final class DeliveryManager {

    public static final String STAGE_CLAIMED = "CLAIMED";
    public static final String STAGE_EXECUTING_COMMANDS = "EXECUTING_COMMANDS";
    public static final String STAGE_COMPLETED = "COMPLETED";
    public static final String STAGE_NEEDS_REVIEW = "NEEDS_REVIEW";

    private DeliveryManager() {
    }

    public static String buildDeliveryId(int orderId, int itemIndex) {
        return "deliv_" + orderId + "_" + itemIndex;
    }

    public static void deliverApprovedOrder(AutoPix ap, Order order, List<OrderItem> items,
                                            String paymentId, String transactionId) {
        if (order == null) return;

        Runnable asyncWork = () -> {
            List<OrderItem> deliveryItems = items != null && !items.isEmpty()
                    ? items
                    : OrderManager.getOrderItems(order.getId());

            UUID playerUuid = order.getPlayerUuid();
            if (playerUuid == null) {
                playerUuid = OrderManager.getOnlinePlayerUuid(order.getPlayer());
            }

            ensureOutboxQueued(order, deliveryItems, playerUuid);

            if (ap != null) {
                OrderManager.refreshStatsCacheAsync(ap);
                DiscordNotifier.notifyPaymentApproved(ap, order);
            }

            Player onlinePlayer = null;
            if (Bukkit.getServer() != null) {
                onlinePlayer = Bukkit.getPlayerExact(order.getPlayer());
                if (onlinePlayer == null && playerUuid != null) {
                    onlinePlayer = Bukkit.getPlayer(playerUuid);
                }
            }

            boolean isOnline = (onlinePlayer != null && onlinePlayer.isOnline())
                    || OrderManager.isPlayerOnline(order.getPlayer());

            final Player finalPlayer = onlinePlayer;
            final UUID finalUuid = finalPlayer != null ? finalPlayer.getUniqueId() : playerUuid;

            if (!isOnline) {
                if (ap != null) {
                    ap.getLogger().info("Jogador " + order.getPlayer() + " offline. Entrega do pedido #"
                            + order.getId() + " registrada no outbox para entrega ao conectar.");
                }
                Runnable offlineApprovedEventTask = () -> AutoPixEventDispatcher.dispatch(new PaymentApprovedEvent(
                        order.getId(), order.getPlayer(), finalUuid,
                        order.getProduct(), order.getPrice(), paymentId, transactionId,
                        deliveryItems));
                if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
                    Bukkit.getScheduler().runTask(ap, offlineApprovedEventTask);
                } else {
                    offlineApprovedEventTask.run();
                }
                return;
            }

            Runnable mainThreadDelivery = () -> {
                if (finalPlayer != null && finalPlayer.isOnline() && ap != null) {
                    showSuccessFeedback(ap, finalPlayer, order);
                }

                AutoPixEventDispatcher.dispatch(new PaymentApprovedEvent(
                        order.getId(), order.getPlayer(), finalUuid,
                        order.getProduct(), order.getPrice(), paymentId, transactionId,
                        deliveryItems));

                Consumer<String> bukkitCommandRunner = rendered -> {
                    if (Bukkit.getServer() != null) {
                        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
                        if (!dispatched) {
                            throw new IllegalStateException("Comando de recompensa rejeitado pelo console: " + rendered);
                        }
                    }
                };

                processOrderDeliveryItemsSync(order, deliveryItems, finalUuid, bukkitCommandRunner);
            };

            if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
                Bukkit.getScheduler().runTask(ap, mainThreadDelivery);
            } else {
                mainThreadDelivery.run();
            }
        };

        if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(ap, asyncWork);
        } else {
            asyncWork.run();
        }
    }

    /**
     * Executes delivery for all pending items of an order synchronously on the calling thread,
     * using atomic per-item database claims and tracking execution stages.
     * Returns the number of items successfully delivered during this invocation.
     */
    public static int processOrderDeliveryItemsSync(Order order, List<OrderItem> items,
                                                    UUID playerUuid, Consumer<String> commandExecutor) {
        if (order == null) return 0;
        List<OrderItem> deliveryItems = (items != null && !items.isEmpty())
                ? items
                : OrderManager.getOrderItems(order.getId());

        ensureOutboxQueued(order, deliveryItems, playerUuid);
        int deliveredCount = 0;

        if (deliveryItems != null && !deliveryItems.isEmpty()) {
            int index = 0;
            for (OrderItem item : deliveryItems) {
                int itemIdx = index++;
                String deliveryTypeStr = item.getDeliveryType() != null
                        ? item.getDeliveryType().toLowerCase(Locale.ROOT)
                        : "autopix";
                String handlerStr = item.getDeliveryHandler() != null
                        ? item.getDeliveryHandler()
                        : deliveryTypeStr;
                String deliveryId = buildDeliveryId(order.getId(), itemIdx);

                String claimToken = claimDeliveryItem(order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr);
                if (claimToken == null) {
                    continue; // Already claimed or delivered by another execution
                }

                try {
                    beginDeliveryExecution(order.getId(), itemIdx, claimToken);

                    AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                            deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item));

                    boolean isSkriptOnly = "skript".equalsIgnoreCase(deliveryTypeStr);
                    boolean isHybrid = "hybrid".equalsIgnoreCase(deliveryTypeStr);
                    boolean isAutoPix = "autopix".equalsIgnoreCase(deliveryTypeStr) || (!isSkriptOnly && !isHybrid);

                    if ((isAutoPix || isHybrid) && commandExecutor != null) {
                        executeItemCommands(order.getPlayer(), item, commandExecutor);
                    }

                    completeDeliveryItem(order.getId(), itemIdx, claimToken, "delivered", null);
                    deliveredCount++;

                    AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                            deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item));
                } catch (Exception ex) {
                    markDeliveryItemForReview(order.getId(), itemIdx, claimToken, ex.getMessage());
                    AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                            deliveryId, order.getId(), itemIdx, playerUuid, handlerStr, ex.getMessage()));
                    AutoPix plugin = AutoPix.getInstance();
                    if (plugin != null) {
                        plugin.getLogger().severe("Erro na entrega do item #" + itemIdx + " do pedido #"
                                + order.getId() + ": " + ex.getMessage());
                    }
                }
            }
        } else {
            OrderProduct op = Bukkit.getServer() != null ? InventoryManager.getProductByOrder(order) : null;
            String claimToken = claimDeliveryItem(order.getId(), 0, playerUuid, "autopix", "autopix");
            if (claimToken != null) {
                String deliveryId = buildDeliveryId(order.getId(), 0);
                try {
                    beginDeliveryExecution(order.getId(), 0, claimToken);
                    AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                            deliveryId, order.getId(), 0, playerUuid, "autopix", "autopix", null));
                    if (op != null && op.getCommands() != null && commandExecutor != null) {
                        for (String cmd : op.getCommands()) {
                            String rendered = cmd.replace("{player}", order.getPlayer()).replace('&', '\u00a7');
                            commandExecutor.accept(rendered);
                        }
                    }
                    completeDeliveryItem(order.getId(), 0, claimToken, "delivered", null);
                    deliveredCount++;
                    AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                            deliveryId, order.getId(), 0, playerUuid, "autopix", "autopix", null));
                } catch (Exception ex) {
                    markDeliveryItemForReview(order.getId(), 0, claimToken, ex.getMessage());
                    AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                            deliveryId, order.getId(), 0, playerUuid, "autopix", ex.getMessage()));
                }
            }
        }

        return deliveredCount;
    }

    public static void processPendingDeliveries(Player player) {
        if (player == null || !player.isOnline()) return;
        AutoPix ap = AutoPix.getInstance();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        Runnable task = () -> {
            List<Integer> pendingOrders = findPendingOrderIdsForPlayer(playerUuid, playerName);
            for (Integer orderId : pendingOrders) {
                Order order = OrderManager.getOrderById(orderId);
                if (order == null || !order.isValidated()) continue;
                List<OrderItem> items = OrderManager.getOrderItems(orderId);
                PixData pd = OrderManager.getPixData(order);
                deliverApprovedOrder(ap, order, items, pd != null ? pd.getPaymentId() : null, order.getTransaction());
            }
        };

        if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(ap, task);
        } else {
            task.run();
        }
    }

    public static List<Integer> findPendingOrderIdsForPlayer(UUID playerUuid, String playerName) {
        List<Integer> pendingOrders = new ArrayList<>();
        String sql = "SELECT DISTINCT d.order_id "
                + "FROM autopix_deliveries d "
                + "LEFT JOIN autopix_orders o ON d.order_id = o.id "
                + "WHERE (d.player_uuid = ? OR LOWER(o.player) = ?) "
                + "AND LOWER(d.status) = 'pending' "
                + "ORDER BY d.order_id ASC;";

        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid != null ? playerUuid.toString() : "");
            statement.setString(2, playerName != null ? playerName.toLowerCase(Locale.ROOT) : "");
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    pendingOrders.add(rs.getInt("order_id"));
                }
            }
        } catch (SQLException ex) {
            AutoPix ap = AutoPix.getInstance();
            if (ap != null) {
                ap.getLogger().warning("Erro ao verificar entregas pendentes para " + playerName + ": " + ex.getMessage());
            }
        }
        return pendingOrders;
    }

    public static void recoverPendingDeliveries(AutoPix ap) {
        Runnable task = () -> {
            int movedToReview = recoverInterruptedDeliveriesNow();
            if (movedToReview > 0 && ap != null) {
                ap.getLogger().warning(movedToReview + " entrega(s) interrompidas durante execucao foram sinalizadas como needs_review.");
            }
        };

        if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(ap, task);
        } else {
            task.run();
        }
    }

    /**
     * Recovers deliveries left in 'processing' state:
     * 1. Deliveries interrupted in STAGE_CLAIMED (before any command or reward execution began)
     *    are safely reset to 'pending' so they can be delivered without loss.
     * 2. Deliveries interrupted in STAGE_EXECUTING_COMMANDS (or unknown stage where a reward may
     *    have already been executed) are transitioned to 'needs_review' and NEVER blindly re-run.
     *
     * @return number of deliveries marked as needs_review
     */
    public static int recoverInterruptedDeliveriesNow() {
        String recoverPreExecSql = "UPDATE autopix_deliveries "
                + "SET status = 'pending', claim_token = NULL, claimed_at = NULL, "
                + "execution_stage = 'RECOVERED_PRE_EXEC', updated_at = CURRENT_TIMESTAMP "
                + "WHERE LOWER(status) = 'processing' AND execution_stage = ?;";

        String selectUncertainSql = "SELECT delivery_id, order_id, item_index, player_uuid, handler "
                + "FROM autopix_deliveries "
                + "WHERE LOWER(status) = 'processing' AND (execution_stage IS NULL OR execution_stage <> ?);";

        String markNeedsReviewSql = "UPDATE autopix_deliveries "
                + "SET status = 'needs_review', execution_stage = ?, updated_at = CURRENT_TIMESTAMP, "
                + "last_error = 'Interrupcao apos inicio da execucao; verifique se a recompensa foi entregue antes de repetir.' "
                + "WHERE LOWER(status) = 'processing' AND (execution_stage IS NULL OR execution_stage <> ?);";

        int reviewCount = 0;
        List<DeliveryNeedsReviewEvent> eventsToFire = new ArrayList<>();

        try (Connection connection = OrderManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement preStmt = connection.prepareStatement(recoverPreExecSql)) {
                    preStmt.setString(1, STAGE_CLAIMED);
                    preStmt.executeUpdate();
                }

                try (PreparedStatement selStmt = connection.prepareStatement(selectUncertainSql)) {
                    selStmt.setString(1, STAGE_CLAIMED);
                    try (ResultSet rs = selStmt.executeQuery()) {
                        while (rs.next()) {
                            String delivId = rs.getString("delivery_id");
                            int orderId = rs.getInt("order_id");
                            int itemIdx = rs.getInt("item_index");
                            String uuidStr = rs.getString("player_uuid");
                            UUID pUuid = null;
                            if (uuidStr != null && !uuidStr.trim().isEmpty()) {
                                try {
                                    pUuid = UUID.fromString(uuidStr.trim());
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                            String handler = rs.getString("handler");
                            eventsToFire.add(new DeliveryNeedsReviewEvent(
                                    delivId != null ? delivId : buildDeliveryId(orderId, itemIdx),
                                    orderId, itemIdx, pUuid,
                                    handler != null ? handler : "autopix",
                                    "Interrupcao durante execucao de comandos de recompensa."));
                        }
                    }
                }

                try (PreparedStatement revStmt = connection.prepareStatement(markNeedsReviewSql)) {
                    revStmt.setString(1, STAGE_NEEDS_REVIEW);
                    revStmt.setString(2, STAGE_CLAIMED);
                    reviewCount = revStmt.executeUpdate();
                }

                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            AutoPix ap = AutoPix.getInstance();
            if (ap != null) {
                ap.getLogger().warning("Erro ao recuperar entregas interrompidas: " + ex.getMessage());
            }
        }

        for (DeliveryNeedsReviewEvent event : eventsToFire) {
            AutoPixEventDispatcher.dispatch(event);
        }

        return reviewCount;
    }

    public static void ensureOutboxQueued(Order order, List<OrderItem> items, UUID playerUuid) {
        if (order == null) return;
        try (Connection connection = OrderManager.getConnection()) {
            ensureOutboxQueued(connection, order.getId(), items, playerUuid);
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao enfileirar entregas no outbox: " + ex.getMessage());
            }
        }
    }

    public static void ensureOutboxQueued(Connection connection, int orderId,
                                          List<OrderItem> items, UUID playerUuid) throws SQLException {
        String checkSql = "SELECT delivery_id FROM autopix_deliveries WHERE order_id = ? AND item_index = ?;";
        String insertSql = "INSERT INTO autopix_deliveries "
                + "(delivery_id, order_id, item_index, player_uuid, status, delivery_type, handler, "
                + "idempotency_key, claim_token, claimed_at, execution_stage, payload, attempts, updated_at, last_error) "
                + "VALUES (?, ?, ?, ?, 'pending', ?, ?, ?, NULL, NULL, NULL, ?, 0, CURRENT_TIMESTAMP, NULL);";

        if (items != null && !items.isEmpty()) {
            int idx = 0;
            for (OrderItem item : items) {
                int itemIdx = idx++;
                if (deliveryRowExists(connection, checkSql, orderId, itemIdx)) {
                    continue;
                }
                String delivId = buildDeliveryId(orderId, itemIdx);
                String dt = item.getDeliveryType() != null ? item.getDeliveryType().toLowerCase(Locale.ROOT) : "autopix";
                String handler = item.getDeliveryHandler() != null ? item.getDeliveryHandler() : dt;
                String payload = item.getDeliverySnapshot();

                try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
                    stmt.setString(1, delivId);
                    stmt.setInt(2, orderId);
                    stmt.setInt(3, itemIdx);
                    stmt.setString(4, playerUuid != null ? playerUuid.toString() : null);
                    stmt.setString(5, dt);
                    stmt.setString(6, handler);
                    stmt.setString(7, delivId);
                    stmt.setString(8, payload);
                    stmt.executeUpdate();
                }
            }
        } else {
            if (!deliveryRowExists(connection, checkSql, orderId, 0)) {
                String delivId = buildDeliveryId(orderId, 0);
                try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
                    stmt.setString(1, delivId);
                    stmt.setInt(2, orderId);
                    stmt.setInt(3, 0);
                    stmt.setString(4, playerUuid != null ? playerUuid.toString() : null);
                    stmt.setString(5, "autopix");
                    stmt.setString(6, "autopix");
                    stmt.setString(7, delivId);
                    stmt.setString(8, null);
                    stmt.executeUpdate();
                }
            }
        }
    }

    private static boolean deliveryRowExists(Connection connection, String checkSql, int orderId, int itemIndex) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement(checkSql)) {
            check.setInt(1, orderId);
            check.setInt(2, itemIndex);
            try (ResultSet rs = check.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Atomically claims a single item delivery only if its current status is 'pending'.
     * Never claims an item that is already 'processing', 'delivered', or 'needs_review'.
     *
     * @return the unique claimToken if this caller won the claim, or null if another caller already claimed it
     */
    public static String claimDeliveryItem(int orderId, int itemIndex, UUID playerUuid,
                                           String deliveryType, String handler) {
        String deliveryId = buildDeliveryId(orderId, itemIndex);
        String claimToken = "claim_" + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        String normType = deliveryType != null ? deliveryType.toLowerCase(Locale.ROOT) : "autopix";
        String normHandler = handler != null ? handler : normType;

        String updateSql = "UPDATE autopix_deliveries "
                + "SET status = 'processing', "
                + "claim_token = ?, "
                + "claimed_at = ?, "
                + "execution_stage = ?, "
                + "attempts = attempts + 1, "
                + "player_uuid = COALESCE(?, player_uuid), "
                + "delivery_type = ?, "
                + "handler = ?, "
                + "updated_at = ? "
                + "WHERE order_id = ? AND item_index = ? AND LOWER(status) = 'pending';";

        try (Connection connection = OrderManager.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setString(1, claimToken);
                statement.setTimestamp(2, now);
                statement.setString(3, STAGE_CLAIMED);
                statement.setString(4, playerUuid != null ? playerUuid.toString() : null);
                statement.setString(5, normType);
                statement.setString(6, normHandler);
                statement.setTimestamp(7, now);
                statement.setInt(8, orderId);
                statement.setInt(9, itemIndex);

                int updated = statement.executeUpdate();
                if (updated == 1) {
                    return claimToken;
                }
            }

            // Check if row exists at all; if not, insert directly as 'processing' with STAGE_CLAIMED
            String checkSql = "SELECT status FROM autopix_deliveries WHERE order_id = ? AND item_index = ?;";
            try (PreparedStatement check = connection.prepareStatement(checkSql)) {
                check.setInt(1, orderId);
                check.setInt(2, itemIndex);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return null; // Row exists and is not 'pending' (already processing/delivered/needs_review)
                    }
                }
            }

            String insertSql = "INSERT INTO autopix_deliveries "
                    + "(delivery_id, order_id, item_index, player_uuid, status, delivery_type, handler, "
                    + "idempotency_key, claim_token, claimed_at, execution_stage, payload, attempts, updated_at, last_error) "
                    + "VALUES (?, ?, ?, ?, 'processing', ?, ?, ?, ?, ?, ?, NULL, 1, ?, NULL);";
            try (PreparedStatement ins = connection.prepareStatement(insertSql)) {
                ins.setString(1, deliveryId);
                ins.setInt(2, orderId);
                ins.setInt(3, itemIndex);
                ins.setString(4, playerUuid != null ? playerUuid.toString() : null);
                ins.setString(5, normType);
                ins.setString(6, normHandler);
                ins.setString(7, deliveryId);
                ins.setString(8, claimToken);
                ins.setTimestamp(9, now);
                ins.setString(10, STAGE_CLAIMED);
                ins.setTimestamp(11, now);
                return ins.executeUpdate() == 1 ? claimToken : null;
            } catch (SQLException raceEx) {
                return null; // Another thread inserted concurrently
            }
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao reivindicar entrega do item #" + itemIndex
                        + " do pedido #" + orderId + ": " + ex.getMessage());
            }
            return null;
        }
    }

    /**
     * Marks a claimed delivery as having entered the command/reward execution phase.
     * Once in this stage, an unexpected crash will transition the delivery to NEEDS_REVIEW
     * rather than blindly re-executing commands.
     */
    public static boolean beginDeliveryExecution(int orderId, int itemIndex, String claimToken) {
        if (claimToken == null) return false;
        String sql = "UPDATE autopix_deliveries SET execution_stage = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing';";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STAGE_EXECUTING_COMMANDS);
            statement.setInt(2, orderId);
            statement.setInt(3, itemIndex);
            statement.setString(4, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao atualizar estagio de execucao da entrega: " + ex.getMessage());
            }
            return false;
        }
    }

    public static boolean completeDeliveryItem(int orderId, int itemIndex, String claimToken, String status, String error) {
        String sql = "UPDATE autopix_deliveries "
                + "SET status = ?, execution_stage = ?, updated_at = CURRENT_TIMESTAMP, last_error = ? "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, STAGE_COMPLETED);
            statement.setString(3, error);
            statement.setInt(4, orderId);
            statement.setInt(5, itemIndex);
            statement.setString(6, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao completar entrega: " + ex.getMessage());
            }
            return false;
        }
    }

    public static void markDeliveryItemForReview(int orderId, int itemIndex, String claimToken, String reason) {
        String sql = claimToken != null
                ? "UPDATE autopix_deliveries SET status = 'needs_review', execution_stage = ?, updated_at = CURRENT_TIMESTAMP, last_error = ? "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ?;"
                : "UPDATE autopix_deliveries SET status = 'needs_review', execution_stage = ?, updated_at = CURRENT_TIMESTAMP, last_error = ? "
                + "WHERE order_id = ? AND item_index = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STAGE_NEEDS_REVIEW);
            statement.setString(2, reason);
            statement.setInt(3, orderId);
            statement.setInt(4, itemIndex);
            if (claimToken != null) {
                statement.setString(5, claimToken);
            }
            statement.executeUpdate();
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao marcar entrega para revisao: " + ex.getMessage());
            }
        }
    }

    private static void executeItemCommands(String playerName, OrderItem item, Consumer<String> commandExecutor) {
        if (item.getCommands() == null || commandExecutor == null) return;
        for (String command : item.getCommands()) {
            String rendered = command
                    .replace("{player}", playerName)
                    .replace("{produto}", item.getProduct())
                    .replace("{quantidade}", Integer.toString(item.getQuantity()))
                    .replace("{preco}", String.format(Locale.ROOT, "%.2f", item.getLineTotal()).replace('.', ','))
                    .replace("{duracao}", item.getDuration() != null ? item.getDuration() : "")
                    .replace('&', '\u00a7');
            commandExecutor.accept(rendered);
        }
    }

    private static void showSuccessFeedback(AutoPix ap, Player p, Order order) {
        if (p == null || !p.isOnline()) return;
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
        } catch (Exception ex) {
            ap.getLogger().warning("Nao foi possivel atualizar mapa de sucesso para " + p.getName() + ": " + ex.getMessage());
        }

        String formattedPrice = String.format(Locale.ROOT, "%.2f", order.getPrice()).replace('.', ',');
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
}
