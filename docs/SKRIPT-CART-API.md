# AutoPix Cart & Skript API

This document describes the native multi-product cart and the public integration facade introduced in AutoPix 1.2.

## Native cart

Players can open the store with `/comprarpix` or `/pix menu`. Clicking a product adds one unit to the cart. The cart button is added automatically to each configured shop menu.

Open the cart with `/pix carrinho`:
- Left-click an item: add one.
- Right-click an item: remove one.
- Shift + right-click: remove the item line.
- Apply coupon: type the coupon in chat (or `cancelar`).
- Clear cart: remove every line.
- Finalize: create one PIX charge for the cart total.

The native menu and the public API use the same cart state and checkout implementation. A player has one cart shared across the configured shop menus. Cart contents are kept in memory for the active server session; completed orders are persisted.

Coupons use the existing `cupons.<nome>` configuration. The coupon's `itens` list must contain every product currently in the cart. The percentage and validity date are validated before applying the discount.

## Public API

Main class:

```java
io.github.warleysr.autopix.api.AutoPixAPI
```

The following operations that mutate the cart or start a checkout must be called from Bukkit's primary thread:

| Method | Purpose |
| --- | --- |
| `addToCart(Player, String menu, int slot, int quantity)` | Adds the configured product at a zero-based Bukkit inventory slot. |
| `removeFromCart(Player, String itemKey)` | Removes a line by its `CartItem.getKey()`. |
| `changeQuantity(Player, String itemKey, int delta)` | Changes the line quantity. A quantity at or below zero removes it. |
| `setQuantity(Player, String itemKey, int quantity)` | Sets the quantity, or removes the line when zero. |
| `getCartItems(Player)` | Returns an immutable snapshot list of cart lines. |
| `getCartSubtotal(Player)` | Returns the subtotal before discounts. |
| `getCartTotal(Player)` | Returns the final total after cart discount. |
| `getCartItemCount(Player)` | Returns the sum of line quantities. |
| `getCartCoupon(Player)` | Returns the applied coupon name, or null. |
| `applyCartDiscount(Player, String, int)` | Applies a 1–99% discount. Validate coupon eligibility in your script before calling it. |
| `clearCart(Player)` | Empties the cart. |
| `openShopMenu(Player)` | Opens the configured native shop. |
| `openCartMenu(Player)` | Opens the configured native cart GUI. |
| `checkout(Player)` | Starts checkout without giving a map; listen to `OrderCreatedEvent` to retrieve the QR payload. |
| `checkout(Player, boolean giveMap)` | Starts checkout and optionally gives the QR map. |

All network and database operations during checkout run asynchronously. `checkout` returns whether the checkout was accepted for processing, not whether Mercado Pago has approved the payment.

### CartItem

A cart line exposes:
- `getKey()`: stable key in the form `menu:slot`.
- `getMenu()`, `getSlot()`: configured shop location.
- `getProduct()`, `getQuantity()`.
- `getUnitPrice()`, `getLineTotal()`.
- `getPreCommands()`, `getCommands()`: immutable command snapshots.

### Events

Events are Bukkit events fired on the server's main thread. Skript can consume them through `skript-reflect` without AutoPix depending on Skript at compile time.

**OrderCreatedEvent**
- `getOrderId()`, `getPlayer()`, `getPlayerName()`, `getPlayerUniqueId()`
- `getProduct()`: summary stored for the order (for a multi-product order, e.g. `Carrinho (3 itens)`)
- `getAmount()`, `getPaymentId()`, `getStatus()`, `getCreatedAt()`
- `getQrCode()`: PIX copy-and-paste payload. Null only when unavailable.
- `getItems()`: immutable list of `OrderItem` snapshots.

**PaymentApprovedEvent**
- Existing order/player/product/amount/payment/transaction getters.
- `getItems()`: immutable list of items delivered for the approved cart order.

**CheckoutFailedEvent**
- `getPlayerName()`, `getPlayerUniqueId()`, `getReason()`.
- Reasons currently include `pending_order`, `mercadopago_error`, `empty_qr`, `database_error`, and `checkout_error`.

### OrderItem

Each persisted item exposes:
- `getProduct()`, `getQuantity()`, `getUnitPrice()`, `getLineTotal()`.
- `getPreCommands()`, `getCommands()`.

The order snapshot is saved in the same database transaction as the order and PIX metadata. Delivery recovery reads the saved snapshot, not the current shop configuration. This protects pending/approved purchases from later changes to product names, prices, or reward commands.

Command placeholders are:
- `{player}`: buyer name.
- `{produto}`: product name for this line.
- `{quantidade}`: purchased quantity.
- `{preco}`: line total formatted with a decimal comma.

Each command is executed once per configured command entry. For commands that need to grant multiple units, use `{quantidade}` in the command itself. For example, a reward command that ignores quantity will still run once, even if the cart line quantity is greater than one.

## Safety notes

- One active order creation lock per player prevents concurrent checkouts.
- Each unchanged cart revision reuses the same Mercado Pago idempotency key. Changing an item, quantity, or discount creates a new key; this helps safe retries after uncertain network responses.
- The full cart amount is charged as one Mercado Pago PIX payment.
- PIX creation and order + item snapshot persistence remain separate operations; if persistence fails, AutoPix attempts to cancel the orphan Mercado Pago payment.
- Payment approval remains amount-checked and idempotent through the existing delivery outbox.
- If reward execution is interrupted or becomes uncertain, the outbox moves to `needs_review` instead of automatically replaying commands and risking duplicate rewards.
- Cart contents are session memory. Completed order snapshots and payment records are database-persistent.
