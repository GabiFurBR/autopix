# AutoPix Neon

Community fork of [AutoPix](https://github.com/warleysr/autopix), originally created by [WarleySR](https://github.com/warleysr). This fork preserves the original PIX and Mercado Pago workflow while adding first-class PostgreSQL/Neon support, pooled JDBC connections, strict payment idempotency, modernized menus, configurable sound effects, expanded PlaceholderAPI statistics, Bukkit events for Skript, and optional Discord Webhook notifications.

## Features

- **Financial Integrity & Strict Idempotency**:
  - Atomic SQL transaction for order + PIX creation (`autopix_orders` and `autopix_pix_data`), with automatic Mercado Pago payment cancellation if local persistence fails.
  - Strictly idempotent payment approval (`WHERE pix IS NULL` and `WHERE status = 'pending'`) preventing duplicate reward deliveries even under concurrent checks or multiple server instances.
  - Exact cent-level amount validation (`BigDecimal` with `HALF_UP` rounding for discounts) preventing false `amount_mismatch` on coupon purchases.
  - Protected status transitions ensuring approved payments can never be overwritten by expiration or cancellation tasks.
- **Database & Connection Pooling**:
  - PostgreSQL / Neon support (`sslmode`, `channel-binding`, TCP keep-alive, login/socket timeouts and cold-start retry).
  - MySQL and SQLite modes retained.
  - HikariCP connection pool with bounded limits and automatic non-destructive column width upgrades.
- **Modernized Menus & Player Experience**:
  - Per-player confirmation GUI displaying a live order summary (product, original price, applied coupon, final PIX value and payment time limit).
  - Visual purchase history & live status GUI (`/pix historico`) with real-time updates when open, allowing players to restore a pending QR map or cancel a pending order.
  - Automatic legacy material resolution (`WOOL` + data values mapped to modern Paper materials).
  - Safe QR map delivery that never overwrites items held by the player, plus automatic pending order recovery on reconnect.
  - Configurable titles, subtitles and actionbars.
- **Independent Sound Configuration**:
  - 9 individually configurable sound triggers (`abrir-menu`, `selecionar-produto`, `confirmar-compra`, `gerar-qrcode`, `pagamento-aprovado`, `pagamento-rejeitado`, `pagamento-cancelado`, `erro-conexao`, `pedido-expirado`) resolved via Paper's sound registry on the main thread.
- **Server Integrations**:
  - **Skript / Bukkit Events**: `OrderCreatedEvent`, `PaymentApprovedEvent`, `PaymentRejectedEvent`, `PaymentCancelledEvent` fired on the main server thread without requiring Skript at compile or runtime.
  - **PlaceholderAPI**: Asynchronous cached placeholders for total revenue, approved count, pending count, per-player spending/order count, and top donor names/values/orders by rank.
  - **Discord Webhook**: Optional native asynchronous webhook notification on payment approval (`discord.webhook`).
- **Build & Runtime**:
  - Reproducible Maven build targeting Paper/Purpur 26.3 and Java 25, with automated JUnit 5 verification suite.

## Requirements

- Paper/Purpur 26.3 and Java 25.
- Maven 3.9+ to compile locally.
- PlaceholderAPI (optional at runtime).
- Skript / skript-reflect (optional at runtime for consuming Bukkit events).
- A Mercado Pago access token and PIX configuration for payment processing.

## Build & Test

Run:

```bash
mvn clean package
```

This executes the JUnit 5 test suite and produces the shaded plugin JAR in `target/autopix-neon-1.1.0-SNAPSHOT.jar`.

## Neon / PostgreSQL Configuration

1. In the Neon Console, open **Connect** and select the branch, database and role you intend to use.
2. Use the pooled endpoint hostname (containing `-pooler`) for a long-running game server with concurrent database operations.
3. Copy the hostname, database, role and password into the local `plugins/AutoPix/config.yml` file.
4. Keep `sslmode: require` and `channel-binding: require`.
5. Use a dedicated Neon database for AutoPix rather than sharing application tables with a website.

```yaml
database:
  type: postgresql
  host: 'ep-example-pooler.region.aws.neon.tech'
  port: 5432
  db: 'autopix'
  user: 'autopix_owner'
  pass: 'CHANGE_ME'
  sslmode: require
  channel-binding: require
  use-ssl: false
  pool:
    maximum-size: 3
    minimum-idle: 0
    connection-timeout: 10000
    idle-timeout: 60000
    max-lifetime: 300000
```

Never commit the real `config.yml`, Mercado Pago token, PIX key or database password.

## Placeholders (PlaceholderAPI)

All placeholders are served from an asynchronous memory cache and never run SQL queries on the calling thread:

| Placeholder | Alias | Description |
| :--- | :--- | :--- |
| `%autopix_total_arrecadado%` | `%autopix_total_revenue%` | Total approved revenue (e.g. `150,00`) |
| `%autopix_total_arrecadado_formatado%` | `%autopix_total_revenue_formatted%` | Formatted total revenue (`R$ 150,00`) |
| `%autopix_pagamentos_aprovados%` | `%autopix_approved_count%` | Total number of approved payments |
| `%autopix_pedidos_pendentes%` | `%autopix_pending_count%` | Current number of pending PIX orders |
| `%autopix_jogador_gasto%` | `%autopix_player_spent%` | Player's total approved spending |
| `%autopix_jogador_gasto_formatado%` | `%autopix_player_spent_formatted%` | Player's formatted spending (`R$ 45,00`) |
| `%autopix_jogador_compras%` | `%autopix_player_orders%` | Player's approved order count |
| `%autopix_top_<pos>%` | — | Formatted top donor line from `mensagens.yml` |
| `%autopix_top_<pos>_nome%` | `%autopix_top_name_<pos>%` | Donor name at position `<pos>` |
| `%autopix_top_<pos>_valor%` | `%autopix_top_value_<pos>%` | Donor total at position `<pos>` |
| `%autopix_top_<pos>_valor_formatado%` | `%autopix_top_value_formatted_<pos>%` | Formatted donor total (`R$ 50,00`) |
| `%autopix_top_<pos>_compras%` | `%autopix_top_orders_<pos>%` | Donor approved order count at `<pos>` |

## Bukkit Events (Skript Bridge)

Package: `io.github.warleysr.autopix.events`

- `OrderCreatedEvent`: `getOrderId()`, `getPlayer()`, `getPlayerName()`, `getPlayerUniqueId()`, `getProduct()`, `getAmount()`, `getPaymentId()`, `getStatus()`, `getCreatedAt()`
- `PaymentApprovedEvent`: `getOrderId()`, `getPlayer()`, `getPlayerName()`, `getPlayerUniqueId()`, `getProduct()`, `getAmount()`, `getPaymentId()`, `getTransactionId()`, `getStatus()`
- `PaymentRejectedEvent`: `getOrderId()`, `getPlayer()`, `getPlayerName()`, `getPlayerUniqueId()`, `getProduct()`, `getAmount()`, `getPaidAmount()`, `getPaymentId()`, `getStatus()`, `getReason()`
- `PaymentCancelledEvent`: `getOrderId()`, `getPlayer()`, `getPlayerName()`, `getPlayerUniqueId()`, `getProduct()`, `getAmount()`, `getPaymentId()`, `getStatus()`, `getReason()`

## Credits and License

This repository is a community fork. The original project and its license notices are preserved. Thanks to WarleySR for AutoPix and to the QRCodeMap contributors referenced by the original project. See [LICENSE](LICENSE).
