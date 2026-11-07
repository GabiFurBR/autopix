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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
import io.github.warleysr.autopix.events.DeliveryFailedEvent;
import io.github.warleysr.autopix.events.DeliveryNeedsReviewEvent;
import io.github.warleysr.autopix.events.DeliveryStartedEvent;
import io.github.warleysr.autopix.events.PaymentApprovedEvent;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.qrcode.ImageCreator;

/**
 * Atomic, idempotent multi-item delivery manager supporting AUTOPIX, SKRIPT, and HYBRID
 * delivery strategies, with persistent outbox queueing for offline players, claim tokens,
 * explicit Skript confirmation, JDBC thread safety, execution stage tracking, and safe crash recovery.
 */
public final class DeliveryManager {

    public static final String STAGE_CLAIMED = "CLAIMED";
    public static final String STAGE_EXECUTING_COMMANDS = "EXECUTING_COMMANDS";
    public static final String STAGE_AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";
    public static final String STAGE_COMPLETED = "COMPLETED";
    public static final String STAGE_FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String STAGE_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final int MAX_DELIVERY_ATTEMPTS = 5;

    private DeliveryManager() {
    }

    public static String buildDeliveryId(int orderId, int itemIndex) {
        return "deliv_" + orderId + "_" + itemIndex;
    }

    private static Executor defaultAsyncExecutor(AutoPix ap) {
        return task -> {
            if (ap != null && ap.isEnabled() && Bukkit.getServer() != null) {
                Bukkit.getScheduler().runTaskAsynchronously(ap, task);
            } else {
                task.run();
            }
        };
    }

    private static Executor defaultMainThreadExecutor(AutoPix ap) {
        return task -> {
            if (ap != null && ap.isEnabled() && Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTask(ap, task);
            } else {
                task.run();
            }
        };
    }

    public static void deliverApprovedOrder(AutoPix ap, Order order, List<OrderItem> items,
                                            String paymentId, String transactionId) {
        if (order == null) return;
        Executor asyncExec = defaultAsyncExecutor(ap);
        Executor mainExec = defaultMainThreadExecutor(ap);
        Consumer<String> bukkitCommandRunner = rendered -> {
            if (Bukkit.getServer() != null) {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
                if (!dispatched) {
                    throw new IllegalStateException("Comando de recompensa rejeitado pelo console: " + rendered);
                }
            }
        };
        deliverApprovedOrder(ap, order, items, paymentId, transactionId, asyncExec, mainExec, bukkitCommandRunner);
    }

    public static CompletableFuture<Integer> deliverApprovedOrder(
            AutoPix ap, Order order, List<OrderItem> items, String paymentId, String transactionId,
            Executor asyncExec, Executor mainExec, Consumer<String> commandExecutor) {
        if (order == null) {
            return CompletableFuture.completedFuture(0);
        }
        CompletableFuture<Integer> resultFuture = new CompletableFuture<>();

        asyncExec.execute(() -> {
            try {
                List<OrderItem> deliveryItems = items != null && !items.isEmpty()
                        ? items
                        : OrderManager.getOrderItems(order.getId());

                UUID playerUuid = order.getPlayerUuid();
                if (playerUuid == null) {
                    playerUuid = OrderManager.getOnlinePlayerUuid(order.getPlayer());
                }

                if (!ensureOutboxQueued(order, deliveryItems, playerUuid)) {
                    if (ap != null) {
                        ap.getLogger().severe("Falha ao persistir outbox de entrega para o pedido #" + order.getId()
                                + ". A execucao de recompensas foi abortada para preservar a consistencia.");
                    }
                    resultFuture.complete(0);
                    return;
                }

                if (ap != null) {
                    OrderManager.refreshStatsCacheAsync(ap);
                    DiscordNotifier.notifyPaymentApproved(ap, order);
                }

                final UUID resolvedUuid = playerUuid;
                final List<OrderItem> finalDeliveryItems = deliveryItems;

                mainExec.execute(() -> {
                    Player onlinePlayer = null;
                    if (Bukkit.getServer() != null) {
                        onlinePlayer = Bukkit.getPlayerExact(order.getPlayer());
                        if (onlinePlayer == null && resolvedUuid != null) {
                            onlinePlayer = Bukkit.getPlayer(resolvedUuid);
                        }
                    }

                    boolean isOnline = (onlinePlayer != null && onlinePlayer.isOnline())
                            || OrderManager.isPlayerOnline(order.getPlayer());

                    final Player finalPlayer = onlinePlayer;
                    final UUID finalUuid = finalPlayer != null ? finalPlayer.getUniqueId() : resolvedUuid;

                    AutoPixEventDispatcher.dispatch(new PaymentApprovedEvent(
                            order.getId(), order.getPlayer(), finalUuid,
                            order.getProduct(), order.getPrice(), paymentId, transactionId,
                            finalDeliveryItems));

                    if (!isOnline) {
                        if (ap != null) {
                            ap.getLogger().info("Jogador " + order.getPlayer() + " offline. Entrega do pedido #"
                                    + order.getId() + " registrada no outbox para entrega ao conectar.");
                        }
                        resultFuture.complete(0);
                        return;
                    }

                    if (finalPlayer != null && finalPlayer.isOnline() && ap != null) {
                        showSuccessFeedback(ap, finalPlayer, order);
                    }

                    processOrderDeliveryItemsAsync(order, finalDeliveryItems, finalUuid, asyncExec, mainExec, commandExecutor)
                            .whenComplete((count, ex) -> {
                                if (ex != null) {
                                    resultFuture.completeExceptionally(ex);
                                } else {
                                    resultFuture.complete(count != null ? count : 0);
                                }
                            });
                });
            } catch (Exception ex) {
                resultFuture.completeExceptionally(ex);
            }
        });

        return resultFuture;
    }

