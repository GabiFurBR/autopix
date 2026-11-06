package io.github.warleysr.autopix;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;

public class MSG {

    private static final Map<String, String> MESSAGES = new ConcurrentHashMap<>();

    static {
        loadFallbackDefaults();
    }

    private static void loadFallbackDefaults() {
        MESSAGES.put("in-game", "\u00a7b[AutoPix] \u00a7cEste comando so pode ser usado dentro do jogo.");
        MESSAGES.put("sem-permissao", "\u00a7b[AutoPix] \u00a7cVoce nao possui permissao!");
        MESSAGES.put("erro-sql", "\u00a7b[AutoPix] \u00a7cErro ao iniciar conexao ao banco. Verifique o erro: {mensagem}");
        MESSAGES.put("erro", "\u00a7b[AutoPix] \u00a7cOcorreu um erro. Tente novamente mais tarde.");
        MESSAGES.put("aguarde", "\u00a7b[AutoPix] \u00a7cAguarde mais \u00a7f{tempo} \u00a7csegundos para executar essa acao novamente.");
        MESSAGES.put("db-invalido", "\u00a7b[AutoPix] \u00a7cO tipo de banco de dados informado nao e valido.");
        MESSAGES.put("processando-pedido", "\u00a7b[AutoPix] \u00a7eSeu pedido ja esta sendo gerado. Aguarde alguns instantes...");
        MESSAGES.put("titulo-qr", "\u00a7a\u00a7lScaneie o c\u00f3digo \u00a7b\u00a7lQR");
        MESSAGES.put("subtitulo-qr", "\u00a77\u00a7le pague pelo \u00a7c\u00a7lPIX\u00a77\u00a7l. Use \u00a7f\u00a7l/pix info");
        MESSAGES.put("titulo-aprovado", "\u00a7a\u00a7lPAGAMENTO APROVADO!");
        MESSAGES.put("subtitulo-aprovado", "\u00a7fProduto \u00a7e{produto} \u00a7fentregue com sucesso!");
        MESSAGES.put("titulo-rejeitado", "\u00a7c\u00a7lPAGAMENTO NAO APROVADO");
        MESSAGES.put("subtitulo-rejeitado", "\u00a77Verifique o status do seu pedido em \u00a7f/pix lista");
        MESSAGES.put("titulo-expirado", "\u00a7c\u00a7lPEDIDO EXPIRADO");
        MESSAGES.put("subtitulo-expirado", "\u00a77O tempo para pagamento do pedido \u00a7f#{id} \u00a77acabou.");
        MESSAGES.put("actionbar-gerando", "\u00a7e\u23f3 Gerando cobran\u00e7a PIX para \u00a7f{produto}\u00a7e...");
        MESSAGES.put("actionbar-pendente", "\u00a7e\u23f3 PIX pendente (\u00a7f{produto}\u00a7e) - Tempo restante: \u00a7f{tempo} min");
        MESSAGES.put("actionbar-aprovado", "\u00a7a\u2714 Pagamento do produto \u00a7f{produto} \u00a7aaprovado!");
        MESSAGES.put("actionbar-cancelado", "\u00a7c\u2716 Pedido PIX cancelado.");
        MESSAGES.put("actionbar-expirado", "\u00a7c\u23f0 Seu pedido PIX expirou por falta de pagamento.");
        MESSAGES.put("pagamento-aprovado-chat", "\u00a7b[AutoPix] \u00a7aSeu pagamento do produto \u00a7f{produto} \u00a7a(\u00a7fR${preco}\u00a7a) foi aprovado!");
        MESSAGES.put("pagamento-rejeitado-chat", "\u00a7b[AutoPix] \u00a7cSeu pagamento do pedido \u00a7f#{id} \u00a7cn\u00e3o foi aprovado ({status}).");
        MESSAGES.put("pedido-expirado-chat", "\u00a7b[AutoPix] \u00a7cSeu pedido \u00a7f#{id} \u00a7c(\u00a7f{produto}\u00a7c) expirou ap\u00f3s \u00a7f{tempo} \u00a7cminutos sem pagamento.");
        MESSAGES.put("pedido-recuperado-join", "\u00a7b[AutoPix] \u00a7eVoc\u00ea possui um pedido PIX pendente (\u00a7f{produto}\u00a7e). Restam \u00a7f{tempo} min \u00a7epara pagar ou use \u00a7f/pix cancelar\u00a7e.");
        MESSAGES.put("ajuda-autopix",
                "\u00a77=========== \u00a7bAutoPix \u00a77===========\n"
                + "\u00a7b/comprarpix \u00a7f- \u00a77Abrir loja de produtos por PIX\n"
                + "\u00a7b/pix info \u00a7f- \u00a77Ver instrucoes de validacao\n"
                + "\u00a7b/pix lista \u00a7f- \u00a77Ver suas transacoes criadas\n"
                + "\u00a7b/pix historico \u00a7f- \u00a77Abrir menu visual de pedidos\n"
                + "\u00a7b/pix cancelar \u00a7f- \u00a77Cancelar pedido pendente atual\n"
                + "\u00a7b/pix top \u00a7f- \u00a77Ver ranking de compradores\n"
                + "\u00a7b/pix validar <Codigo> \u00a77- Validar uma transacao por PIX\n ");
        MESSAGES.put("ajuda-lista", "\u00a7b[AutoPix] \u00a7cUse \u00a7f/ap lista <Jogador> \u00a7c.");
        MESSAGES.put("sem-ordens", "\u00a7b[AutoPix] \u00a7cNenhuma ordem PIX encontrada. Use \u00a7f/comprarpix\u00a7c.");
        MESSAGES.put("cabecalho", "\u00a77========== \u00a7bSuas ordens por PIX \u00a77===========");
        MESSAGES.put("corpo", "\u00a77[\u00a7c{id}\u00a77] {data} - \u00a7f{produto} (\u00a7cR${preco}\u00a7f) \u00a77- {status}");
        MESSAGES.put("sem-doadores", "\u00a7b[AutoPix] \u00a7cAinda nao tivemos nenhum doador. Seja o primeiro!");
        MESSAGES.put("cabecalho-top", "\u00a77========== \u00a7bTop Doadores por PIX \u00a77===========");
        MESSAGES.put("corpo-top", "\u00a7a{doador} \u00a7f- Total: \u00a7cR${total}");
        MESSAGES.put("ajuda-validar", "\u00a7b[AutoPix] \u00a7cUse \u00a7f/ap validar <ID do PIX>\u00a7c.");
        MESSAGES.put("pix-invalido", "\u00a7b[AutoPix] \u00a7cO codigo informado nao e um PIX valido.");
        MESSAGES.put("ja-validado", "\u00a7b[AutoPix] \u00a7cEste PIX ja foi validado antes.");
        MESSAGES.put("erro-validar", "\u00a7b[AutoPix] \u00a7cOcorreu um erro ao validar. Tente novamente mais tarde.");
        MESSAGES.put("pix-nao-validado", "\u00a7b[AutoPix] \u00a7cNao foi possivel validar o PIX. Verifique o codigo e tente novamente.");
        MESSAGES.put("mao-vazia", "\u00a7b[AutoPix] \u00a7cVoce precisa estar com a mao vazia para receber o item.");
        MESSAGES.put("validando", "\u00a7b[AutoPix] \u00a7aValidando seu PIX. Aguarde um pouco.");
        MESSAGES.put("reload-executado", "\u00a7b[AutoPix] \u00a7aReload executado com sucesso.");
        MESSAGES.put("criando-mapa", "\u00a7b[AutoPix] \u00a7aCriando mapa de pagamento. Aguarde...");
        MESSAGES.put("inserir-cupom", "\n\u00a7b[AutoPix] \u00a7aDigite no chat o cupom a ser aplicado\n\u00a7b[AutoPix] \u00a7aou digite \"\u00a7fcancelar\u00a7a\" para desistir da compra.\n ");
        MESSAGES.put("cupom-invalido", "\u00a7b[AutoPix] \u00a7cCupom invalido informado. Tente novamente.");
        MESSAGES.put("cupom-nao-aplicavel", "\u00a7b[AutoPix] \u00a7cEste cupom nao pode ser aplicado neste produto.");
        MESSAGES.put("cupom-expirado", "\u00a7b[AutoPix] \u00a7cO cupom informado expirou.");
        MESSAGES.put("cupom-aplicado", "\n\u00a7b[AutoPix] \u00a7aCupom de desconto aplicado com sucesso.\n\u00a7b[AutoPix] \u00a7aVoce ganhou \u00a7f{porcentagem}% \u00a7ade desconto e\n\u00a7b[AutoPix] \u00a7apagara \u00a7fR${valor} \u00a7ano produto \u00a7f{produto}\u00a7a.\n ");
        MESSAGES.put("compra-cancelada", "\u00a7b[AutoPix] \u00a7cCompra cancelada.");
        MESSAGES.put("sem-pedido-pendente", "\u00a7b[AutoPix] \u00a7cSem pedido pendente para cancelar.");
        MESSAGES.put("erro-cancelar", "\u00a7b[AutoPix] \u00a7cErro ao cancelar pedido. Tente novamente.");
        MESSAGES.put("cancelado", "\u00a7b[AutoPix] \u00a7aPedido cancelado com sucesso.");
        MESSAGES.put("cancelar-primeiro", "\u00a7b[AutoPix] \u00a7cVoce gerou um pedido e n\u00e3o pagou.\n\u00a7b[AutoPix] \u00a7cPague-o ou use \u00a7f/pix cancelar \u00a7cpara\n\u00a7b[AutoPix] \u00a7ccancelar antes de gerar outro.");
    }

