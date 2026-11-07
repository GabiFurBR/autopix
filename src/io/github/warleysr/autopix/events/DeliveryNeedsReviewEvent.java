package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import io.github.warleysr.autopix.api.DeliveryStatus;

public class DeliveryNeedsReviewEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String deliveryId;
    private final int orderId;
    private final int orderItemId;
    private final UUID playerUuid;
    private final String handler;
    private final DeliveryStatus status;
    private final String reason;

    public DeliveryNeedsReviewEvent(String deliveryId, int orderId, int orderItemId, UUID playerUuid, String handler, String reason) {
        super(false);
        this.deliveryId = deliveryId;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.playerUuid = playerUuid;
        this.handler = handler;
        this.status = DeliveryStatus.NEEDS_REVIEW;
        this.reason = reason;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public int getOrderId() {
        return orderId;
    }

    public int getOrderItemId() {
        return orderItemId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Player getPlayer() {
        return (playerUuid != null && Bukkit.getServer() != null) ? Bukkit.getPlayer(playerUuid) : null;
    }

    public String getHandler() {
        return handler;
    }

    public DeliveryStatus getStatus() {
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
