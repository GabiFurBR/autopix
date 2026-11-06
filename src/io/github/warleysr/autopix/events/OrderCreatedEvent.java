package io.github.warleysr.autopix.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.github.warleysr.autopix.domain.OrderItem;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class OrderCreatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int orderId;
    private final String playerName;
    private final UUID playerUniqueId;
    private final String product;
    private final double amount;
    private final String paymentId;
    private final String status;
    private final long createdAt;
    private final String qrCode;
    private final List<OrderItem> items;

    public OrderCreatedEvent(int orderId, String playerName, UUID playerUniqueId,
                             String product, double amount, String paymentId,
                             String status, long createdAt) {
        this(orderId, playerName, playerUniqueId, product, amount, paymentId,
                status, createdAt, null, Collections.emptyList());
    }

    public OrderCreatedEvent(int orderId, String playerName, UUID playerUniqueId,
                             String product, double amount, String paymentId,
                             String status, long createdAt, String qrCode, List<OrderItem> items) {
        super(false);
        this.orderId = orderId;
        this.playerName = playerName;
        this.playerUniqueId = playerUniqueId;
        this.product = product;
        this.amount = amount;
        this.paymentId = paymentId;
        this.status = status != null ? status : "pending";
        this.createdAt = createdAt;
        this.qrCode = qrCode;
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

    public String getPaymentId() {
        return paymentId;
    }

    public String getStatus() {
        return status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** Raw PIX copy-and-paste payload. May be null when manual mode is used. */
    public String getQrCode() {
        return qrCode;
    }

    /** Immutable snapshot of the products in this order. */
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
