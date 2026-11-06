package io.github.warleysr.autopix.events;

import java.util.UUID;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the server thread when a cart checkout cannot be completed.
 */
public final class CheckoutFailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final UUID playerUniqueId;
    private final String reason;

    public CheckoutFailedEvent(String playerName, UUID playerUniqueId, String reason) {
        super(false);
        this.playerName = playerName;
        this.playerUniqueId = playerUniqueId;
        this.reason = reason == null ? "unknown" : reason;
    }

    public String getPlayerName() { return playerName; }
    public UUID getPlayerUniqueId() { return playerUniqueId; }
    public String getReason() { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
