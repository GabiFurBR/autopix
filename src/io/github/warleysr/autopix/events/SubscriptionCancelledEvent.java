package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class SubscriptionCancelledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String subscriptionId;
    private final UUID playerUuid;
    private final String reason;

    public SubscriptionCancelledEvent(String subscriptionId, UUID playerUuid, String reason) {
        super(false);
        this.subscriptionId = subscriptionId;
        this.playerUuid = playerUuid;
        this.reason = reason;
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
