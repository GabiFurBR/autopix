package io.github.warleysr.autopix;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public final class SoundManager {

    public static final String OPEN_MENU = "abrir-menu";
    public static final String SELECT_PRODUCT = "selecionar-produto";
    public static final String CONFIRM_PURCHASE = "confirmar-compra";
    public static final String GENERATE_QR = "gerar-qrcode";
    public static final String PAYMENT_APPROVED = "pagamento-aprovado";
    public static final String PAYMENT_REJECTED = "pagamento-rejeitado";
    public static final String PAYMENT_CANCELLED = "pagamento-cancelado";
    public static final String CONNECTION_ERROR = "erro-conexao";
    public static final String ORDER_EXPIRED = "pedido-expirado";

    private static final Map<String, Sound> SOUND_LOOKUP = new ConcurrentHashMap<>();
    private static final Map<String, SoundSpec> CONFIGURED_SOUNDS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_UI_SOUND_MS = new ConcurrentHashMap<>();
    private static volatile boolean registryIndexed = false;

    private static final class SoundSpec {
        final boolean enabled;
        final String soundName;
        final float volume;
        final float pitch;

        SoundSpec(boolean enabled, String soundName, float volume, float pitch) {
            this.enabled = enabled;
            this.soundName = soundName;
            this.volume = volume;
            this.pitch = pitch;
        }
    }

    private SoundManager() {
    }

    public static void loadSounds(AutoPix plugin) {
        CONFIGURED_SOUNDS.clear();
        if (plugin == null) return;

        FileConfiguration cfg = plugin.getConfig();
        boolean legacyMasterEnabled = cfg.getBoolean("som.ativar", true);
        String legacyApprovedSound = cfg.getString("som.efeito", "ENTITY_PLAYER_LEVELUP");

        Map<String, SoundSpec> defaults = new HashMap<>();
        defaults.put(OPEN_MENU, new SoundSpec(legacyMasterEnabled, "BLOCK_CHEST_OPEN", 0.7f, 1.1f));
        defaults.put(SELECT_PRODUCT, new SoundSpec(legacyMasterEnabled, "UI_BUTTON_CLICK", 0.8f, 1.2f));
        defaults.put(CONFIRM_PURCHASE, new SoundSpec(legacyMasterEnabled, "BLOCK_NOTE_BLOCK_PLING", 0.9f, 1.3f));
        defaults.put(GENERATE_QR, new SoundSpec(legacyMasterEnabled, "ENTITY_EXPERIENCE_ORB_PICKUP", 1.0f, 1.2f));
        defaults.put(PAYMENT_APPROVED, new SoundSpec(legacyMasterEnabled, legacyApprovedSound, 1.0f, 1.0f));
        defaults.put(PAYMENT_REJECTED, new SoundSpec(legacyMasterEnabled, "ENTITY_VILLAGER_NO", 1.0f, 0.9f));
        defaults.put(PAYMENT_CANCELLED, new SoundSpec(legacyMasterEnabled, "BLOCK_NOTE_BLOCK_BASS", 0.9f, 0.8f));
        defaults.put(CONNECTION_ERROR, new SoundSpec(legacyMasterEnabled, "BLOCK_ANVIL_LAND", 0.6f, 0.8f));
        defaults.put(ORDER_EXPIRED, new SoundSpec(legacyMasterEnabled, "BLOCK_NOTE_BLOCK_DIDGERIDOO", 0.9f, 0.8f));

        ConfigurationSection section = cfg.getConfigurationSection("sons");
        for (Map.Entry<String, SoundSpec> entry : defaults.entrySet()) {
            String key = entry.getKey();
            SoundSpec def = entry.getValue();
            if (section != null && section.isConfigurationSection(key)) {
                ConfigurationSection sub = section.getConfigurationSection(key);
                boolean enabled = sub.getBoolean("ativar", def.enabled);
                String soundName = sub.getString("som", def.soundName);
                float volume = clamp((float) sub.getDouble("volume", def.volume), 0.0f, 2.0f);
                float pitch = clamp((float) sub.getDouble("pitch", def.pitch), 0.5f, 2.0f);
                CONFIGURED_SOUNDS.put(key, new SoundSpec(enabled, soundName, volume, pitch));
            } else {
                CONFIGURED_SOUNDS.put(key, def);
            }
        }
    }

    public static void play(Player player, String soundKey) {
        if (player == null || soundKey == null || Bukkit.getServer() == null) return;

        SoundSpec spec = CONFIGURED_SOUNDS.get(soundKey);
        if (spec == null || !spec.enabled || spec.soundName == null || spec.soundName.trim().isEmpty()) {
            return;
        }

        if (isUiSound(soundKey)) {
            long now = System.currentTimeMillis();
            String rateKey = player.getName().toLowerCase(Locale.ROOT) + ":" + soundKey;
            Long previous = LAST_UI_SOUND_MS.get(rateKey);
            if (previous != null && (now - previous) < 150L) {
                return;
            }
            LAST_UI_SOUND_MS.put(rateKey, now);
        }

        if (Bukkit.isPrimaryThread()) {
            playOnMainThread(player, spec);
        } else {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> playOnMainThread(player, spec));
            }
        }
    }

    private static void playOnMainThread(Player player, SoundSpec spec) {
        if (player == null || !player.isOnline()) return;
        Sound sound = resolveSound(spec.soundName);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, spec.volume, spec.pitch);
        }
    }

    private static boolean isUiSound(String soundKey) {
        return OPEN_MENU.equals(soundKey) || SELECT_PRODUCT.equals(soundKey) || CONFIRM_PURCHASE.equals(soundKey);
    }

    public static Sound resolveSound(String rawName) {
        if (rawName == null || rawName.trim().isEmpty() || Bukkit.getServer() == null) {
            return null;
        }
        ensureRegistryIndexed();

        String normalized = rawName.trim();
        Sound direct = SOUND_LOOKUP.get(normalized.toUpperCase(Locale.ROOT));
        if (direct != null) return direct;

        Sound lower = SOUND_LOOKUP.get(normalized.toLowerCase(Locale.ROOT));
        if (lower != null) return lower;

        String dotForm = normalized.toLowerCase(Locale.ROOT).replace('_', '.');
        NamespacedKey key = NamespacedKey.fromString(dotForm);
        if (key != null) {
            Sound byKey = Registry.SOUNDS.get(key);
            if (byKey != null) return byKey;
        }
        return null;
    }

    private static void ensureRegistryIndexed() {
        if (registryIndexed) return;
        synchronized (SOUND_LOOKUP) {
            if (registryIndexed) return;
            try {
                for (Sound sound : Registry.SOUNDS) {
                    NamespacedKey key = Registry.SOUNDS.getKey(sound);
                    if (key != null) {
                        String rawKey = key.getKey();
                        SOUND_LOOKUP.put(rawKey.toLowerCase(Locale.ROOT), sound);
                        SOUND_LOOKUP.put(rawKey.replace('.', '_').toUpperCase(Locale.ROOT), sound);
                    }
                }
                registryIndexed = true;
            } catch ( Throwable ignored) {
            }
        }
    }

    public static void clearPlayerRateLimits(String playerName) {
        if (playerName == null) return;
        String prefix = playerName.toLowerCase(Locale.ROOT) + ":";
        LAST_UI_SOUND_MS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
