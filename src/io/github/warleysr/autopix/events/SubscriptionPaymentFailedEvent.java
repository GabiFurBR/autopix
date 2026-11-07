package io.github.warleysr.autopix.events;

import java.math.BigDecimal;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class SubscriptionPaymentFailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String subscriptionId;
    private final UUID playerUuid;
    private final String invoiceId;
    private final BigDecimal amount;
    private final String reason;

    public SubscriptionPaymentFailedEvent(String subscriptionId, UUID playerUuid, BigDecimal amount, String reason) {
        this(subscriptionId, playerUuid, null, amount, reason);
    }

    public SubscriptionPaymentFailedEvent(String subscriptionId, UUID playerUuid, String invoiceId,
                                          BigDecimal amount, String reason) {
        super(false);
        this.subscriptionId = subscriptionId;
        this.playerUuid = playerUuid;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.reason = reason;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public Player getPlayer() {
        return (playerUuid != null && Bukkit.getServer() != null) ? Bukkit.getPlayer(playerUuid) : null;
    }

    public BigDecimal getAmount() {
        return amount;
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
