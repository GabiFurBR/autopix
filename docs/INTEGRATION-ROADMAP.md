# Integration roadmap

## Priority 1: Skript bridge

Recommended first-class optional integration.

Expose Bukkit events with immutable order/payment data:
- OrderCreatedEvent
- PaymentApprovedEvent
- PaymentRejectedEvent
- PaymentCancelledEvent

Events must be fired on the server thread. They must not expose Mercado Pago access tokens, PIX keys, database credentials or raw QR payloads. Include player name/UUID when reliably available, product key, order ID, amount, payment ID and final status.

This lets Skript call DiSky or DiscordSRV features, update NeoRankUP variables, play custom sounds or dispatch server-specific reward commands without making AutoPix depend on Skript.

## Priority 2: PlaceholderAPI

Already integrated in the original project through the AutoPix expansion and top-donor cache.

Future placeholders:
- total approved revenue
- approved order count
- pending order count
- a player's approved spending
- a player's approved order count
- top donor name and value by position

Update cached aggregates asynchronously; placeholder requests must never run SQL queries synchronously.

FancyHolograms, TAB and compatible server-list plugins can consume these values through PlaceholderAPI without direct dependencies.

## Priority 3: Discord notifications

Prefer the event-driven route through Skript first. This avoids hard dependencies and lets the server owner choose DiscordSRV or DiSky.

Do not assume DiscordSRV and DiSky can safely coexist in the same runtime: their JDA dependencies may conflict. Verify the exact installed versions before selecting a Discord API integration. A neutral webhook sender is another option.

## Existing command-based integrations (no bridge needed yet)

The product config already dispatches console commands. This is generally enough for:
- LuckPerms: grant groups and permissions.
- Essentials: issue economy, item or utility commands.
- Oraxen: grant configured custom items through its commands.
- ExecutableItems, ExecutableBlocks and ExecutableEvents: invoke their documented reward commands.
- Skript: call a custom Skript command as console.

Keep these configuration-driven unless a missing feature proves a direct API hook is necessary.

## Low priority or no direct bridge

- AdvancedBanZ, Maintenance, nLogin: account access/moderation is not part of payment persistence.
- Backuper, InventoryRollbackPlus: backup and rollback concerns; payment records should be immutable to these systems.
- Chunky, FAWE, WorldGuard, Multiverse-Core, Parkour, GSit: unrelated to PIX order processing.
- ConsoleSpamFixReborn, PlugManX, ProtocolLib, PacketEvents, ViaVersion, ViaBackwards, ViaRewind, voicechat, Votifier: no core payment feature needs a direct integration.
- FancyHolograms: use PlaceholderAPI first; direct API integration is redundant for basic statistics.
- AdvancedServerList: use PlaceholderAPI if supported by the installed version; verify that before adding an integration.
- OpenRadPlugin, SCore, SkDialogs, ItemEdit, ItemTag, java_site, SkinsRestorer and less-specific components: wait for a concrete use case and verify the exact plugin/API version before adding a dependency.

## Integration design rules

- All integrations must be optional and detected safely.
- Payment validation must not depend on another gameplay plugin.
- Never execute Bukkit API calls from SQL/network worker threads.
- Never treat a command callback as proof that a payment was approved; only the Mercado Pago validation path can establish approval.
- Never store or log access tokens, PIX keys, database passwords or QR payloads.
