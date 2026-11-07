package io.github.warleysr.autopix.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.api.Payment;
import io.github.warleysr.autopix.api.PaymentStatus;
import io.github.warleysr.autopix.api.Subscription;
import io.github.warleysr.autopix.api.SubscriptionInvoice;
import io.github.warleysr.autopix.api.SubscriptionResult;
import io.github.warleysr.autopix.api.SubscriptionStatus;
import io.github.warleysr.autopix.catalog.CatalogManager;
import io.github.warleysr.autopix.catalog.DeliveryConfig;
import io.github.warleysr.autopix.catalog.Offer;
import io.github.warleysr.autopix.catalog.OfferType;
import io.github.warleysr.autopix.catalog.Product;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.SubscriptionCancelledEvent;
import io.github.warleysr.autopix.events.SubscriptionCreatedEvent;
import io.github.warleysr.autopix.events.SubscriptionPaymentFailedEvent;
import io.github.warleysr.autopix.events.SubscriptionRenewedEvent;
import io.github.warleysr.autopix.events.SubscriptionSuspendedEvent;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.github.warleysr.autopix.qrcode.PixGenerator;

/**
 * Manages PIX-billed recurring subscriptions and billing cycle invoices.
 *
 * Technical limitation of Mercado Pago PIX:
 * Standard Mercado Pago PIX (/v1/payments with payment_method_id=pix) does not support
 * automatic bank account debit without payer action per cycle. Therefore, subscriptions
 * operate on an explicit invoice-per-cycle model:
 * - Creating a subscription creates a PENDING subscription, a real Order/PIX charge, and a PENDING invoice.
 * - A subscription only becomes ACTIVE after the provider confirms payment of its initial invoice.
 * - Renewing a subscription generates a PENDING renewal invoice and PIX charge for the next cycle;
 *   the billing period (current_period_end) is only extended after the renewal payment is confirmed.
 */
public final class SubscriptionManager {

    private static final Map<String, Object> SUBSCRIPTION_LOCKS = new ConcurrentHashMap<>();
    private static final long DEFAULT_INVOICE_EXPIRATION_MINUTES = 30L;
    private static final long DEFAULT_GRACE_PERIOD_DAYS = 3L;

    private SubscriptionManager() {
    }

    private static Object lockFor(String key) {
        return SUBSCRIPTION_LOCKS.computeIfAbsent(key, ignored -> new Object());
    }

