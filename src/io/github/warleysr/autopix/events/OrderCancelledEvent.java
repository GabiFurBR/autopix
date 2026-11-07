package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class OrderCancelledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int orderId;
    private final UUID playerUuid;
    private final String playerName;
    private final double total;
    private final String reason;

    public OrderCancelledEvent(int orderId, UUID playerUuid, String playerName, double total, String reason) {
        super(false);
        this.orderId = orderId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.total = total;
        this.reason = reason != null ? reason : "cancelled";
    }

    public int getOrderId() {
        return orderId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Player getPlayer() {
        if (playerUuid != null) {
            Player p = Bukkit.getPlayer(playerUuid);
            if (p != null) return p;
        }
        return playerName != null ? Bukkit.getPlayerExact(playerName) : null;
    }

    public double getTotal() {
        return total;
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
