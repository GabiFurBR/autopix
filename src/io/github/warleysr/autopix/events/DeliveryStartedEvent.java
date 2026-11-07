package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import io.github.warleysr.autopix.api.DeliveryStatus;
import io.github.warleysr.autopix.domain.OrderItem;

public class DeliveryStartedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String deliveryId;
    private final int orderId;
    private final int orderItemId;
    private final UUID playerUuid;
    private final String deliveryType;
    private final String handler;
    private final OrderItem orderItem;
    private final String claimToken;
    private final DeliveryStatus status;

    public DeliveryStartedEvent(String deliveryId, int orderId, int orderItemId, UUID playerUuid, String handler) {
        this(deliveryId, orderId, orderItemId, playerUuid, handler, handler, null, null);
    }

    public DeliveryStartedEvent(String deliveryId, int orderId, int orderItemId, UUID playerUuid,
                                String deliveryType, String handler, OrderItem orderItem) {
        this(deliveryId, orderId, orderItemId, playerUuid, deliveryType, handler, orderItem, null);
    }

    public DeliveryStartedEvent(String deliveryId, int orderId, int orderItemId, UUID playerUuid,
                                String deliveryType, String handler, OrderItem orderItem, String claimToken) {
        super(false);
        this.deliveryId = deliveryId;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.playerUuid = playerUuid;
        this.deliveryType = deliveryType != null ? deliveryType : "autopix";
        this.handler = handler != null ? handler : this.deliveryType;
        this.orderItem = orderItem;
        this.claimToken = claimToken;
        this.status = DeliveryStatus.PROCESSING;
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

    public int getItemIndex() {
        return orderItemId;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Player getPlayer() {
        return (playerUuid != null && Bukkit.getServer() != null) ? Bukkit.getPlayer(playerUuid) : null;
    }

    public String getDeliveryType() {
        return deliveryType;
    }

    public String getHandler() {
        return handler;
    }

    public OrderItem getOrderItem() {
        return orderItem;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