    public static SubscriptionResult createSubscription(UUID uuid, String productId, String planId) {
        if (uuid == null) return SubscriptionResult.failure("UUID do jogador inválido.");
        if (productId == null || productId.trim().isEmpty()) {
            return SubscriptionResult.failure("ID do produto inválido.");
        }
        if (planId == null || planId.trim().isEmpty()) {
            return SubscriptionResult.failure("ID do plano/oferta de assinatura não informado.");
        }

        Product product = CatalogManager.getProduct(productId);
        if (product == null || !product.isEnabled()) {
            return SubscriptionResult.failure("Produto '" + productId + "' não encontrado ou indisponível no catálogo.");
        }

        Offer offer = product.getOffer(planId.trim());
        if (offer == null) {
            return SubscriptionResult.failure("Oferta '" + planId + "' não existe para o produto '" + productId + "'.");
        }
        if (!offer.isEnabled()) {
            return SubscriptionResult.failure("A oferta '" + planId + "' está indisponível no momento.");
        }
        if (offer.getType() != OfferType.SUBSCRIPTION) {
            return SubscriptionResult.failure("A oferta '" + planId + "' não é uma modalidade de assinatura.");
        }

        BigDecimal price = offer.getPrice() != null
                ? offer.getPrice().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (price.compareTo(new BigDecimal("0.01")) < 0) {
            return SubscriptionResult.failure("O preço da assinatura deve ser maior que zero.");
        }

        String duration = offer.getDurationString() != null ? offer.getDurationString() : "30d";
        String frequency = resolveFrequencyLabel(duration);

        String playerLockKey = "create:" + uuid + ":" + product.getId().toLowerCase(Locale.ROOT) + ":" + offer.getId().toLowerCase(Locale.ROOT);
        synchronized (lockFor(playerLockKey)) {
            String subscriptionId = "sub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String invoiceId = "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Instant now = Instant.now();
            Instant dueDate = now.plus(DEFAULT_INVOICE_EXPIRATION_MINUTES, ChronoUnit.MINUTES);

            String playerName = resolvePlayerName(uuid);
            OrderItem subItem = buildSubscriptionOrderItem(product, offer, price, duration);
            String summary = truncate(product.getName() + " (" + offer.getId() + ")", 64);
            String idempotencyKey = "sub_charge_" + subscriptionId + "_cycle_1";

            PixData pixData = createProviderOrLocalCharge(playerName, summary, price, subscriptionId, idempotencyKey);
            if (pixData == null) {
                return SubscriptionResult.failure("Não foi possível gerar a cobrança PIX para a assinatura.");
            }

            Order order = OrderManager.createOrderDetailed(
                    playerName, uuid, summary, price, price, BigDecimal.ZERO,
                    null, dueDate, pixData, Collections.singletonList(subItem));
            if (order == null) {
                return SubscriptionResult.failure("Erro ao registrar o pedido da assinatura no banco de dados.");
            }

            Payment payment = new Payment(
                    pixData.getPaymentId(), pixData.getPaymentId(), PaymentStatus.PENDING,
                    pixData.getQrCode(), pixData.getQrCode(), null, dueDate);
            OrderManager.savePayment(payment, order.getId(), price);

            String insertSubSql = "INSERT INTO autopix_subscriptions "
                    + "(subscription_id, player_uuid, product_id, plan_id, provider_subscription_id, status, price, currency, "
                    + "interval_type, interval_count, current_period_start, current_period_end, next_billing_at, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'BRL', ?, 1, NULL, NULL, NULL, ?, ?);";

            String insertInvoiceSql = "INSERT INTO autopix_subscription_invoices "
                    + "(invoice_id, subscription_id, cycle_number, order_id, provider_payment_id, amount, currency, status, "
                    + "billing_date, due_date, paid_at, created_at) "
                    + "VALUES (?, ?, 1, ?, ?, ?, 'BRL', 'PENDING', ?, ?, NULL, ?);";

            try (Connection connection = OrderManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement subStmt = connection.prepareStatement(insertSubSql)) {
                        subStmt.setString(1, subscriptionId);
                        subStmt.setString(2, uuid.toString());
                        subStmt.setString(3, product.getId());
                        subStmt.setString(4, offer.getId());
                        subStmt.setString(5, pixData.getPaymentId());
                        subStmt.setString(6, SubscriptionStatus.PENDING.name());
                        subStmt.setBigDecimal(7, price);
                        subStmt.setString(8, frequency);
                        subStmt.setTimestamp(9, Timestamp.from(now));
                        subStmt.setTimestamp(10, Timestamp.from(now));
                        subStmt.executeUpdate();
                    }

                    try (PreparedStatement invStmt = connection.prepareStatement(insertInvoiceSql)) {
                        invStmt.setString(1, invoiceId);
                        invStmt.setString(2, subscriptionId);
                        invStmt.setInt(3, order.getId());
                        invStmt.setString(4, pixData.getPaymentId());
                        invStmt.setBigDecimal(5, price);
                        invStmt.setTimestamp(6, Timestamp.from(now));
                        invStmt.setTimestamp(7, Timestamp.from(dueDate));
                        invStmt.setTimestamp(8, Timestamp.from(now));
                        invStmt.executeUpdate();
                    }

                    connection.commit();

                    Subscription subscription = new Subscription(
                            subscriptionId, uuid, product.getId(), offer.getId(),
                            pixData.getPaymentId(), SubscriptionStatus.PENDING, price, frequency, now, null, null);

                    SubscriptionInvoice invoice = new SubscriptionInvoice(
                            invoiceId, subscriptionId, 1, order.getId(), pixData.getPaymentId(),
                            price, "BRL", "PENDING", now, dueDate, null, now);

                    AutoPixEventDispatcher.dispatch(new SubscriptionCreatedEvent(
                            subscriptionId, uuid, product.getId(), offer.getId(), price));

                    return SubscriptionResult.success(
                            subscription, invoice, order, payment,
                            "Assinatura registrada como PENDENTE. Confirme o pagamento PIX para ativá-la.");
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                logError("Erro ao criar assinatura no banco", ex);
                return SubscriptionResult.failure("Erro de banco de dados ao criar assinatura: " + ex.getMessage());
            }
        }
    }

    /**
     * Generates a new renewal invoice and real PIX charge for the next cycle of an active/past_due subscription.
     * Does NOT mark the invoice as PAID and does NOT extend current_period_end until the payment is confirmed.
     */
    public static SubscriptionResult renewSubscription(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            return SubscriptionResult.failure("ID de assinatura inválido.");
        }
        String normSubId = subscriptionId.trim();

        synchronized (lockFor("renew:" + normSubId)) {
            Subscription sub = getSubscription(normSubId);
            if (sub == null) {
                return SubscriptionResult.failure("Assinatura não encontrada.");
            }
            if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
                return SubscriptionResult.failure("Não é possível renovar uma assinatura cancelada.");
            }
            if (sub.getStatus() == SubscriptionStatus.PENDING) {
                return SubscriptionResult.failure("A assinatura ainda aguarda o pagamento da fatura inicial.");
            }

            Product product = CatalogManager.getProduct(sub.getProductId());
            Offer offer = product != null ? product.getOffer(sub.getOfferId()) : null;
            String duration = offer != null && offer.getDurationString() != null ? offer.getDurationString() : "30d";
            BigDecimal amount = sub.getAmount().setScale(2, RoundingMode.HALF_UP);

            int nextCycle;
            try (Connection connection = OrderManager.getConnection()) {
                // Prevent duplicate open renewal invoices for the same cycle
                String checkOpenSql = "SELECT invoice_id, cycle_number FROM autopix_subscription_invoices "
                        + "WHERE subscription_id = ? AND status = 'PENDING';";
                try (PreparedStatement checkStmt = connection.prepareStatement(checkOpenSql)) {
                    checkStmt.setString(1, normSubId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            return SubscriptionResult.failure("Já existe uma fatura de renovação pendente ("
                                    + rs.getString("invoice_id") + ") para o ciclo #" + rs.getInt("cycle_number") + ".");
                        }
                    }
                }

                String maxCycleSql = "SELECT COALESCE(MAX(cycle_number), 0) AS max_cycle "
                        + "FROM autopix_subscription_invoices WHERE subscription_id = ?;";
                try (PreparedStatement maxStmt = connection.prepareStatement(maxCycleSql)) {
                    maxStmt.setString(1, normSubId);
                    try (ResultSet rs = maxStmt.executeQuery()) {
                        nextCycle = (rs.next() ? rs.getInt("max_cycle") : 1) + 1;
                    }
                }
            } catch (SQLException ex) {
                logError("Erro ao verificar ciclo de renovação da assinatura #" + normSubId, ex);
                return SubscriptionResult.failure("Erro ao verificar ciclo de renovação: " + ex.getMessage());
            }

            Instant now = Instant.now();
            Instant dueDate = now.plus(DEFAULT_INVOICE_EXPIRATION_MINUTES, ChronoUnit.MINUTES);
            String playerName = resolvePlayerName(sub.getPlayerUuid());
            OrderItem renewalItem = product != null && offer != null
                    ? buildSubscriptionOrderItem(product, offer, amount, duration)
                    : new OrderItem(sub.getProductId(), sub.getOfferId(), sub.getProductId(), "vips",
                            1, amount, amount, "autopix", null, duration, Collections.emptyList(), Collections.emptyList());

            String summary = truncate("Renovação " + sub.getProductId() + " (Ciclo #" + nextCycle + ")", 64);
            String idempotencyKey = "sub_charge_" + normSubId + "_cycle_" + nextCycle;

            PixData pixData = createProviderOrLocalCharge(playerName, summary, amount, normSubId, idempotencyKey);
            if (pixData == null) {
                return SubscriptionResult.failure("Não foi possível gerar a cobrança PIX de renovação.");
            }

            Order order = OrderManager.createOrderDetailed(
                    playerName, sub.getPlayerUuid(), summary, amount, amount, BigDecimal.ZERO,
                    null, dueDate, pixData, Collections.singletonList(renewalItem));
            if (order == null) {
                return SubscriptionResult.failure("Erro ao criar pedido de renovação no banco de dados.");
            }

            Payment payment = new Payment(
                    pixData.getPaymentId(), pixData.getPaymentId(), PaymentStatus.PENDING,
                    pixData.getQrCode(), pixData.getQrCode(), null, dueDate);
            OrderManager.savePayment(payment, order.getId(), amount);

            String invoiceId = "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String insertInvoiceSql = "INSERT INTO autopix_subscription_invoices "
                    + "(invoice_id, subscription_id, cycle_number, order_id, provider_payment_id, amount, currency, status, "
                    + "billing_date, due_date, paid_at, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'BRL', 'PENDING', ?, ?, NULL, ?);";

            try (Connection connection = OrderManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement invStmt = connection.prepareStatement(insertInvoiceSql)) {
                        invStmt.setString(1, invoiceId);
                        invStmt.setString(2, normSubId);
                        invStmt.setInt(3, nextCycle);
                        invStmt.setInt(4, order.getId());
                        invStmt.setString(5, pixData.getPaymentId());
                        invStmt.setBigDecimal(6, amount);
                        invStmt.setTimestamp(7, Timestamp.from(now));
                        invStmt.setTimestamp(8, Timestamp.from(dueDate));
                        invStmt.setTimestamp(9, Timestamp.from(now));
                        invStmt.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                logError("Erro ao gerar fatura de renovação da assinatura #" + normSubId, ex);
                return SubscriptionResult.failure("Já existe fatura para este ciclo ou ocorreu erro de banco: " + ex.getMessage());
            }

            SubscriptionInvoice invoice = new SubscriptionInvoice(
                    invoiceId, normSubId, nextCycle, order.getId(), pixData.getPaymentId(),
                    amount, "BRL", "PENDING", now, dueDate, null, now);

            Subscription currentSub = getSubscription(normSubId);
            return SubscriptionResult.success(
                    currentSub, invoice, order, payment,
                    "Cobrança de renovação gerada com status PENDENTE. O período será estendido após o pagamento.");
        }
    }

    /**
     * Confirms a subscription invoice payment after verifying the transaction and paid amount.
     * Activates a PENDING subscription (cycle 1) or extends the period of an existing subscription (cycle > 1).
     * Strictly idempotent: confirming the same invoice twice will not extend the period twice.
     */
    public static SubscriptionResult confirmSubscriptionPayment(String subscriptionId, String invoiceId,
                                                                String transactionId, BigDecimal paidAmount) {
        if (subscriptionId == null || invoiceId == null) {
            return SubscriptionResult.failure("Assinatura ou fatura não informada.");
        }
        if (transactionId == null || transactionId.trim().isEmpty()) {
            return SubscriptionResult.failure("Identificador da transação confirmada é obrigatório.");
        }
        if (paidAmount == null || paidAmount.compareTo(new BigDecimal("0.01")) < 0) {
            return SubscriptionResult.failure("Valor pago inválido para confirmação de assinatura.");
        }

        String normSubId = subscriptionId.trim();
        String normInvId = invoiceId.trim();
        String normTxId = transactionId.trim();
        BigDecimal normalizedPaid = paidAmount.setScale(2, RoundingMode.HALF_UP);

        synchronized (lockFor("confirm:" + normSubId)) {
            SubscriptionInvoice invoice = getInvoice(normInvId);
            if (invoice == null || !normSubId.equals(invoice.getSubscriptionId())) {
                return SubscriptionResult.failure("Fatura não encontrada para a assinatura informada.");
            }

            if (invoice.isPaid()) {
                Subscription existing = getSubscription(normSubId);
                return SubscriptionResult.success(existing, invoice, null, null,
                        "Fatura já havia sido confirmada anteriormente (operação idempotente).");
            }

            if (!"PENDING".equalsIgnoreCase(invoice.getStatus())) {
                return SubscriptionResult.failure("Fatura em estado não pagável: " + invoice.getStatus());
            }

            if (normalizedPaid.compareTo(invoice.getAmount()) != 0) {
                handleSubscriptionPaymentFailure(normSubId, normInvId, "AMOUNT_MISMATCH",
                        "Valor pago (R$ " + normalizedPaid + ") diverge do valor da fatura (R$ " + invoice.getAmount() + ").");
                return SubscriptionResult.failure("Valor pago diverge do valor exigido pela fatura.");
            }

            // Ensure associated order is approved in database if not already
            if (invoice.getOrderId() != null && invoice.getOrderId() > 0) {
                Order order = OrderManager.getOrderById(invoice.getOrderId());
                if (order != null && !order.isValidated()) {
                    PixData pd = OrderManager.getPixData(order);
                    if (pd != null) {
                        OrderManager.approvePayment(pd, order, normTxId);
                    } else {
                        OrderManager.approveManualOrder(order, normTxId);
                    }
                }
            }

            return activateOrExtendSubscriptionForInvoice(normSubId, invoice, normTxId);
        }
    }

    /**
     * Hook called by OrderManager when an order's payment is confirmed by Mercado Pago or manual validation.
     * Automatically activates or renews any subscription linked to that orderId.
     */
    public static boolean onOrderPaymentApproved(int orderId, String providerPaymentId,
                                                 String transactionId, BigDecimal paidAmount) {
        if (orderId <= 0 || transactionId == null || transactionId.trim().isEmpty()) {
            return false;
        }
        SubscriptionInvoice invoice = getInvoiceByOrderId(orderId);
        if (invoice == null) {
            return false;
        }
        BigDecimal effectivePaid = paidAmount != null ? paidAmount : invoice.getAmount();
        SubscriptionResult result = confirmSubscriptionPayment(
                invoice.getSubscriptionId(), invoice.getInvoiceId(), transactionId, effectivePaid);
        return result.isSuccessful();
    }

    /**
     * Handles a rejected, cancelled, or expired payment for a subscription invoice.
     * Never activates the subscription or extends its period.
     */
    public static SubscriptionResult handleSubscriptionPaymentFailure(String subscriptionId, String invoiceId,
                                                                      String failureStatus, String reason) {
        if (subscriptionId == null || invoiceId == null) {
            return SubscriptionResult.failure("Assinatura ou fatura não informada.");
        }
        String normSubId = subscriptionId.trim();
        String normInvId = invoiceId.trim();
        String normInvoiceStatus = normalizeInvoiceFailureStatus(failureStatus);

        synchronized (lockFor("confirm:" + normSubId)) {
            Subscription sub = getSubscription(normSubId);
            SubscriptionInvoice invoice = getInvoice(normInvId);
            if (sub == null || invoice == null) {
                return SubscriptionResult.failure("Assinatura ou fatura não encontrada.");
            }
            if (invoice.isPaid()) {
                return SubscriptionResult.failure("Uma fatura já paga não pode ser marcada como falha.");
            }

            Instant now = Instant.now();
            SubscriptionStatus nextSubStatus;
            Timestamp graceUntil = null;

            if (invoice.getCycleNumber() <= 1 || sub.getStatus() == SubscriptionStatus.PENDING) {
                nextSubStatus = "CANCELLED".equalsIgnoreCase(normInvoiceStatus)
                        ? SubscriptionStatus.CANCELLED
                        : SubscriptionStatus.UNPAID;
            } else {
                nextSubStatus = SubscriptionStatus.PAST_DUE;
                graceUntil = Timestamp.from(now.plus(DEFAULT_GRACE_PERIOD_DAYS, ChronoUnit.DAYS));
            }

            String updateInvSql = "UPDATE autopix_subscription_invoices SET status = ? WHERE invoice_id = ? AND status <> 'PAID';";
            String updateSubSql = "UPDATE autopix_subscriptions SET status = ?, grace_until = ?, updated_at = ? WHERE subscription_id = ?;";

            try (Connection connection = OrderManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement invStmt = connection.prepareStatement(updateInvSql)) {
                        invStmt.setString(1, normInvoiceStatus);
                        invStmt.setString(2, normInvId);
                        invStmt.executeUpdate();
                    }

                    try (PreparedStatement subStmt = connection.prepareStatement(updateSubSql)) {
                        subStmt.setString(1, nextSubStatus.name());
                        subStmt.setTimestamp(2, graceUntil);
                        subStmt.setTimestamp(3, Timestamp.from(now));
                        subStmt.setString(4, normSubId);
                        subStmt.executeUpdate();
                    }

                    connection.commit();
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                logError("Erro ao registrar falha de pagamento da assinatura #" + normSubId, ex);
                return SubscriptionResult.failure("Erro de banco ao registrar falha: " + ex.getMessage());
            }

            AutoPixEventDispatcher.dispatch(new SubscriptionPaymentFailedEvent(
                    normSubId, sub.getPlayerUuid(), normInvId, invoice.getAmount(),
                    reason != null ? reason : "Pagamento não aprovado (" + normInvoiceStatus + ")"));

            Subscription updatedSub = getSubscription(normSubId);
            SubscriptionInvoice updatedInv = getInvoice(normInvId);
            return SubscriptionResult.success(updatedSub, updatedInv, null, null,
                    "Falha de pagamento registrada. Assinatura em estado " + nextSubStatus.name() + ".");
        }
    }

    public static void processDueSubscriptions() {
        String sql = "SELECT subscription_id, player_uuid FROM autopix_subscriptions "
                + "WHERE status = 'PAST_DUE' AND grace_until IS NOT NULL AND grace_until < CURRENT_TIMESTAMP;";
        Instant now = Instant.now();
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<String[]> toSuspend = new ArrayList<>();
            while (rs.next()) {
                toSuspend.add(new String[]{rs.getString("subscription_id"), rs.getString("player_uuid")});
            }
            for (String[] entry : toSuspend) {
                String subId = entry[0];
                UUID playerUuid = null;
                try {
                    if (entry[1] != null) {
                        playerUuid = UUID.fromString(entry[1]);
                    }
                } catch (IllegalArgumentException ignored) {
                }
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE autopix_subscriptions SET status = 'SUSPENDED', updated_at = ? WHERE subscription_id = ? AND status = 'PAST_DUE';")) {
                    upd.setTimestamp(1, Timestamp.from(now));
                    upd.setString(2, subId);
                    if (upd.executeUpdate() > 0 && playerUuid != null) {
                        AutoPixEventDispatcher.dispatch(new SubscriptionSuspendedEvent(
                                subId, playerUuid, "Período de carência expirado sem pagamento."));
                    }
                }
            }
        } catch (SQLException ex) {
            logError("Erro ao processar assinaturas vencidas", ex);
        }
    }

    public static void onOrderPaymentFailed(int orderId, String failureStatus, String reason) {
        if (orderId <= 0) return;
        SubscriptionInvoice invoice = getInvoiceByOrderId(orderId);
        if (invoice != null && "PENDING".equalsIgnoreCase(invoice.getStatus())) {
            handleSubscriptionPaymentFailure(invoice.getSubscriptionId(), invoice.getInvoiceId(), failureStatus, reason);
        }
    }

    private static SubscriptionResult activateOrExtendSubscriptionForInvoice(
            String subscriptionId, SubscriptionInvoice invoice, String transactionId) {
        Subscription sub = getSubscription(subscriptionId);
        if (sub == null) {
            return SubscriptionResult.failure("Assinatura não encontrada.");
        }

        Product product = CatalogManager.getProduct(sub.getProductId());
        Offer offer = product != null ? product.getOffer(sub.getOfferId()) : null;
        String duration = offer != null && offer.getDurationString() != null ? offer.getDurationString() : "30d";
        String frequency = sub.getFrequency();

        Instant now = Instant.now();
        Instant newPeriodStart;
        Instant newPeriodEnd;
        boolean alreadyProcessed = false;

        try (Connection connection = OrderManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Atomic transition from PENDING -> PAID
                String updateInvSql = "UPDATE autopix_subscription_invoices "
                        + "SET status = 'PAID', provider_payment_id = COALESCE(?, provider_payment_id), paid_at = ? "
                        + "WHERE invoice_id = ? AND status = 'PENDING';";
                try (PreparedStatement invStmt = connection.prepareStatement(updateInvSql)) {
                    invStmt.setString(1, transactionId);
                    invStmt.setTimestamp(2, Timestamp.from(now));
                    invStmt.setString(3, invoice.getInvoiceId());
                    int updatedRows = invStmt.executeUpdate();
                    if (updatedRows == 0) {
                        connection.rollback();
                        alreadyProcessed = true;
                        newPeriodStart = now;
                        newPeriodEnd = now;
                    } else {
                        // Read current period end inside the transaction
                        Instant currentStart = null;
                        Instant currentEnd = null;
                        String selectPeriodSql = "SELECT current_period_start, current_period_end FROM autopix_subscriptions WHERE subscription_id = ?;";
                        try (PreparedStatement selStmt = connection.prepareStatement(selectPeriodSql)) {
                            selStmt.setString(1, subscriptionId);
                            try (ResultSet rs = selStmt.executeQuery()) {
                                if (rs.next()) {
                                    Timestamp startTs = rs.getTimestamp("current_period_start");
                                    Timestamp endTs = rs.getTimestamp("current_period_end");
                                    if (startTs != null) currentStart = startTs.toInstant();
                                    if (endTs != null) currentEnd = endTs.toInstant();
                                }
                            }
                        }

                        if (currentEnd == null || currentEnd.isBefore(now)) {
                            newPeriodStart = now;
                            newPeriodEnd = calculatePeriodEnd(now, duration, frequency);
                        } else {
                            newPeriodStart = currentStart != null ? currentStart : now;
                            newPeriodEnd = calculatePeriodEnd(currentEnd, duration, frequency);
                        }

                        String updateSubSql = "UPDATE autopix_subscriptions "
                                + "SET status = ?, current_period_start = ?, current_period_end = ?, next_billing_at = ?, "
                                + "grace_until = NULL, updated_at = ? WHERE subscription_id = ?;";
                        try (PreparedStatement subStmt = connection.prepareStatement(updateSubSql)) {
                            subStmt.setString(1, SubscriptionStatus.ACTIVE.name());
                            subStmt.setTimestamp(2, Timestamp.from(newPeriodStart));
                            subStmt.setTimestamp(3, Timestamp.from(newPeriodEnd));
                            subStmt.setTimestamp(4, Timestamp.from(newPeriodEnd));
                            subStmt.setTimestamp(5, Timestamp.from(now));
                            subStmt.setString(6, subscriptionId);
                            subStmt.executeUpdate();
                        }

                        connection.commit();
                    }
                }
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            logError("Erro ao confirmar pagamento da assinatura #" + subscriptionId, ex);
            return SubscriptionResult.failure("Erro de banco ao ativar/renovar assinatura: " + ex.getMessage());
        }

        if (alreadyProcessed) {
            Subscription currentSub = getSubscription(subscriptionId);
            SubscriptionInvoice currentInv = getInvoice(invoice.getInvoiceId());
            return SubscriptionResult.success(currentSub, currentInv, null, null,
                    "Fatura já processada anteriormente (idempotente).");
        }

        if (invoice.getCycleNumber() > 1) {
            AutoPixEventDispatcher.dispatch(new SubscriptionRenewedEvent(
                    subscriptionId, sub.getPlayerUuid(), invoice.getInvoiceId(), invoice.getAmount()));
        }

        Subscription updatedSub = new Subscription(
                sub.getId(), sub.getPlayerUuid(), sub.getProductId(), sub.getOfferId(),
                sub.getProviderSubscriptionId(), SubscriptionStatus.ACTIVE, sub.getAmount(),
                sub.getFrequency(), sub.getCreatedAt(), newPeriodEnd, sub.getCancelledAt());

        SubscriptionInvoice updatedInv = getInvoice(invoice.getInvoiceId());
        return SubscriptionResult.success(updatedSub, updatedInv, null, null,
                invoice.getCycleNumber() > 1
                        ? "Assinatura renovada mediante confirmação de pagamento."
                        : "Assinatura ativada mediante confirmação de pagamento.");
    }

    public static SubscriptionResult cancelSubscription(String subscriptionId, boolean cancelImmediately) {
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            return SubscriptionResult.failure("ID de assinatura inválido.");
        }

        Subscription sub = getSubscription(subscriptionId);
        if (sub == null) {
            return SubscriptionResult.failure("Assinatura não encontrada.");
        }

        String sql = "UPDATE autopix_subscriptions SET status = ?, cancelled_at = ?, updated_at = ? WHERE subscription_id = ?;";
        String cancelInvoicesSql = "UPDATE autopix_subscription_invoices SET status = 'CANCELLED' WHERE subscription_id = ? AND status = 'PENDING';";
        Instant now = Instant.now();

        try (Connection connection = OrderManager.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 PreparedStatement invStatement = connection.prepareStatement(cancelInvoicesSql)) {
                statement.setString(1, SubscriptionStatus.CANCELLED.name());
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setString(4, subscriptionId.trim());

                int updated = statement.executeUpdate();
                invStatement.setString(1, subscriptionId.trim());
                invStatement.executeUpdate();

                connection.commit();

                if (updated > 0) {
                    AutoPixEventDispatcher.dispatch(new SubscriptionCancelledEvent(
                            subscriptionId.trim(), sub.getPlayerUuid(), cancelImmediately ? "immediate" : "scheduled"));

                    if (cancelImmediately) {
                        AutoPixEventDispatcher.dispatch(new SubscriptionSuspendedEvent(
                                subscriptionId.trim(), sub.getPlayerUuid(), "Assinatura cancelada imediatamente."));
                    }

                    Subscription updatedSub = new Subscription(
                            sub.getId(), sub.getPlayerUuid(), sub.getProductId(), sub.getOfferId(),
                            sub.getProviderSubscriptionId(), SubscriptionStatus.CANCELLED, sub.getAmount(),
                            sub.getFrequency(), sub.getCreatedAt(), sub.getNextBillingAt(), now);

                    return SubscriptionResult.success(updatedSub, "Assinatura cancelada com sucesso.");
                }
                return SubscriptionResult.failure("Falha ao atualizar status da assinatura.");
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            logError("Erro ao cancelar assinatura #" + subscriptionId, ex);
            return SubscriptionResult.failure("Erro de banco de dados ao cancelar assinatura: " + ex.getMessage());
        }
    }

    public static Subscription getSubscription(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) return null;
        String sql = "SELECT * FROM autopix_subscriptions WHERE subscription_id = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subscriptionId.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return readSubscription(rs);
                }
            }
        } catch (SQLException ex) {
            logError("Erro ao buscar assinatura #" + subscriptionId, ex);
        }
        return null;
    }

    public static List<Subscription> getPlayerSubscriptions(UUID uuid) {
        List<Subscription> list = new ArrayList<>();
        if (uuid == null) return list;
        String sql = "SELECT * FROM autopix_subscriptions WHERE player_uuid = ? ORDER BY created_at DESC;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    list.add(readSubscription(rs));
                }
            }
        } catch (SQLException ex) {
            logError("Erro ao buscar assinaturas de " + uuid, ex);
        }
        return list;
    }

    public static SubscriptionInvoice getInvoice(String invoiceId) {
        if (invoiceId == null || invoiceId.trim().isEmpty()) return null;
        String sql = "SELECT * FROM autopix_subscription_invoices WHERE invoice_id = ?;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, invoiceId.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return readInvoice(rs);
                }
            }
        } catch (SQLException ex) {
            logError("Erro ao buscar fatura #" + invoiceId, ex);
        }
        return null;
    }

    public static SubscriptionInvoice getInvoiceByOrderId(int orderId) {
        if (orderId <= 0) return null;
        String sql = "SELECT * FROM autopix_subscription_invoices WHERE order_id = ? ORDER BY cycle_number DESC LIMIT 1;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, orderId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return readInvoice(rs);
                }
            }
        } catch (SQLException ex) {
            logError("Erro ao buscar fatura do pedido #" + orderId, ex);
        }
        return null;
    }

    public static List<SubscriptionInvoice> getSubscriptionInvoices(String subscriptionId) {
        List<SubscriptionInvoice> list = new ArrayList<>();
        if (subscriptionId == null || subscriptionId.trim().isEmpty()) return list;
        String sql = "SELECT * FROM autopix_subscription_invoices WHERE subscription_id = ? ORDER BY cycle_number ASC;";
        try (Connection connection = OrderManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subscriptionId.trim());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    list.add(readInvoice(rs));
                }
            }
        } catch (SQLException ex) {
            logError("Erro ao listar faturas da assinatura #" + subscriptionId, ex);
        }
        return list;
    }

    private static PixData createProviderOrLocalCharge(String playerName, String summary, BigDecimal price,
                                                       String externalRef, String idempotencyKey) {
        AutoPix plugin = AutoPix.getInstance();
        boolean automatic = plugin != null && plugin.getConfig().getBoolean("automatico.ativado", true);
        try {
            PixData fromProvider = MercadoPagoAPI.createPixPayment(plugin, playerName, summary, price, externalRef, idempotencyKey);
            if (fromProvider != null) {
                return fromProvider;
            }
            if (automatic) {
                return null;
            }
        } catch (Exception ex) {
            if (automatic) {
                return null;
            }
        }

        String pixKey = AutoPix.getPixKey() != null ? AutoPix.getPixKey() : "autopix@servidor.com";
        String pixName = AutoPix.getPixName() != null ? AutoPix.getPixName() : "AutoPix";
        String qr;
        try {
            qr = PixGenerator.generatePayload(pixKey, pixName, summary, price.floatValue());
        } catch (Exception ex) {
            qr = "00020101021226580014BR.GOV.BCB.PIX01" + idempotencyKey;
        }
        String paymentId = "sub_pay_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString().replace("-", "").substring(0, 16);
        return new PixData(paymentId, qr);
    }

    private static OrderItem buildSubscriptionOrderItem(Product product, Offer offer, BigDecimal price, String duration) {
        DeliveryConfig deliv = product.getDelivery();
        String deliveryType = deliv != null && deliv.getType() != null
                ? deliv.getType().name().toLowerCase(Locale.ROOT)
                : "autopix";
        String deliveryHandler = deliv != null ? deliv.getHandler() : null;
        List<String> preCmds = deliv != null ? deliv.getPreCommands() : Collections.emptyList();
        List<String> cmds = deliv != null ? deliv.getCommands() : Collections.emptyList();

        return new OrderItem(
                product.getId(),
                offer.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategory(),
                offer.getType().name().toLowerCase(Locale.ROOT),
                duration,
                "BRL",
                1,
                price,
                price,
                deliveryType,
                deliveryHandler,
                null,
                preCmds,
                cmds);
    }

    private static Subscription readSubscription(ResultSet rs) throws SQLException {
        String id = rs.getString("subscription_id");
        UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
        String productId = rs.getString("product_id");
        String planId = rs.getString("plan_id");
        String providerId = id;
        try {
            String pId = rs.getString("provider_subscription_id");
            if (pId != null && !pId.trim().isEmpty()) providerId = pId;
        } catch (SQLException ignored) {
        }

        String statusStr = rs.getString("status");
        SubscriptionStatus status = SubscriptionStatus.PENDING;
        try {
            if (statusStr != null) status = SubscriptionStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
        }

        BigDecimal price = rs.getBigDecimal("price");
        if (price != null) price = price.setScale(2, RoundingMode.HALF_UP);
        String freq = rs.getString("interval_type");

        Timestamp created = rs.getTimestamp("created_at");
        Instant createdAt = created != null ? created.toInstant() : Instant.now();

        Timestamp periodEnd = rs.getTimestamp("current_period_end");
        Timestamp billing = rs.getTimestamp("next_billing_at");
        Instant nextBillingAt = periodEnd != null
                ? periodEnd.toInstant()
                : (billing != null ? billing.toInstant() : null);

        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        Instant cancelledAt = cancelled != null ? cancelled.toInstant() : null;

        return new Subscription(id, playerUuid, productId, planId, providerId, status, price, freq, createdAt, nextBillingAt, cancelledAt);
    }

    private static SubscriptionInvoice readInvoice(ResultSet rs) throws SQLException {
        String invoiceId = rs.getString("invoice_id");
        String subscriptionId = rs.getString("subscription_id");
        int cycleNumber = 1;
        try {
            cycleNumber = rs.getInt("cycle_number");
            if (cycleNumber <= 0) cycleNumber = 1;
        } catch (SQLException ignored) {
        }

        int rawOrderId = rs.getInt("order_id");
        Integer orderId = rs.wasNull() ? null : rawOrderId;

        String providerPaymentId = null;
        try {
            providerPaymentId = rs.getString("provider_payment_id");
        } catch (SQLException ignored) {
        }

        BigDecimal amount = rs.getBigDecimal("amount");
        if (amount != null) amount = amount.setScale(2, RoundingMode.HALF_UP);

        String currency = "BRL";
        try {
            String cur = rs.getString("currency");
            if (cur != null && !cur.trim().isEmpty()) currency = cur;
        } catch (SQLException ignored) {
        }

        String status = rs.getString("status");
        Timestamp billingTs = rs.getTimestamp("billing_date");
        Timestamp dueTs = rs.getTimestamp("due_date");
        Timestamp paidTs = rs.getTimestamp("paid_at");
        Timestamp createdTs = rs.getTimestamp("created_at");

        return new SubscriptionInvoice(
                invoiceId,
                subscriptionId,
                cycleNumber,
                orderId,
                providerPaymentId,
                amount,
                currency,
                status,
                billingTs != null ? billingTs.toInstant() : null,
                dueTs != null ? dueTs.toInstant() : null,
                paidTs != null ? paidTs.toInstant() : null,
                createdTs != null ? createdTs.toInstant() : null);
    }

    private static String resolveFrequencyLabel(String duration) {
        if (duration == null) return "monthly";
        String lower = duration.trim().toLowerCase(Locale.ROOT);
        if ("7d".equals(lower)) return "weekly";
        if ("90d".equals(lower)) return "quarterly";
        if ("365d".equals(lower)) return "yearly";
        return "monthly";
    }

    private static String normalizeInvoiceFailureStatus(String raw) {
        if (raw == null) return "REJECTED";
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("CANCEL")) return "CANCELLED";
        if (upper.contains("EXPIR")) return "EXPIRED";
        return "REJECTED";
    }

    private static String resolvePlayerName(UUID uuid) {
        if (uuid == null) return "Player";
        try {
            if (Bukkit.getServer() != null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                if (offline != null && offline.getName() != null && !offline.getName().isEmpty()) {
                    return offline.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return uuid.toString().substring(0, 16);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "Assinatura";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Instant calculatePeriodEnd(Instant start, String duration, String frequency) {
        if (duration != null && !duration.trim().isEmpty()) {
            String lower = duration.trim().toLowerCase(Locale.ROOT);
            if (lower.endsWith("d")) {
                try {
                    long days = Long.parseLong(lower.substring(0, lower.length() - 1));
                    return start.plus(days, ChronoUnit.DAYS);
                } catch (NumberFormatException ignored) {
                }
            }
            if (lower.endsWith("h")) {
                try {
                    long hours = Long.parseLong(lower.substring(0, lower.length() - 1));
                    return start.plus(hours, ChronoUnit.HOURS);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (frequency != null) {
            String lower = frequency.trim().toLowerCase(Locale.ROOT);
            if (lower.contains("year") || lower.contains("anual")) {
                return start.plus(365, ChronoUnit.DAYS);
            }
            if (lower.contains("quarter") || lower.contains("trimestral")) {
                return start.plus(90, ChronoUnit.DAYS);
            }
            if (lower.contains("week") || lower.contains("semanal")) {
                return start.plus(7, ChronoUnit.DAYS);
            }
        }

        return start.plus(30, ChronoUnit.DAYS);
    }

    private static void logError(String context, SQLException ex) {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning(context + ": " + ex.getMessage());
        }
    }
}