    /**
     * Asynchronously claims and persists each delivery stage on {@code asyncExec} while executing
     * Bukkit commands and events exclusively on {@code mainExec}. Never blocks the main thread on JDBC.
     */
    public static CompletableFuture<Integer> processOrderDeliveryItemsAsync(
            Order order, List<OrderItem> items, UUID playerUuid,
            Executor asyncExec, Executor mainExec, Consumer<String> commandExecutor) {
        if (order == null) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<Integer> completion = new CompletableFuture<>();
        asyncExec.execute(() -> {
            try {
                List<OrderItem> deliveryItems = (items != null && !items.isEmpty())
                        ? items
                        : OrderManager.getOrderItems(order.getId());

                if (!ensureOutboxQueued(order, deliveryItems, playerUuid)) {
                    completion.complete(0);
                    return;
                }

                AtomicInteger deliveredCount = new AtomicInteger(0);
                if (deliveryItems != null && !deliveryItems.isEmpty()) {
                    processItemAtIndexAsync(order, deliveryItems, 0, playerUuid, asyncExec, mainExec,
                            commandExecutor, deliveredCount, completion);
                } else {
                    processLegacySingleItemAsync(order, playerUuid, asyncExec, mainExec,
                            commandExecutor, deliveredCount, completion);
                }
            } catch (Exception ex) {
                completion.completeExceptionally(ex);
            }
        });

        return completion;
    }

    private static void processItemAtIndexAsync(
            Order order, List<OrderItem> items, int index, UUID playerUuid,
            Executor asyncExec, Executor mainExec, Consumer<String> commandExecutor,
            AtomicInteger deliveredCount, CompletableFuture<Integer> completion) {
        if (index >= items.size()) {
            completion.complete(deliveredCount.get());
            return;
        }

        OrderItem item = items.get(index);
        int itemIdx = index;
        String deliveryTypeStr = item.getDeliveryType() != null
                ? item.getDeliveryType().toLowerCase(Locale.ROOT)
                : "autopix";
        String handlerStr = item.getDeliveryHandler() != null
                ? item.getDeliveryHandler()
                : deliveryTypeStr;
        String deliveryId = buildDeliveryId(order.getId(), itemIdx);

        boolean isSkriptOnly = "skript".equalsIgnoreCase(deliveryTypeStr);
        boolean isHybrid = "hybrid".equalsIgnoreCase(deliveryTypeStr);
        boolean isAutoPix = "autopix".equalsIgnoreCase(deliveryTypeStr) || (!isSkriptOnly && !isHybrid);

        // Phase 1 (Async JDBC): Claim item and persist initial execution stage BEFORE running any rewards
        String claimToken = claimDeliveryItem(order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr);
        if (claimToken == null) {
            processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                    commandExecutor, deliveredCount, completion);
            return;
        }

        boolean stageReady;
        if (isSkriptOnly) {
            stageReady = markDeliveryAwaitingConfirmation(order.getId(), itemIdx, claimToken);
        } else {
            stageReady = beginDeliveryExecution(order.getId(), itemIdx, claimToken);
        }

