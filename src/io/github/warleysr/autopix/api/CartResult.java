package io.github.warleysr.autopix.api;

public class CartResult {

    private final boolean successful;
    private final String message;
    private final Cart cart;

    public CartResult(boolean successful, String message, Cart cart) {
        this.successful = successful;
        this.message = message;
        this.cart = cart;
    }

    public static CartResult success(String message, Cart cart) {
        return new CartResult(true, message, cart);
    }

    public static CartResult failure(String message, Cart cart) {
        return new CartResult(false, message, cart);
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getMessage() {
        return message;
    }

    public Cart getCart() {
        return cart;
    }
}
