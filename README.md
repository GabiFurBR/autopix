# AutoPix Neon

Community fork of [AutoPix](https://github.com/warleysr/autopix), originally created by [WarleySR](https://github.com/warleysr). This fork keeps the original PIX and Mercado Pago workflow and adds PostgreSQL/Neon support, pooled JDBC connections and a reproducible build.

## Current status

This is a development build. It has not yet been validated against a live Neon database or a production Minecraft server. Do not use it to process real payments until the test checklist below has passed.

## Features

- Original AutoPix menus, QR Code PIX flow and Mercado Pago integration.
- PostgreSQL support, including Neon.
- MySQL and SQLite modes retained.
- HikariCP connection pool with configurable limits and idle connection retirement.
- Existing PlaceholderAPI expansion retained.
- Maven build targeting Paper 26.3 / Java 25.

## Requirements

- Paper/Purpur 26.3 and Java 25 for this build.
- Maven 3.9+ to compile locally.
- PlaceholderAPI is optional at runtime; it is used for the existing AutoPix expansion.
- A Mercado Pago access token and PIX configuration for payment processing.

## Build

Run `mvn clean package`.

The shaded plugin JAR is generated in `target/autopix-neon-1.1.0-SNAPSHOT.jar`.

GitHub Actions also builds the plugin on pushes and pull requests to `master` and `develop`. Download the artifact from the workflow run; this repository does not commit compiled JARs.

## Neon / PostgreSQL configuration

1. In the Neon Console, open **Connect** and select the branch, database and role you intend to use.
2. Use the pooled endpoint hostname (the hostname contains `-pooler`) for a long-running game server with concurrent database operations.
3. Copy the hostname, database, role and password into the local `plugins/AutoPix/config.yml` file.
4. Keep `sslmode: require` and `channel-binding: require`.
5. Use a dedicated Neon database for AutoPix rather than sharing application tables with the website.

Example:

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
  pool:
    maximum-size: 3
    minimum-idle: 0
    connection-timeout: 10000
    idle-timeout: 60000
    max-lifetime: 300000
```

Never commit the real config.yml, Mercado Pago token, PIX key or database password. The repository's config.yml contains placeholders only. Rotate any credential that is accidentally published.

### Database modes

- `postgresql` (or `postgres`): PostgreSQL and Neon. Uses SSL and channel binding by default.
- `mysql`: MySQL using the configured host, port, database, user and password.
- `sqlite`: local `autopix.db` file in the plugin folder.

The plugin creates the original `autopix_orders` and `autopix_pix_data` tables automatically. Existing MySQL/SQLite schemas are not migrated or copied into PostgreSQL. Use a fresh, dedicated Neon database for the first test.

## Test checklist before production

- Confirm startup creates both tables in the intended Neon database.
- Create a test order and confirm the generated order ID is correct.
- Generate a PIX payment and confirm its payment ID and QR data are stored.
- Pay with a controlled low-value test and confirm the exact amount is checked.
- Confirm the configured product commands run once after an approved payment.
- Test pending, rejected, cancelled and expired payments.
- Restart the server with a pending payment and verify that it remains recoverable.
- Stop the database briefly, restore connectivity and verify that the pool recovers.
- Confirm `/pix lista`, manual validation and top-donor placeholders still work.
- Test the plugin with MySQL and SQLite if those modes are still required.

## Integration roadmap

The original product configuration executes console commands after a successful payment. This already supports command-driven rewards from LuckPerms, Essentials, Oraxen and ExecutableItems without adding hard dependencies. PlaceholderAPI support is already present in the original project.

The most useful future addition is a small optional Skript bridge that exposes Bukkit events for order creation, payment approval, rejection and cancellation. DiscordSRV or DiSky can then react to those events without becoming required dependencies. FancyHolograms, TAB and other PlaceholderAPI-compatible plugins can consume AutoPix statistics. Avoid hard hooks to unrelated plugins unless a concrete feature needs them.

## Credits and license

This repository is a community fork. The original project and its license notices are preserved. Thanks to WarleySR for AutoPix and to the QRCodeMap contributors referenced by the original project. See [LICENSE](LICENSE).
