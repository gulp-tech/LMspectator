package ru.musd348.modchecker;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Слушатель входящих плагин-каналов, из которых достаётся список модов клиента.
 *
 * <p>Обрабатываемые каналы:</p>
 * <ul>
 *     <li>{@code fml:handshake} — handshake Forge/NeoForge версии 1.13+ (формат FML2);</li>
 *     <li>{@code FML|HS} — legacy-handshake Forge версий 1.7.10–1.12.2;</li>
 *     <li>{@code minecraft:register} — перечень каналов, зарегистрированных клиентом.</li>
 * </ul>
 *
 * <p>Важно: клиент отдаёт список модов во время фазы login/configuration, а в фазу play
 * эти данные Bukkit-каналами не передаются. Поэтому парсер максимально «мягкий»: любой
 * нестандартный или обрезанный пакет просто игнорируется и не роняет сервер.</p>
 */
public class ModListListener implements PluginMessageListener {

    /** Канал handshake Forge/NeoForge 1.13+. */
    public static final String CHANNEL_FML_HANDSHAKE = "fml:handshake";

    /** Канал legacy-handshake Forge 1.7.10–1.12.2. */
    public static final String CHANNEL_FML_HS = "FML|HS";

    /** Служебный канал регистрации клиентских каналов. */
    public static final String CHANNEL_REGISTER = "minecraft:register";

    /** Индекс пакета со списком модов клиента в формате FML2. */
    private static final int FML2_CLIENT_MOD_LIST = 2;

    /** Индекс пакета с версиями модов клиента в формате FML2. */
    private static final int FML2_MOD_VERSIONS = 3;

    /** Дискриминатор пакета ModList в legacy-формате FML|HS. */
    private static final byte LEGACY_MOD_LIST = 2;

    /** Жёсткий потолок на размер пакета, который мы готовы разобрать. */
    private static final int MAX_PARSE_BYTES = 2 * 1024 * 1024;

    /** Служебные идентификаторы, которые не являются пользовательскими модами. */
    private static final Set<String> CORE_MOD_IDS = Set.of(
            "minecraft", "forge", "fml", "mcp", "neoforge", "java", "modlauncher", "forgespi"
    );

    /** Префиксы каналов, однозначно выдающие Fabric-клиент. */
    private static final List<String> FABRIC_MARKERS = List.of(
            "fabric:registry/sync",
            "fabric-screen-handler-api",
            "fabric-networking-api",
            "fabric-keybindings-api",
            "fabric-resource-loader",
            "fabric-lifecycle-events",
            "fabric-item-api",
            "fabric-transfer-api"
    );

    private final ModChecker plugin;

