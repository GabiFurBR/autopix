package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import io.github.warleysr.autopix.api.Cart;

public class CartUpdatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final Cart cart;
    private final String operation;

    public CartUpdatedEvent(UUID playerUuid, Cart cart, String operation) {
        super(false);
        this.playerUuid = playerUuid;
        this.cart = cart;
        this.operation = operation != null ? operation : "update";
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Player getPlayer() {
        return playerUuid != null ? Bukkit.getPlayer(playerUuid) : null;
    }

    public Cart getCart() {
        return cart;
    }

    public String getOperation() {
        return operation;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