        if (!stageReady) {
            releaseUnstartedClaim(order.getId(), itemIdx, claimToken);
            processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                    commandExecutor, deliveredCount, completion);
            return;
        }

        // Phase 2 (Main Thread): Execute Bukkit commands and/or dispatch DeliveryStartedEvent
        mainExec.execute(() -> {
            if (isAutoPix) {
                try {
                    AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                            deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item, claimToken));
                    if (commandExecutor != null) {
                        executeItemCommands(order.getPlayer(), item, commandExecutor);
                    }
                } catch (Exception ex) {
                    asyncExec.execute(() -> {
                        markDeliveryItemForReview(order.getId(), itemIdx, claimToken, ex.getMessage());
                        mainExec.execute(() -> AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, handlerStr, ex.getMessage())));
                        processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                                commandExecutor, deliveredCount, completion);
                    });
                    return;
                }

                // Phase 3 (Async JDBC): Persist completion BEFORE firing DeliveryCompletedEvent
                asyncExec.execute(() -> {
                    boolean persisted = completeDeliveryItem(order.getId(), itemIdx, claimToken, "delivered", null);
                    if (persisted) {
                        deliveredCount.incrementAndGet();
                        mainExec.execute(() -> {
                            AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                                    deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item));
                            asyncExec.execute(() -> processItemAtIndexAsync(order, items, index + 1, playerUuid,
                                    asyncExec, mainExec, commandExecutor, deliveredCount, completion));
                        });
                    } else {
                        markDeliveryItemForReview(order.getId(), itemIdx, claimToken,
                                "Comandos executados, mas falhou ao persistir conclusao da entrega.");
                        mainExec.execute(() -> {
                            AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                    deliveryId, order.getId(), itemIdx, playerUuid, handlerStr,
                                    "Comandos executados, mas falhou ao persistir conclusao da entrega."));
                            asyncExec.execute(() -> processItemAtIndexAsync(order, items, index + 1, playerUuid,
                                    asyncExec, mainExec, commandExecutor, deliveredCount, completion));
                        });
                    }
                });
            } else if (isHybrid) {
                try {
                    if (commandExecutor != null) {
                        executeItemCommands(order.getPlayer(), item, commandExecutor);
                    }
                } catch (Exception ex) {
                    asyncExec.execute(() -> {
                        markDeliveryItemForReview(order.getId(), itemIdx, claimToken, ex.getMessage());
                        mainExec.execute(() -> AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, handlerStr, ex.getMessage())));
                        processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                                commandExecutor, deliveredCount, completion);
                    });
                    return;
                }

                asyncExec.execute(() -> {
                    if (!markDeliveryAwaitingConfirmation(order.getId(), itemIdx, claimToken)) {
                        markDeliveryItemForReview(order.getId(), itemIdx, claimToken,
                                "Comandos hibridos executados, mas falhou ao registrar espera de confirmacao Skript.");
                        mainExec.execute(() -> AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, handlerStr,
                                "Comandos hibridos executados, mas falhou ao registrar espera de confirmacao Skript.")));
                        processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                                commandExecutor, deliveredCount, completion);
                        return;
                    }

                    mainExec.execute(() -> {
                        AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item, claimToken));
                        scheduleSkriptConfirmationTimeoutIfNeeded(order.getId(), itemIdx, claimToken, deliveryTypeStr);
                        asyncExec.execute(() -> {
                            if (isDeliveryItemDelivered(order.getId(), itemIdx)) {
                                deliveredCount.incrementAndGet();
                            }
                            processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                                    commandExecutor, deliveredCount, completion);
                        });
                    });
                });
            } else {
                // SKRIPT only: already in STAGE_AWAITING_CONFIRMATION before dispatching DeliveryStartedEvent
                AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                        deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item, claimToken));
                scheduleSkriptConfirmationTimeoutIfNeeded(order.getId(), itemIdx, claimToken, deliveryTypeStr);
                asyncExec.execute(() -> {
                    if (isDeliveryItemDelivered(order.getId(), itemIdx)) {
                        deliveredCount.incrementAndGet();
                    }
                    processItemAtIndexAsync(order, items, index + 1, playerUuid, asyncExec, mainExec,
                            commandExecutor, deliveredCount, completion);
                });
            }
        });
    }

    private static void processLegacySingleItemAsync(
            Order order, UUID playerUuid, Executor asyncExec, Executor mainExec,
            Consumer<String> commandExecutor, AtomicInteger deliveredCount, CompletableFuture<Integer> completion) {
        String claimToken = claimDeliveryItem(order.getId(), 0, playerUuid, "autopix", "autopix");
        if (claimToken == null) {
            completion.complete(0);
            return;
        }
        if (!beginDeliveryExecution(order.getId(), 0, claimToken)) {
            releaseUnstartedClaim(order.getId(), 0, claimToken);
            completion.complete(0);
            return;
        }
        String deliveryId = buildDeliveryId(order.getId(), 0);
        mainExec.execute(() -> {
            OrderProduct op = Bukkit.getServer() != null ? InventoryManager.getProductByOrder(order) : null;
            try {
                AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                        deliveryId, order.getId(), 0, playerUuid, "autopix", "autopix", null, claimToken));
                if (op != null && op.getCommands() != null && commandExecutor != null) {
                    for (String cmd : op.getCommands()) {
                        String rendered = cmd.replace("{player}", order.getPlayer()).replace('&', '\u00a7');
                        commandExecutor.accept(rendered);
                    }
                }
            } catch (Exception ex) {
                asyncExec.execute(() -> {
                    markDeliveryItemForReview(order.getId(), 0, claimToken, ex.getMessage());
                    mainExec.execute(() -> AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                            deliveryId, order.getId(), 0, playerUuid, "autopix", ex.getMessage())));
                    completion.complete(0);
                });
                return;
            }

            asyncExec.execute(() -> {
                if (completeDeliveryItem(order.getId(), 0, claimToken, "delivered", null)) {
                    deliveredCount.incrementAndGet();
                    mainExec.execute(() -> {
                        AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                                deliveryId, order.getId(), 0, playerUuid, "autopix", "autopix", null));
                        completion.complete(deliveredCount.get());
                    });
                } else {
                    markDeliveryItemForReview(order.getId(), 0, claimToken,
                            "Falha ao persistir conclusao da entrega legada.");
                    mainExec.execute(() -> {
                        AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                deliveryId, order.getId(), 0, playerUuid, "autopix",
                                "Falha ao persistir conclusao da entrega legada."));
                        completion.complete(0);
                    });
                }
            });
        });
    }

    private static void scheduleSkriptConfirmationTimeoutIfNeeded(int orderId, int itemIndex,
                                                                  String claimToken, String deliveryType) {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null && plugin.isEnabled() && Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (isAwaitingConfirmationWithToken(orderId, itemIndex, claimToken)) {
                    boolean retryable = "skript".equalsIgnoreCase(deliveryType);
                    failCustomDelivery(orderId, itemIndex, claimToken,
                            "Timeout aguardando confirmacao de entrega do Skript (possivel reload ou falha de execucao).",
                            retryable);
                }
            }, 200L);
        }
    }

    /**
     * Executes delivery for all pending items of an order synchronously on an asynchronous worker thread,
     * using atomic per-item database claims and tracking execution stages.
     * Note: Must NOT be called on the Bukkit main thread (enforced by OrderManager.getConnection()).
     * Custom Skript/Hybrid deliveries are left in STAGE_AWAITING_CONFIRMATION until explicitly confirmed.
     */
    public static int processOrderDeliveryItemsSync(Order order, List<OrderItem> items,
                                                    UUID playerUuid, Consumer<String> commandExecutor) {
        if (order == null) return 0;
        List<OrderItem> deliveryItems = (items != null && !items.isEmpty())
                ? items
                : OrderManager.getOrderItems(order.getId());

        if (!ensureOutboxQueued(order, deliveryItems, playerUuid)) {
            return 0;
        }
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

                boolean isSkriptOnly = "skript".equalsIgnoreCase(deliveryTypeStr);
                boolean isHybrid = "hybrid".equalsIgnoreCase(deliveryTypeStr);
                boolean isAutoPix = "autopix".equalsIgnoreCase(deliveryTypeStr) || (!isSkriptOnly && !isHybrid);

                String claimToken = claimDeliveryItem(order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr);
                if (claimToken == null) {
                    continue;
                }

                try {
                    if (isSkriptOnly) {
                        if (!markDeliveryAwaitingConfirmation(order.getId(), itemIdx, claimToken)) {
                            releaseUnstartedClaim(order.getId(), itemIdx, claimToken);
                            continue;
                        }
                        AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item, claimToken));
                        if (isDeliveryItemDelivered(order.getId(), itemIdx)) {
                            deliveredCount++;
                        }
                    } else if (isHybrid) {
                        if (!beginDeliveryExecution(order.getId(), itemIdx, claimToken)) {
                            releaseUnstartedClaim(order.getId(), itemIdx, claimToken);
                            continue;
                        }
                        if (commandExecutor != null) {
                            executeItemCommands(order.getPlayer(), item, commandExecutor);
                        }
                        if (!markDeliveryAwaitingConfirmation(order.getId(), itemIdx, claimToken)) {
                            markDeliveryItemForReview(order.getId(), itemIdx, claimToken,
                                    "Comandos hibridos executados, mas falhou ao registrar espera de confirmacao Skript.");
                            AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                    deliveryId, order.getId(), itemIdx, playerUuid, handlerStr,
                                    "Comandos hibridos executados, mas falhou ao registrar espera de confirmacao Skript."));
                            continue;
                        }
                        AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item, claimToken));
                        if (isDeliveryItemDelivered(order.getId(), itemIdx)) {
                            deliveredCount++;
                        }
                    } else if (isAutoPix) {
                        if (!beginDeliveryExecution(order.getId(), itemIdx, claimToken)) {
                            releaseUnstartedClaim(order.getId(), itemIdx, claimToken);
                            continue;
                        }
                        AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                                deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item, claimToken));
                        if (commandExecutor != null) {
                            executeItemCommands(order.getPlayer(), item, commandExecutor);
                        }
                        if (completeDeliveryItem(order.getId(), itemIdx, claimToken, "delivered", null)) {
                            deliveredCount++;
                            AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                                    deliveryId, order.getId(), itemIdx, playerUuid, deliveryTypeStr, handlerStr, item));
                        } else {
                            markDeliveryItemForReview(order.getId(), itemIdx, claimToken,
                                    "Comandos executados, mas falhou ao persistir conclusao da entrega.");
                            AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                    deliveryId, order.getId(), itemIdx, playerUuid, handlerStr,
                                    "Comandos executados, mas falhou ao persistir conclusao da entrega."));
                        }
                    }
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
                if (!beginDeliveryExecution(order.getId(), 0, claimToken)) {
                    releaseUnstartedClaim(order.getId(), 0, claimToken);
                    return 0;
                }
                String deliveryId = buildDeliveryId(order.getId(), 0);
                try {
                    AutoPixEventDispatcher.dispatch(new DeliveryStartedEvent(
                            deliveryId, order.getId(), 0, playerUuid, "autopix", "autopix", null, claimToken));
                    if (op != null && op.getCommands() != null && commandExecutor != null) {
                        for (String cmd : op.getCommands()) {
                            String rendered = cmd.replace("{player}", order.getPlayer()).replace('&', '\u00a7');
                            commandExecutor.accept(rendered);
                        }
                    }
                    if (completeDeliveryItem(order.getId(), 0, claimToken, "delivered", null)) {
                        deliveredCount++;
                        AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                                deliveryId, order.getId(), 0, playerUuid, "autopix", "autopix", null));
                    } else {
                        markDeliveryItemForReview(order.getId(), 0, claimToken,
                                "Comandos executados, mas falhou ao persistir conclusao da entrega.");
                        AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                                deliveryId, order.getId(), 0, playerUuid, "autopix",
                                "Comandos executados, mas falhou ao persistir conclusao da entrega."));
                    }
                } catch (Exception ex) {
                    markDeliveryItemForReview(order.getId(), 0, claimToken, ex.getMessage());
                    AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                            deliveryId, order.getId(), 0, playerUuid, "autopix", ex.getMessage()));
                }
            }
        }

        return deliveredCount;
    }

    /**
     * Explicitly confirms successful completion of a custom Skript or Hybrid delivery item.
     * Validates orderId, itemIndex, and the active claimToken. Prevents duplicate or stale confirmations.
     * Dispatches DeliveryCompletedEvent ONLY after the persistent database update succeeds.
     */
    public static boolean confirmCustomDelivery(int orderId, int itemIndex, String claimToken) {
        if (orderId <= 0 || itemIndex < 0 || claimToken == null || claimToken.trim().isEmpty()) {
            return false;
        }
        String trimmedToken = claimToken.trim();
        String updateSql = "UPDATE autopix_deliveries "
                + "SET status = 'delivered', execution_stage = ?, updated_at = CURRENT_TIMESTAMP, last_error = NULL "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ? "
                + "AND LOWER(status) = 'processing' "
                + "AND execution_stage IN (?, ?);";
        String selectSql = "SELECT delivery_id, player_uuid, delivery_type, handler "
                + "FROM autopix_deliveries WHERE order_id = ? AND item_index = ?;";

        String deliveryId = buildDeliveryId(orderId, itemIndex);
        UUID playerUuid = null;
        String deliveryType = "skript";
        String handler = "skript";

        try (Connection connection = OrderManager.getConnection()) {
            try (PreparedStatement upd = connection.prepareStatement(updateSql)) {
                upd.setString(1, STAGE_COMPLETED);
                upd.setInt(2, orderId);
                upd.setInt(3, itemIndex);
                upd.setString(4, trimmedToken);
                upd.setString(5, STAGE_AWAITING_CONFIRMATION);
                upd.setString(6, STAGE_EXECUTING_COMMANDS);
                if (upd.executeUpdate() != 1) {
                    return false;
                }
            }

            updateOrderStatusIfAllItemsDelivered(connection, orderId);

            try (PreparedStatement sel = connection.prepareStatement(selectSql)) {
                sel.setInt(1, orderId);
                sel.setInt(2, itemIndex);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getString("delivery_id") != null) {
                            deliveryId = rs.getString("delivery_id");
                        }
                        String uuidStr = rs.getString("player_uuid");
                        if (uuidStr != null && !uuidStr.trim().isEmpty()) {
                            try {
                                playerUuid = UUID.fromString(uuidStr.trim());
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        if (rs.getString("delivery_type") != null) {
                            deliveryType = rs.getString("delivery_type");
                        }
                        if (rs.getString("handler") != null) {
                            handler = rs.getString("handler");
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao confirmar entrega customizada do pedido #"
                        + orderId + " item #" + itemIndex + ": " + ex.getMessage());
            }
            return false;
        }

        OrderItem orderItem = null;
        List<OrderItem> orderItems = OrderManager.getOrderItems(orderId);
        if (itemIndex >= 0 && itemIndex < orderItems.size()) {
            orderItem = orderItems.get(itemIndex);
        }

        final String finalDelivId = deliveryId;
        final UUID finalUuid = playerUuid;
        final String finalType = deliveryType;
        final String finalHandler = handler;
        final OrderItem finalItem = orderItem;

        AutoPix plugin = AutoPix.getInstance();
        defaultMainThreadExecutor(plugin).execute(() ->
                AutoPixEventDispatcher.dispatch(new DeliveryCompletedEvent(
                        finalDelivId, orderId, itemIndex, finalUuid, finalType, finalHandler, finalItem)));
        return true;
    }

    /**
     * Reports a failure in custom Skript delivery execution.
     * For pure 'skript' items where no console commands ran yet, resets the item to 'pending'
     * (when retryable and attempts < MAX_DELIVERY_ATTEMPTS) so the delivery remains recoverable.
     * For 'hybrid' items (where console commands already executed) or non-retryable failures,
     * transitions the item to 'needs_review' to prevent duplicate console command execution.
     */
    public static boolean failCustomDelivery(int orderId, int itemIndex, String claimToken, String reason) {
        return failCustomDelivery(orderId, itemIndex, claimToken, reason, true);
    }

    public static boolean failCustomDelivery(int orderId, int itemIndex, String claimToken,
                                             String reason, boolean retryable) {
        if (orderId <= 0 || itemIndex < 0 || claimToken == null || claimToken.trim().isEmpty()) {
            return false;
        }
        String trimmedToken = claimToken.trim();
        String effectiveReason = (reason != null && !reason.trim().isEmpty())
                ? reason.trim()
                : "Falha reportada pelo handler Skript.";

        String selectSql = "SELECT delivery_id, player_uuid, delivery_type, handler, attempts "
                + "FROM autopix_deliveries "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing';";

        String deliveryId = buildDeliveryId(orderId, itemIndex);
        UUID playerUuid = null;
        String deliveryType = "skript";
        String handler = "skript";
        int attempts = 1;
        boolean canRecoverAsPending;

        try (Connection connection = OrderManager.getConnection()) {
            try (PreparedStatement sel = connection.prepareStatement(selectSql)) {
                sel.setInt(1, orderId);
                sel.setInt(2, itemIndex);
                sel.setString(3, trimmedToken);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    if (rs.getString("delivery_id") != null) {
                        deliveryId = rs.getString("delivery_id");
                    }
                    String uuidStr = rs.getString("player_uuid");
                    if (uuidStr != null && !uuidStr.trim().isEmpty()) {
                        try {
                            playerUuid = UUID.fromString(uuidStr.trim());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (rs.getString("delivery_type") != null) {
                        deliveryType = rs.getString("delivery_type");
                    }
                    if (rs.getString("handler") != null) {
                        handler = rs.getString("handler");
                    }
                    attempts = rs.getInt("attempts");
                }
            }

            canRecoverAsPending = retryable
                    && "skript".equalsIgnoreCase(deliveryType)
                    && attempts < MAX_DELIVERY_ATTEMPTS;

            String updateSql = canRecoverAsPending
                    ? "UPDATE autopix_deliveries SET status = 'pending', claim_token = NULL, claimed_at = NULL, "
                    + "execution_stage = ?, updated_at = CURRENT_TIMESTAMP, last_error = ? "
                    + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing';"
                    : "UPDATE autopix_deliveries SET status = 'needs_review', execution_stage = ?, "
                    + "updated_at = CURRENT_TIMESTAMP, last_error = ? "
                    + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing';";

            try (PreparedStatement upd = connection.prepareStatement(updateSql)) {
                upd.setString(1, canRecoverAsPending ? STAGE_FAILED_RETRYABLE : STAGE_NEEDS_REVIEW);
                upd.setString(2, effectiveReason);
                upd.setInt(3, orderId);
                upd.setInt(4, itemIndex);
                upd.setString(5, trimmedToken);
                if (upd.executeUpdate() != 1) {
                    return false;
                }
            }
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao registrar falha de entrega customizada do pedido #"
                        + orderId + " item #" + itemIndex + ": " + ex.getMessage());
            }
            return false;
        }

        final String finalDelivId = deliveryId;
        final UUID finalUuid = playerUuid;
        final String finalHandler = handler;
        final boolean fireReview = !canRecoverAsPending;

        AutoPix plugin = AutoPix.getInstance();
        defaultMainThreadExecutor(plugin).execute(() -> {
            if (fireReview) {
                AutoPixEventDispatcher.dispatch(new DeliveryNeedsReviewEvent(
                        finalDelivId, orderId, itemIndex, finalUuid, finalHandler, effectiveReason));
            } else {
                AutoPixEventDispatcher.dispatch(new DeliveryFailedEvent(
                        finalDelivId, orderId, itemIndex, finalUuid, finalHandler, effectiveReason));
            }
        });
        return true;
    }

    public static boolean isDeliveryItemDelivered(int orderId, int itemIndex) {
        String sql = "SELECT status FROM autopix_deliveries WHERE order_id = ? AND item_index = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, orderId);
            stmt.setInt(2, itemIndex);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    return "delivered".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status);
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    private static boolean isAwaitingConfirmationWithToken(int orderId, int itemIndex, String claimToken) {
        String sql = "SELECT status, execution_stage FROM autopix_deliveries "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, orderId);
            stmt.setInt(2, itemIndex);
            stmt.setString(3, claimToken);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return "processing".equalsIgnoreCase(rs.getString("status"))
                            && STAGE_AWAITING_CONFIRMATION.equalsIgnoreCase(rs.getString("execution_stage"));
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
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
     * 2. Pure 'skript' deliveries interrupted in STAGE_AWAITING_CONFIRMATION (where no console commands ran
     *    and Skript never confirmed completion before restart/reload, with attempts < MAX_DELIVERY_ATTEMPTS)
     *    are safely reset to 'pending' so they remain recoverable.
     * 3. Deliveries interrupted in STAGE_EXECUTING_COMMANDS, hybrid deliveries in STAGE_AWAITING_CONFIRMATION,
     *    or deliveries that exhausted attempts are transitioned to 'needs_review' and NEVER blindly re-run.
     *
     * @return number of deliveries marked as needs_review
     */
    public static int recoverInterruptedDeliveriesNow() {
        String recoverPreExecSql = "UPDATE autopix_deliveries "
                + "SET status = 'pending', claim_token = NULL, claimed_at = NULL, "
                + "execution_stage = 'RECOVERED_PRE_EXEC', updated_at = CURRENT_TIMESTAMP "
                + "WHERE LOWER(status) = 'processing' AND execution_stage = ?;";

        String recoverUnconfirmedSkriptSql = "UPDATE autopix_deliveries "
                + "SET status = 'pending', claim_token = NULL, claimed_at = NULL, "
                + "execution_stage = 'RECOVERED_SKRIPT', updated_at = CURRENT_TIMESTAMP, "
                + "last_error = 'Entrega Skript recuperada apos reinicializacao/reload sem confirmacao.' "
                + "WHERE LOWER(status) = 'processing' AND execution_stage = ? "
                + "AND LOWER(delivery_type) = 'skript' AND attempts < ?;";

        String selectUncertainSql = "SELECT delivery_id, order_id, item_index, player_uuid, handler "
                + "FROM autopix_deliveries "
                + "WHERE LOWER(status) = 'processing';";

        String markNeedsReviewSql = "UPDATE autopix_deliveries "
                + "SET status = 'needs_review', execution_stage = ?, updated_at = CURRENT_TIMESTAMP, "
                + "last_error = 'Interrupcao apos inicio da execucao; verifique se a recompensa foi entregue antes de repetir.' "
                + "WHERE LOWER(status) = 'processing';";

        int reviewCount = 0;
        List<DeliveryNeedsReviewEvent> eventsToFire = new ArrayList<>();

        try (Connection connection = OrderManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement preStmt = connection.prepareStatement(recoverPreExecSql)) {
                    preStmt.setString(1, STAGE_CLAIMED);
                    preStmt.executeUpdate();
                }

                try (PreparedStatement skriptStmt = connection.prepareStatement(recoverUnconfirmedSkriptSql)) {
                    skriptStmt.setString(1, STAGE_AWAITING_CONFIRMATION);
                    skriptStmt.setInt(2, MAX_DELIVERY_ATTEMPTS);
                    skriptStmt.executeUpdate();
                }

                try (PreparedStatement selStmt = connection.prepareStatement(selectUncertainSql);
                     ResultSet rs = selStmt.executeQuery()) {
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

                try (PreparedStatement revStmt = connection.prepareStatement(markNeedsReviewSql)) {
                    revStmt.setString(1, STAGE_NEEDS_REVIEW);
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

        AutoPix plugin = AutoPix.getInstance();
        for (DeliveryNeedsReviewEvent event : eventsToFire) {
            defaultMainThreadExecutor(plugin).execute(() -> AutoPixEventDispatcher.dispatch(event));
        }

        return reviewCount;
    }

    public static boolean ensureOutboxQueued(Order order, List<OrderItem> items, UUID playerUuid) {
        if (order == null) return false;
        try (Connection connection = OrderManager.getConnection()) {
            ensureOutboxQueued(connection, order.getId(), items, playerUuid);
            return true;
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao enfileirar entregas no outbox: " + ex.getMessage());
            }
            return false;
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

            String checkSql = "SELECT status FROM autopix_deliveries WHERE order_id = ? AND item_index = ?;";
            try (PreparedStatement check = connection.prepareStatement(checkSql)) {
                check.setInt(1, orderId);
                check.setInt(2, itemIndex);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return null;
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
                return null;
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

    /**
     * Marks a claimed delivery as awaiting explicit Skript confirmation via AutoPixAPI.confirmCustomDelivery.
     */
    public static boolean markDeliveryAwaitingConfirmation(int orderId, int itemIndex, String claimToken) {
        if (claimToken == null) return false;
        String sql = "UPDATE autopix_deliveries SET execution_stage = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing';";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STAGE_AWAITING_CONFIRMATION);
            statement.setInt(2, orderId);
            statement.setInt(3, itemIndex);
            statement.setString(4, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao atualizar estagio de confirmacao Skript da entrega: " + ex.getMessage());
            }
            return false;
        }
    }

    public static void releaseUnstartedClaim(int orderId, int itemIndex, String claimToken) {
        if (claimToken == null) return;
        String sql = "UPDATE autopix_deliveries "
                + "SET status = 'pending', claim_token = NULL, claimed_at = NULL, execution_stage = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing' AND execution_stage = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            statement.setInt(2, itemIndex);
            statement.setString(3, claimToken);
            statement.setString(4, STAGE_CLAIMED);
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public static boolean completeDeliveryItem(int orderId, int itemIndex, String claimToken, String status, String error) {
        if (claimToken == null) return false;
        String sql = "UPDATE autopix_deliveries "
                + "SET status = ?, execution_stage = ?, updated_at = CURRENT_TIMESTAMP, last_error = ? "
                + "WHERE order_id = ? AND item_index = ? AND claim_token = ? AND LOWER(status) = 'processing';";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, STAGE_COMPLETED);
            statement.setString(3, error);
            statement.setInt(4, orderId);
            statement.setInt(5, itemIndex);
            statement.setString(6, claimToken);
            if (statement.executeUpdate() != 1) {
                return false;
            }
            updateOrderStatusIfAllItemsDelivered(connection, orderId);
            return true;
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao completar entrega: " + ex.getMessage());
            }
            return false;
        }
    }

    private static void updateOrderStatusIfAllItemsDelivered(Connection connection, int orderId) throws SQLException {
        String checkPendingSql = "SELECT COUNT(*) AS remaining FROM autopix_deliveries "
                + "WHERE order_id = ? AND LOWER(status) NOT IN ('delivered', 'completed');";
        try (PreparedStatement check = connection.prepareStatement(checkPendingSql)) {
            check.setInt(1, orderId);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next() && rs.getInt("remaining") == 0) {
                    try (PreparedStatement updOrder = connection.prepareStatement(
                            "UPDATE autopix_orders SET status = 'DELIVERED' WHERE id = ? AND status = 'PAID';")) {
                        updOrder.setInt(1, orderId);
                        updOrder.executeUpdate();
                    }
                }
            }
        }
    }

    public static boolean markDeliveryItemForReview(int orderId, int itemIndex, String claimToken, String reason) {
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
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Erro ao marcar entrega para revisao: " + ex.getMessage());
            }
            return false;
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
