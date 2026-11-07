package io.github.warleysr.autopix.events;

import java.math.BigDecimal;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class SubscriptionRenewedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String subscriptionId;
    private final UUID playerUuid;
    private final String invoiceId;
    private final BigDecimal amount;

    public SubscriptionRenewedEvent(String subscriptionId, UUID playerUuid, String invoiceId, BigDecimal amount) {
        super(false);
        this.subscriptionId = subscriptionId;
        this.playerUuid = playerUuid;
        this.invoiceId = invoiceId;
        this.amount = amount;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Player getPlayer() {
        return (playerUuid != null && Bukkit.getServer() != null) ? Bukkit.getPlayer(playerUuid) : null;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getAmount() {
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
