package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PaymentRejectedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int orderId;
    private final String playerName;
    private final UUID playerUniqueId;
    private final String product;
    private final double amount;
    private final double paidAmount;
    private final String paymentId;
    private final String status;
    private final String reason;

    public PaymentRejectedEvent(int orderId, String playerName, UUID playerUniqueId,
                                String product, double amount, double paidAmount,
                                String paymentId, String status, String reason) {
        super(false);
        this.orderId = orderId;
        this.playerName = playerName;
        this.playerUniqueId = playerUniqueId;
        this.product = product;
        this.amount = amount;
        this.paidAmount = paidAmount;
        this.paymentId = paymentId;
        this.status = status != null ? status : "rejected";
        this.reason = reason != null ? reason : "rejected";
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

    public double getPaidAmount() {
        return paidAmount;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
