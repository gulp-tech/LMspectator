package ru.musd348.modchecker;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Определение имени клиента по бренду и версии игры по номеру сетевого протокола.
 *
 * <p>Бренд приходит из ванильного канала {@code minecraft:brand} и доступен через
 * {@link org.bukkit.entity.Player#getClientBrandName()}. Номер протокола отдаёт
 * Paper/Purpur API ({@code NetworkClient#getProtocolVersion()}).</p>
 */
public final class ClientDetector {

    /** Имя ванильного клиента. */
    public static final String VANILLA = "Vanilla";

    /** Имя клиента Forge. */
    public static final String FORGE = "Forge";

    /** Имя клиента Fabric. */
    public static final String FABRIC = "Fabric";

    /** Текст для случая, когда версию определить не удалось. */
    public static final String UNKNOWN_VERSION = "Неизвестно";

    /**
     * Соответствие «номер протокола -> версия Minecraft».
     * Порядок важен: сначала самые новые версии.
     */
    private static final Map<Integer, String> PROTOCOL_VERSIONS = new LinkedHashMap<>();

    static {
        PROTOCOL_VERSIONS.put(774, "1.21.8");
        PROTOCOL_VERSIONS.put(773, "1.21.10");
        PROTOCOL_VERSIONS.put(772, "1.21.7");
        PROTOCOL_VERSIONS.put(771, "1.21.6");
        PROTOCOL_VERSIONS.put(770, "1.21.5");
        PROTOCOL_VERSIONS.put(769, "1.21.4");
        PROTOCOL_VERSIONS.put(768, "1.21.2");
        PROTOCOL_VERSIONS.put(767, "1.21.1");
        PROTOCOL_VERSIONS.put(766, "1.20.5");
        PROTOCOL_VERSIONS.put(765, "1.20.3");
        PROTOCOL_VERSIONS.put(764, "1.20.2");
        PROTOCOL_VERSIONS.put(763, "1.20");
        PROTOCOL_VERSIONS.put(762, "1.19.4");
        PROTOCOL_VERSIONS.put(761, "1.19.3");
        PROTOCOL_VERSIONS.put(760, "1.19.1");
        PROTOCOL_VERSIONS.put(759, "1.19");
        PROTOCOL_VERSIONS.put(758, "1.18.2");
        PROTOCOL_VERSIONS.put(757, "1.18");
        PROTOCOL_VERSIONS.put(756, "1.17.1");
        PROTOCOL_VERSIONS.put(755, "1.17");
        PROTOCOL_VERSIONS.put(754, "1.16.4");
        PROTOCOL_VERSIONS.put(753, "1.16.3");
        PROTOCOL_VERSIONS.put(751, "1.16.2");
        PROTOCOL_VERSIONS.put(736, "1.16.1");
        PROTOCOL_VERSIONS.put(735, "1.16");
        PROTOCOL_VERSIONS.put(578, "1.15.2");
        PROTOCOL_VERSIONS.put(575, "1.15.1");
        PROTOCOL_VERSIONS.put(573, "1.15");
        PROTOCOL_VERSIONS.put(498, "1.14.4");
        PROTOCOL_VERSIONS.put(490, "1.14.3");
        PROTOCOL_VERSIONS.put(485, "1.14.2");
        PROTOCOL_VERSIONS.put(480, "1.14.1");
        PROTOCOL_VERSIONS.put(477, "1.14");
        PROTOCOL_VERSIONS.put(404, "1.13.2");
        PROTOCOL_VERSIONS.put(401, "1.13.1");
        PROTOCOL_VERSIONS.put(393, "1.13");
        PROTOCOL_VERSIONS.put(340, "1.12.2");
        PROTOCOL_VERSIONS.put(338, "1.12.1");
        PROTOCOL_VERSIONS.put(335, "1.12");
        PROTOCOL_VERSIONS.put(316, "1.11.1");
        PROTOCOL_VERSIONS.put(315, "1.11");
        PROTOCOL_VERSIONS.put(210, "1.10");
        PROTOCOL_VERSIONS.put(110, "1.9.3");
        PROTOCOL_VERSIONS.put(109, "1.9.2");
        PROTOCOL_VERSIONS.put(108, "1.9.1");
        PROTOCOL_VERSIONS.put(107, "1.9");
        PROTOCOL_VERSIONS.put(47, "1.8");
        PROTOCOL_VERSIONS.put(5, "1.7.6");
        PROTOCOL_VERSIONS.put(4, "1.7.2");
    }

    private ClientDetector() {
        throw new UnsupportedOperationException("Утилитарный класс");
    }

    /**
     * Определяет имя клиента по сырому бренду.
     *
     * @param brand значение {@code Player#getClientBrandName()}, может быть {@code null}
     * @return имя клиента, например {@code Fabric}, {@code Forge}, {@code Vanilla}, {@code LunarClient}
     */
    public static String detectClient(String brand) {
        if (brand == null || brand.isBlank()) {
            return VANILLA;
        }

        String lower = brand.toLowerCase(Locale.ROOT);

        if (lower.contains("lunar")) {
            return "LunarClient";
        }
        if (lower.contains("badlion")) {
            return "BadlionClient";
        }
        if (lower.contains("feather")) {
            return "FeatherClient";
        }
        if (lower.contains("laby")) {
            return "LabyMod";
        }
        if (lower.contains("salwyrr")) {
            return "SalwyrrClient";
        }
        if (lower.contains("meteor")) {
            return "MeteorClient";
        }
        if (lower.contains("impact")) {
            return "ImpactClient";
        }
        if (lower.contains("optifine") || lower.contains("optfine")) {
            return "OptiFine";
        }
        if (lower.contains("quilt")) {
            return "Quilt";
        }
        if (lower.contains("neoforge")) {
            return "NeoForge";
        }
        if (lower.contains("forge")) {
            return FORGE;
        }
        if (lower.contains("fabric")) {
            return FABRIC;
        }
        if (lower.contains("purpur")) {
            return "PurpurClient";
        }
        if (lower.contains("vanilla")) {
            return VANILLA;
        }
        if (lower.equals("minecraft")) {
            return VANILLA;
        }

        // Неизвестный бренд — показываем его как есть, с заглавной буквы.
        return capitalize(brand.trim());
    }

    /**
     * Определяет версию игры клиента по номеру сетевого протокола.
     *
     * @param protocol номер протокола; значения меньше нуля означают «неизвестно»
     * @return версия игры, например {@code 1.21.1}
     */
    public static String detectGameVersion(int protocol) {
        // -1 — сервер не отдал протокол; 0 — служебное значение; меньше 4 — версия древнее 1.7.2.
        if (protocol < 4) {
            return UNKNOWN_VERSION;
        }

        String exact = PROTOCOL_VERSIONS.get(protocol);
        if (exact != null) {
            return exact;
        }

        int nearest = nearestKnownProtocol(protocol);
        if (nearest < 0) {
            return UNKNOWN_VERSION + " (протокол " + protocol + ")";
        }

        String suffix = protocol > nearest ? "+" : "-";
        return PROTOCOL_VERSIONS.get(nearest) + suffix;
    }

    /**
     * Ищет ближайший известный номер протокола к указанному.
     *
     * @param protocol номер протокола клиента
     * @return ближайший известный протокол или {@code -1}, если таблица пуста
     */
    private static int nearestKnownProtocol(int protocol) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int known : PROTOCOL_VERSIONS.keySet()) {
            int distance = Math.abs(known - protocol);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = known;
            }
        }

        return best;
    }

    /**
     * Делает первую букву заглавной, остальные — строчными.
     *
     * @param value исходная строка
     * @return строка в формате «Слово»
     */
    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() == 1) {
            return value.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }
}