    protected static void loadMessages(AutoPix ap) {
        loadFallbackDefaults();
        if (ap == null) return;

        try (InputStream defStream = ap.getResource("mensagens.yml")) {
            if (defStream != null) {
                FileConfiguration defCfg = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                readConfigIntoMap(defCfg);
            }
        } catch (Exception ignored) {
        }

        File msgFile = new File(ap.getDataFolder(), "mensagens.yml");
        if (!msgFile.exists()) {
            ap.saveResource("mensagens.yml", false);
        }

        FileConfiguration msg = YamlConfiguration.loadConfiguration(msgFile);
        readConfigIntoMap(msg);
    }

    private static void readConfigIntoMap(FileConfiguration msg) {
        for (String key : msg.getKeys(false)) {
            if (msg.isList(key)) {
                List<String> lines = msg.getStringList(key);
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < lines.size(); i++) {
                    builder.append(translateColors(lines.get(i)));
                    if (i < lines.size() - 1) {
                        builder.append("\n");
                    }
                }
                MESSAGES.put(key, builder.toString());
            } else {
                String raw = msg.getString(key);
                if (raw != null) {
                    MESSAGES.put(key, translateColors(raw));
                }
            }
        }
    }

    public static void sendMessage(CommandSender sender, String key) {
        sendMessage(sender, key, new String[0]);
    }

    public static void sendMessage(CommandSender sender, String key, String... replacements) {
        if (sender == null || key == null) return;
        final String formatted = formatMessage(key, replacements);
        if (formatted == null || formatted.isEmpty()) return;

        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            sender.sendMessage(formatted);
        } else {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(formatted));
            }
        }
    }

    public static void sendActionBar(Player player, String key, String... replacements) {
        if (player == null || key == null || Bukkit.getServer() == null) return;
        final String formatted = formatMessage(key, replacements);
        if (formatted == null || formatted.trim().isEmpty()) return;

        Runnable action = () -> {
            if (!player.isOnline()) return;
            try {
                Component component = LegacyComponentSerializer.legacySection().deserialize(formatted);
                player.sendActionBar(component);
            } catch (Throwable ignored) {
            }
        };

        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, action);
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static void sendTitle(Player player, String titleKey, String subtitleKey,
                                 int fadeIn, int stay, int fadeOut, String... replacements) {
        if (player == null || Bukkit.getServer() == null) return;
        final String title = titleKey != null ? formatMessage(titleKey, replacements) : "";
        final String subtitle = subtitleKey != null ? formatMessage(subtitleKey, replacements) : "";

        Runnable action = () -> {
            if (!player.isOnline()) return;
            try {
                player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
            } catch (Throwable e) {
                if (!title.isEmpty()) player.sendMessage(title);
                if (!subtitle.isEmpty()) player.sendMessage(subtitle);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, action);
            }
        }
    }

    public static String getMessage(String key) {
        return MESSAGES.getOrDefault(key, "");
    }

    public static String formatMessage(String key, String... replacements) {
        String message = getMessage(key);
        if (message == null || replacements == null) return message != null ? message : "";
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String target = replacements[i];
            String value = replacements[i + 1];
            if (target != null && value != null) {
                message = message.replace(target, value);
            }
        }
        return message;
    }

    public static String colorize(String text) {
        if (text == null) return "";
        return translateColors(text);
    }

    private static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            return true;
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig");
                return true;
            } catch (ClassNotFoundException ex) {
                return false;
            }
        }
    }

    private static String translateColors(String message) {
        if (message == null) return "";
        if (!isPaper() || !containsMiniMessageTag(message)) {
            return ChatColor.translateAlternateColorCodes('&', message);
        }
        try {
            String converted = legacyToMiniMessage(message);
            Component component = MiniMessage.miniMessage().deserialize(converted);
            return LegacyComponentSerializer.legacySection().serialize(component);
        } catch (Exception e) {
            return ChatColor.translateAlternateColorCodes('&', message);
        }
    }

    private static boolean containsMiniMessageTag(String text) {
        return text.contains("<gradient") || text.contains("<rainbow") || text.contains("<#")
                || text.contains("<color:");
    }

    private static String legacyToMiniMessage(String legacyText) {
        return legacyText
            .replace("&0", "<black>")
            .replace("&1", "<dark_blue>")
            .replace("&2", "<dark_green>")
            .replace("&3", "<dark_aqua>")
            .replace("&4", "<dark_red>")
            .replace("&5", "<dark_purple>")
            .replace("&6", "<gold>")
            .replace("&7", "<gray>")
            .replace("&8", "<dark_gray>")
            .replace("&9", "<blue>")
            .replace("&a", "<green>")
            .replace("&b", "<aqua>")
            .replace("&c", "<red>")
            .replace("&d", "<light_purple>")
            .replace("&e", "<yellow>")
            .replace("&f", "<white>")
            .replace("&l", "<bold>")
            .replace("&m", "<strikethrough>")
            .replace("&n", "<underlined>")
            .replace("&o", "<italic>")
            .replace("&r", "<reset>");
    }
}
