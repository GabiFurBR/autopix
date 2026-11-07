package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PaymentExpiredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String paymentId;
    private final int orderId;
    private final UUID playerUuid;
    private final double amount;

    public PaymentExpiredEvent(String paymentId, int orderId, UUID playerUuid, double amount) {
        super(false);
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.playerUuid = playerUuid;
        this.amount = amount;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public int getOrderId() {
        return orderId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Player getPlayer() {
        return playerUuid != null ? Bukkit.getPlayer(playerUuid) : null;
    }

    public double getAmount() {
        return amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
