package ru.musd348.modchecker;

import java.util.List;
import java.util.Set;

/**
 * Снимок информации о клиенте игрока.
 *
 * <p>Запись неизменяемая, поэтому любые обновления (например, пришедший позже
 * список модов) выполняются через {@link #withMods(List)} и атомарную замену
 * значения в хранилище плагина.</p>
 *
 * @param clientName    человекочитаемое имя клиента (Fabric, Forge, Vanilla, LunarClient, ...)
 * @param gameVersion   версия игры клиента, определённая по номеру протокола
 * @param rawBrand      сырое значение бренда из пакета {@code minecraft:brand}
 * @param protocolVersion номер сетевого протокола клиента (-1, если сервер его не отдал)
 * @param mods          список модов в формате {@code modid} или {@code modid (версия)}
 * @param channels      каналы, зарегистрированные клиентом через {@code minecraft:register}
 */
public record ClientInfo(String clientName,
                         String gameVersion,
                         String rawBrand,
                         int protocolVersion,
                         List<String> mods,
                         Set<String> channels) {

    /** Текст-заглушка, когда модов нет или клиент ванильный. */
    public static final String NO_MODS = "Нет модов";

    public ClientInfo {
        mods = mods == null ? List.of() : List.copyOf(mods);
        channels = channels == null ? Set.of() : Set.copyOf(channels);
    }

    /**
     * Возвращает копию записи с новым списком модов.
     *
     * @param newMods обнаруженные моды
     * @return обновлённая информация о клиенте
     */
    public ClientInfo withMods(List<String> newMods) {
        return new ClientInfo(clientName, gameVersion, rawBrand, protocolVersion, newMods, channels);
    }

    /**
     * Возвращает копию записи с новым набором зарегистрированных каналов.
     *
     * @param newChannels каналы клиента
     * @return обновлённая информация о клиенте
     */
    public ClientInfo withChannels(Set<String> newChannels) {
        return new ClientInfo(clientName, gameVersion, rawBrand, protocolVersion, mods, newChannels);
    }

    /**
     * Список модов одной строкой через запятую.
     *
     * @return перечисление модов или {@value #NO_MODS}, если список пуст
     */
    public String modsAsString() {
        if (mods.isEmpty()) {
            return NO_MODS;
        }
        return String.join(", ", mods);
    }

    /**
     * Является ли клиент ванильным.
     *
     * @return {@code true}, если клиент определён как Vanilla
     */
    public boolean isVanilla() {
        return ClientDetector.VANILLA.equalsIgnoreCase(clientName);
    }
}
