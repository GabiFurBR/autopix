# Integration Roadmap & Guide

## 1. Skript Bridge (Implemented)

AutoPix dispatches immutable Bukkit events on the main server thread under `io.github.warleysr.autopix.events`:
- `OrderCreatedEvent`
- `PaymentApprovedEvent`
- `PaymentRejectedEvent`
- `PaymentCancelledEvent`

Events never expose Mercado Pago access tokens, PIX keys, database credentials or raw QR payloads. They provide:
- `getOrderId()` (`int`)
- `getPlayer()` (`Player`, if online) and `getPlayerName()` (`String`)
- `getPlayerUniqueId()` (`UUID`, if available)
- `getProduct()` (`String`)
- `getAmount()` (`double`)
- `getPaymentId()` (`String`)
- `getStatus()` (`String`)

### Example Usage in Skript (`skript-reflect`)

```skript
import:
    io.github.warleysr.autopix.events.PaymentApprovedEvent
    io.github.warleysr.autopix.events.OrderCreatedEvent

on PaymentApprovedEvent:
    set {_player} to event.getPlayerName()
    set {_produto} to event.getProduct()
    set {_valor} to event.getAmount()
    set {_pedido} to event.getOrderId()
    broadcast "&b[AutoPix] &f%{_player}% &aadquiriu &e%{_produto}% &apor &aR$ %{_valor}%&a!"
```

## 2. PlaceholderAPI (Implemented)

All placeholders are served from an asynchronous cache (`OrderManager.refreshStatsCacheNow`) and never execute SQL queries on the placeholder request thread:
- `%autopix_total_arrecadado%` / `%autopix_total_arrecadado_formatado%`
- `%autopix_pagamentos_aprovados%`
- `%autopix_pedidos_pendentes%`
- `%autopix_jogador_gasto%` / `%autopix_jogador_gasto_formatado%`
- `%autopix_jogador_compras%`
- `%autopix_top_<pos>%`
- `%autopix_top_<pos>_nome%`
- `%autopix_top_<pos>_valor%` / `%autopix_top_<pos>_valor_formatado%`
- `%autopix_top_<pos>_compras%`

FancyHolograms, TAB and AdvancedServerList can consume these placeholders directly without custom plugin hooks.

## 3. Discord Notifications (Implemented)

Two non-conflicting options are available:
1. **Event-driven via Skript**: Listen to `PaymentApprovedEvent` in Skript and send messages/embeds through DiSky or DiscordSRV.
2. **Built-in Discord Webhook**: Enable `discord.webhook.ativado: true` and set `discord.webhook.url` in `config.yml` for zero-dependency asynchronous Discord embed notifications.

## 4. Command-Driven Reward Integrations

Product configurations in `config.yml` execute console commands on the main thread after a verified approval:
- **LuckPerms**: `lp user {player} parent addtemp vip 30d`
- **Essentials**: `eco give {player} 10000`
- **Oraxen**: `oraxen give {player} custom_item 1`
- **ExecutableItems / ExecutableBlocks / ExecutableEvents**: invoke their documented console reward commands.
- **Skript**: invoke custom Skript commands from console.
