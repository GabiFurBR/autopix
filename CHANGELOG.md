# Changelog

## 1.1.0-SNAPSHOT

### Financial Security & Database Integrity
- Added PostgreSQL/Neon, MySQL and SQLite connection pooling with HikariCP.
- Configured PostgreSQL/Neon socket timeout, login timeout, TCP keep-alive and startup wakeup retry.
- Unified order and PIX data creation into a single atomic SQL transaction (`createOrderWithPixData`), automatically cancelling the Mercado Pago payment if local database persistence fails.
- Enforced strict payment approval idempotency and non-overlapping validation cycles (`VALIDATION_RUNNING` lock + per-player creation lock).
- Protected `autopix_pix_data` status transitions so `approved` payments can never be overwritten by `cancelled`, and re-checked Mercado Pago before expiring pending orders.
- Fixed manual PIX validation (`MPValidator`) to verify `"status": "approved"` and removed synchronous SQL execution from the main thread.
- Fixed discount calculation rounding (`BigDecimal` scale 2) and cent-exact amount comparison (`toCents`) to prevent false `amount_mismatch` on discounted orders.
- Expanded `autopix_orders` and `autopix_pix_data` column widths (`product VARCHAR(64)`, `pix VARCHAR(64)`, `status VARCHAR(32)`, `qr_code TEXT`) with non-destructive `ALTER TABLE` upgrades for existing tables.
- Sanitized Mercado Pago API logging and JSON payload construction (omitting empty `notification_url` and never logging tokens, PIX keys or raw payer responses).
- Fixed static PIX BRCode payload formatting for keys shorter than 10 characters and enforced UTF-8 in `CRC16`.

### Thread Safety & Paper 26.3 Compatibility
- Moved all `InventoryManager.removeUnpaidMaps`, inventory modifications, sound playback and event dispatching strictly to the main server thread.
- Replaced raw `new Thread(...)` calls, shared `HashMap`/`ArrayList` state and static `SimpleDateFormat` instances with Bukkit async scheduler tasks, `ConcurrentHashMap` and thread-safe `DateTimeFormatter` / `LocalDate`.
- Fixed `ConcurrentModificationException` on player death when removing payment maps from drops.
- Prevented overwriting items in the player's hand when delivering QR Code or success maps.
- Replaced deprecated `Sound.valueOf` with Paper `Registry.SOUNDS` lookup.

### Menus, Sounds & Player Experience
- Modernized confirmation menu with per-player inventory instances, central order summary item, coupon badge and legacy material mapping (`WOOL` + data to modern colored wool).
- Added visual purchase history and live payment status menu (`/pix historico`) with automatic real-time refresh when open.
- Added 9 independent sound configurations (`sons.*`) with volume, pitch, activation toggle and UI rate-limiting.
- Added configurable titles, subtitles, actionbars and pending order recovery on player login.

### Server Integrations & Testing
- Added Bukkit events (`OrderCreatedEvent`, `PaymentApprovedEvent`, `PaymentRejectedEvent`, `PaymentCancelledEvent`) for optional Skript, DiSky and DiscordSRV integration.
- Expanded `AutoPixExpansion` (PlaceholderAPI) with asynchronous cached global, per-player and per-rank placeholders.
- Added optional native Discord Webhook notification (`discord.webhook`) for approved payments.
- Added JUnit 5 automated test suite (`AutoPixAuditTest`) covering database persistence, concurrent idempotency, discount rounding, Mercado Pago parsing, QR generation, PlaceholderAPI cache and Bukkit events.
