package io.github.warleysr.autopix.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.github.warleysr.autopix.domain.OrderItem;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PaymentApprovedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int orderId;
    private final String playerName;
    private final UUID playerUniqueId;
    private final String product;
    private final double amount;
    private final String paymentId;
    private final String transactionId;
    private final String status;
    private final List<OrderItem> items;

    public PaymentApprovedEvent(int orderId, String playerName, UUID playerUniqueId,
                                String product, double amount, String paymentId,
                                String transactionId) {
        this(orderId, playerName, playerUniqueId, product, amount, paymentId,
                transactionId, Collections.emptyList());
    }

    public PaymentApprovedEvent(int orderId, String playerName, UUID playerUniqueId,
                                String product, double amount, String paymentId,
                                String transactionId, List<OrderItem> items) {
        super(false);
        this.orderId = orderId;
        this.playerName = playerName;
        this.playerUniqueId = playerUniqueId;
        this.product = product;
        this.amount = amount;
        this.paymentId = paymentId;
        this.transactionId = transactionId;
        this.status = "approved";
        this.items = items == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
    }

    public int getOrderId() {
        return orderId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getPlayerUniqueId() {
        return playerUniqueId;
    }

    public Player getPlayer() {
        if (Bukkit.getServer() == null) return null;
        if (playerUniqueId != null) {
            Player byUuid = Bukkit.getPlayer(playerUniqueId);
            if (byUuid != null) return byUuid;
        }
        return playerName != null ? Bukkit.getPlayerExact(playerName) : null;
    }

    public String getProduct() {
        return product;
    }

    public double getAmount() {
        return amount;
    }

    public double getPrice() {
        return amount;
    }

    public BigDecimal getPriceDecimal() {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getStatus() {
        return status;
    }

    /** Immutable snapshot of the products delivered by this payment. */
    public List<OrderItem> getItems() {
        return items;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