    /**
     * Создаёт слушатель каналов.
     *
     * @param plugin экземпляр плагина, в котором хранятся данные игроков
     */
    public ModListListener(ModChecker plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (channel == null || player == null || message == null || message.length == 0) {
            return;
        }

        if (message.length > MAX_PARSE_BYTES) {
            return;
        }

        try {
            switch (channel) {
                case CHANNEL_FML_HANDSHAKE -> handleFmlHandshake(player, message);
                case CHANNEL_FML_HS -> handleLegacyFmlHandshake(player, message);
                case CHANNEL_REGISTER -> handleRegister(player, message);
                default -> {
                    // Остальные каналы нас не интересуют.
                }
            }
        } catch (RuntimeException exception) {
            // Никогда не роняем сервер из-за кривого клиентского пакета.
            plugin.debug("Не удалось разобрать пакет канала " + channel + " от " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Forge 1.13+ (канал fml:handshake, формат FML2)                      */
    /* ------------------------------------------------------------------ */

    /**
     * Разбирает пакет handshake Forge/NeoForge 1.13+.
     *
     * <p>Структура: {@code varint-индекс пакета} и далее тело. Строки кодируются как
     * {@code varint-длина + UTF-8}. Индекс 2 — список modid'ов клиента, индекс 3 —
     * карта «modid -> (отображаемое имя, версия)».</p>
     *
     * @param player  отправитель пакета
     * @param payload сырое содержимое пакета
     */
    private void handleFmlHandshake(Player player, byte[] payload) {
        Cursor cursor = new Cursor(payload);

        int index = cursor.readVarInt();
        switch (index) {
            case FML2_CLIENT_MOD_LIST -> storeMods(player, readFml2ModList(cursor));
            case FML2_MOD_VERSIONS -> storeMods(player, readFml2ModVersions(cursor));
            default -> {
                // Acknowledge, RegistryList и прочие пакеты списка модов не содержат.
            }
        }
    }

    /**
     * Читает пакет {@code C2SModListReply}: varint-количество и varint-строки modid'ов.
     *
     * @param cursor курсор по буферу пакета
     * @return список идентификаторов модов
     */
    private List<String> readFml2ModList(Cursor cursor) {
        List<String> mods = new ArrayList<>();
        int declared = clampCount(cursor.readVarInt());

        for (int i = 0; i < declared; i++) {
            if (!cursor.hasRemaining()) {
                // Пакет обрезан — частичные данные показывать нельзя.
                return List.of();
            }
            mods.add(cursor.readUtf());
        }

        return sanitize(mods);
    }

    /**
     * Читает пакет {@code ModVersions}: карта «modid -> (имя, версия)».
     *
     * @param cursor курсор по буферу пакета
     * @return список модов в формате {@code modid (версия)}
     */
    private List<String> readFml2ModVersions(Cursor cursor) {
        List<String> mods = new ArrayList<>();
        int declared = clampCount(cursor.readVarInt());

        for (int i = 0; i < declared; i++) {
            if (!cursor.hasRemaining()) {
                // Пакет обрезан — частичные данные показывать нельзя.
                return List.of();
            }

            String modId = cursor.readUtf();
            cursor.readUtf(); // отображаемое имя мода
            String version = cursor.readUtf();

            if (modId.isBlank() || version.isBlank()) {
                return List.of();
            }

            mods.add(modId + " (" + version + ")");
        }

        return sanitize(mods);
    }

    /* ------------------------------------------------------------------ */
    /* Forge 1.7.10–1.12.2 (канал FML|HS, legacy-формат)                   */
    /* ------------------------------------------------------------------ */

    /**
     * Разбирает legacy-пакет Forge.
     *
     * <p>Структура: байт-дискриминатор и далее тело. Дискриминатор 2 — {@code ModList}:
     * количество элементов (int) и пары строк «modid, версия». Строки в оригинале пишутся
     * через {@code DataOutputStream.writeUTF}, но часть сборок FML использует varint-длины,
     * поэтому разбираются оба варианта — с полным отбрасыванием обрезанных пакетов.</p>
     *
     * @param player  отправитель пакета
     * @param payload сырое содержимое пакета
     */
    private void handleLegacyFmlHandshake(Player player, byte[] payload) {
        int discriminator = payload[0] & 0xFF;
        if (discriminator != LEGACY_MOD_LIST) {
            return;
        }

        // Вариант 1: длины строк — обычные int (формат 1.8–1.12.2).
        List<String> mods = parseLegacyDataInput(payload);

        if (mods.isEmpty()) {
            // Вариант 2: varint-строки — пробуем разобрать тем же курсором, что и FML2.
            mods = parseLegacyVarint(payload);
        }

        storeMods(player, mods);
    }

    /**
     * Разбирает legacy-ModList, записанный через {@code DataOutputStream}
     * (количество элементов — int, строки — {@code writeUTF}).
     *
     * @param payload сырое содержимое пакета
     * @return список модов; пустой список, если формат не подошёл или пакет обрезан
     */
    private List<String> parseLegacyDataInput(byte[] payload) {
        List<String> mods = new ArrayList<>();

        try (DataInputStream data = new DataInputStream(
                new ByteArrayInputStream(payload, 1, payload.length - 1))) {
            int declared = clampCount(data.readInt());

            for (int i = 0; i < declared; i++) {
                String modId = data.readUTF();
                String version = data.readUTF();

                if (modId.isBlank() || version.isBlank()) {
                    return List.of();
                }

                mods.add(modId + " (" + version + ")");
            }
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }

        return sanitize(mods);
    }

    /**
     * Разбирает legacy-ModList с varint-строками (встречается в части сборок FML).
     *
     * @param payload сырое содержимое пакета
     * @return список модов; пустой список, если разобрать не удалось
     */
    private List<String> parseLegacyVarint(byte[] payload) {
        List<String> mods = new ArrayList<>();

        try {
            Cursor cursor = new Cursor(payload, 1);
            int declared = clampCount(cursor.readVarInt());

            for (int i = 0; i < declared; i++) {
                if (!cursor.hasRemaining()) {
                    return List.of();
                }

                String modId = cursor.readUtf();
                String version = cursor.readUtf();

                if (modId.isBlank() || version.isBlank()) {
                    return List.of();
                }

                mods.add(modId + " (" + version + ")");
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }

        return sanitize(mods);
    }

    /* ------------------------------------------------------------------ */
    /* minecraft:register                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Разбирает {@code minecraft:register}: перечень каналов через байт {@code 0x00}.
     *
     * <p>Список модов этот канал не отдаёт, но по характерным каналам Fabric можно
     * уточнить тип клиента, если бренд пришёл пустым или неоднозначным.</p>
     *
     * @param player  отправитель пакета
     * @param payload сырое содержимое пакета
     */
    private void handleRegister(Player player, byte[] payload) {
        Set<String> channels = new LinkedHashSet<>();

        for (String raw : new String(payload, StandardCharsets.UTF_8).split("\u0000")) {
            String channel = raw.trim();
            if (!channel.isEmpty()) {
                channels.add(channel);
            }
        }

        if (channels.isEmpty()) {
            return;
        }

        plugin.storeChannels(player.getUniqueId(), channels);

        boolean looksLikeFabric = channels.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(channel -> FABRIC_MARKERS.stream().anyMatch(channel::startsWith));

        if (looksLikeFabric) {
            plugin.markFabric(player.getUniqueId());
            plugin.debug("Клиент " + player.getName() + " определён как Fabric по каналам: " + channels);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Вспомогательные методы                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Сохраняет список модов игрока, если он не пуст.
     *
     * @param player игрок-владелец данных
     * @param mods   разобранные моды
     */
    private void storeMods(Player player, List<String> mods) {
        if (mods.isEmpty()) {
            return;
        }

        plugin.storeMods(player.getUniqueId(), mods);
        plugin.debug("Получен список модов " + player.getName() + " (" + mods.size() + " шт.): " + mods);
    }

    /**
     * Приводит список модов к аккуратному виду: убирает пустые значения, служебные
     * идентификаторы и дубликаты.
     *
     * @param mods сырой список
     * @return отсортированный список модов без повторов
     */
    private List<String> sanitize(List<String> mods) {
        Set<String> unique = new LinkedHashSet<>();

        for (String mod : mods) {
            if (mod == null) {
                continue;
            }

            String value = mod.trim();
            if (value.isEmpty()) {
                continue;
            }

            String id = value;
            String version = "";
            int space = value.indexOf(' ');
            if (space > 0) {
                id = value.substring(0, space);
                version = value.substring(space + 1).trim();
            }

            int bracket = version.indexOf('(');
            if (bracket >= 0) {
                version = version.substring(bracket + 1).replace(")", "").trim();
            }

            // Пустой идентификатор или пустая версия — признак битого пакета.
            if (id.isEmpty() || (space > 0 && version.isEmpty())) {
                continue;
            }

            if (CORE_MOD_IDS.contains(id.toLowerCase(Locale.ROOT))) {
                continue;
            }

            unique.add(space > 0 && !version.isEmpty() ? id + " (" + version + ")" : id);
        }

        List<String> result = new ArrayList<>(unique);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /**
     * Обрезает количество элементов до разумного предела, чтобы обрезанный или
     * поддельный пакет не привёл к гигантскому циклу.
     *
     * @param count заявленное количество элементов
     * @return безопасное количество элементов
     */
    private static int clampCount(int count) {
        if (count < 0) {
            return 0;
        }
        return Math.min(count, 4096);
    }

    /**
     * Курсор по буферу пакета в формате Minecraft: varint'ы и varint-строки UTF-8.
     */
    private static final class Cursor {

        private final ByteBuffer buffer;

        Cursor(byte[] data) {
            this(data, 0);
        }

        Cursor(byte[] data, int offset) {
            this.buffer = ByteBuffer.wrap(Arrays.copyOfRange(data, offset, data.length));
        }

        boolean hasRemaining() {
            return buffer.hasRemaining();
        }

        /**
         * Читает varint (до 5 байт).
         *
         * @return прочитанное значение
         */
        int readVarInt() {
            int result = 0;
            int shift = 0;

            while (true) {
                if (!buffer.hasRemaining()) {
                    throw new IllegalStateException("Пакет оборвался на varint");
                }
                if (shift >= 32) {
                    throw new IllegalStateException("Слишком длинный varint");
                }

                byte current = buffer.get();
                result |= (current & 0x7F) << shift;

                if ((current & 0x80) == 0) {
                    return result;
                }

                shift += 7;
            }
        }

        /**
         * Читает строку в формате {@code varint-длина + UTF-8}.
         *
         * @return прочитанная строка
         */
        String readUtf() {
            int length = readVarInt();

            if (length < 0 || length > buffer.remaining()) {
                throw new IllegalStateException("Некорректная длина строки: " + length);
            }

            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
