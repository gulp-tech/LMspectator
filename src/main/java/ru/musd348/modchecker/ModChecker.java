package ru.musd348.modchecker;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModChecker — плагин проверки клиента, версии игры и списка модов у игроков.
 *
 * <p>Данные берутся из стандартных механизмов Minecraft: бренда клиента
 * ({@code minecraft:brand} -> {@link Player#getClientBrandName()}), номера сетевого
 * протокола и входящих плагин-каналов {@code fml:handshake}, {@code FML|HS} и
 * {@code minecraft:register}.</p>
 *
 * <p>Все сообщения выводятся через MiniMessage — нативный формат Adventure в
 * Paper/Purpur, поэтому legacy-коды цветов (§) не используются.</p>
 *
 * @author musd348
 */
public final class ModChecker extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    /** Основной канал handshake Forge/NeoForge. */
    private static final String CHANNEL_FML_HANDSHAKE = ModListListener.CHANNEL_FML_HANDSHAKE;

    /** Legacy-канал handshake Forge 1.7.10–1.12.2. */
    private static final String CHANNEL_FML_HS = ModListListener.CHANNEL_FML_HS;

    /** Канал регистрации клиентских каналов. */
    private static final String CHANNEL_REGISTER = ModListListener.CHANNEL_REGISTER;

    /** Префикс всех сообщений плагина. */
    private static final String PREFIX = "<gray>[</gray><gold>ModChecker</gold><gray>]</gray> ";

    /** Сообщение: игрок не найден или оффлайн. */
    private static final String MSG_PLAYER_NOT_FOUND = "<red>Игрок не найден.</red>";

    /** Сообщение: недостаточно прав. */
    private static final String MSG_NO_PERMISSION = "<red>У вас нет прав для использования этой команды.</red>";

    /** Заголовок отчёта. Аргумент 0 — ник игрока. */
    private static final String MSG_HEADER = PREFIX + "<white>Информация о игроке</white> <yellow><name></yellow><white>:</white>";

    /** Строка «Клиент». Аргумент 0 — имя клиента. */
    private static final String MSG_CLIENT = "<gray>•</gray> <white>Клиент:</white> <green><value></green>";

    /** Строка «Версия». Аргумент 0 — версия игры. */
    private static final String MSG_VERSION = "<gray>•</gray> <white>Версия:</white> <green><value></green>";

    /** Строка «Моды». Аргумент 0 — список модов или «Нет модов». */
    private static final String MSG_MODS = "<gray>•</gray> <white>Моды:</white> <yellow><value></yellow>";

    /** Кэш данных о клиентах: UUID игрока -> информация о клиенте. */
    private final Map<UUID, ClientInfo> clients = new ConcurrentHashMap<>();

    /** Парсер сообщений MiniMessage. Создаётся один раз на плагин. */
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        PluginCommand command = getCommand("checkmods");
        if (command == null) {
            getLogger().severe("Команда checkmods не объявлена в plugin.yml — плагин не будет работать.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);

        registerChannel(CHANNEL_FML_HANDSHAKE);
        registerChannel(CHANNEL_FML_HS);
        registerChannel(CHANNEL_REGISTER);

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("ModChecker включён. Автор: musd348. Команда: /checkmods <ник>");
    }

    @Override
    public void onDisable() {
        // Снимаем подписки с каналов, чтобы не оставлять «висящих» слушателей.
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        clients.clear();
        getLogger().info("ModChecker выключен.");
    }

    /**
     * Подписывается на входящий плагин-канал, если сервер позволяет это сделать.
     *
     * @param channel имя канала
     */
    private void registerChannel(String channel) {
        try {
            getServer().getMessenger().registerIncomingPluginChannel(this, channel, new ModListListener(this));
            getLogger().info("Слушаю канал: " + channel);
        } catch (IllegalArgumentException exception) {
            // Канал уже зарегистрирован другим плагином или не разрешён сервером.
            getLogger().warning("Не удалось зарегистрировать канал " + channel + ": " + exception.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Команда /checkmods                                                  */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("checkmods")) {
            return false;
        }

        if (!sender.hasPermission("modchecker.use")) {
            send(sender, MSG_NO_PERMISSION);
            return true;
        }

        // Аргумент обязателен: без ника проверять некого.
        if (args.length == 0 || args[0].isBlank()) {
            send(sender, MSG_PLAYER_NOT_FOUND);
            return true;
        }

        Player target = findPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            send(sender, MSG_PLAYER_NOT_FOUND);
            return true;
        }

        ClientInfo info = clientInfoOf(target);
        String mods = info.isVanilla() ? ClientInfo.NO_MODS : info.modsAsString();

        sendHeader(sender, MSG_HEADER, target.getName());
        sendValue(sender, MSG_CLIENT, info.clientName());
        sendValue(sender, MSG_VERSION, info.gameVersion());
        sendValue(sender, MSG_MODS, mods);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("checkmods") || args.length != 1
                || !sender.hasPermission("modchecker.use")) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            if (prefix.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(name);
            }
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /* ------------------------------------------------------------------ */
    /* Хранилище данных о клиентах                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Сохраняет список модов игрока, полученный из плагин-канала.
     *
     * @param uuid уникальный идентификатор игрока
     * @param mods список модов
     */
    public void storeMods(UUID uuid, List<String> mods) {
        if (uuid == null || mods == null || mods.isEmpty()) {
            return;
        }

        clients.compute(uuid, (key, current) -> {
            ClientInfo base = current != null ? current : unknownClient();
            return base.withMods(mods);
        });
    }

    /**
     * Сохраняет каналы, зарегистрированные клиентом.
     *
     * @param uuid     уникальный идентификатор игрока
     * @param channels набор имён каналов
     */
    public void storeChannels(UUID uuid, Set<String> channels) {
        if (uuid == null || channels == null || channels.isEmpty()) {
            return;
        }

        clients.compute(uuid, (key, current) -> {
            ClientInfo base = current != null ? current : unknownClient();
            return base.withChannels(channels);
        });
    }

    /**
     * Помечает клиента как Fabric — используется, когда бренд пустой или
     * неоднозначный, но зарегистрированные каналы явно принадлежат Fabric API.
     *
     * @param uuid уникальный идентификатор игрока
     */
    public void markFabric(UUID uuid) {
        if (uuid == null) {
            return;
        }

        clients.compute(uuid, (key, current) -> {
            ClientInfo base = current != null ? current : unknownClient();
            if (ClientDetector.FABRIC.equalsIgnoreCase(base.clientName())) {
                return base;
            }
            return new ClientInfo(ClientDetector.FABRIC, base.gameVersion(), base.rawBrand(),
                    base.protocolVersion(), base.mods(), base.channels());
        });
    }

    /**
     * Удаляет данные игрока при выходе, чтобы кэш не рос бесконечно.
     *
     * @param event событие выхода игрока
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clients.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /* Внутренние помощники                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Возвращает информацию о клиенте игрока, при необходимости определяя её заново.
     *
     * @param player целевой игрок
     * @return данные о клиенте, никогда не {@code null}
     */
    private ClientInfo clientInfoOf(Player player) {
        ClientInfo stored = clients.get(player.getUniqueId());
        if (stored != null) {
            return stored;
        }

        ClientInfo detected = detect(player);
        clients.put(player.getUniqueId(), detected);
        return detected;
    }

    /**
     * Определяет клиента и версию игры по данным, которые сервер отдаёт напрямую.
     *
     * @param player целевой игрок
     * @return свежий снимок информации о клиенте
     */
    private ClientInfo detect(Player player) {
        String brand = safeBrand(player);
        int protocol = safeProtocol(player);

        return new ClientInfo(
                ClientDetector.detectClient(brand),
                ClientDetector.detectGameVersion(protocol),
                brand,
                protocol,
                List.of(),
                Set.of()
        );
    }

    /**
     * Безопасно читает бренд клиента.
     *
     * @param player целевой игрок
     * @return бренд клиента или {@code null}
     */
    private String safeBrand(Player player) {
        try {
            return player.getClientBrandName();
        } catch (Throwable error) {
            debug("getClientBrandName() недоступен: " + error.getMessage());
            return null;
        }
    }

    /**
     * Безопасно читает номер сетевого протокола клиента.
     *
     * @param player целевой игрок
     * @return номер протокола или -1, если определить не удалось
     */
    private int safeProtocol(Player player) {
        try {
            return player.getProtocolVersion();
        } catch (Throwable error) {
            debug("getProtocolVersion() недоступен: " + error.getMessage());
            return -1;
        }
    }

    /**
     * Пустой снимок данных для случая, когда игрок ещё не был проверен.
     *
     * @return заготовка {@link ClientInfo}
     */
    private ClientInfo unknownClient() {
        return new ClientInfo(ClientDetector.VANILLA, ClientDetector.UNKNOWN_VERSION, null, -1, List.of(), Set.of());
    }

    /**
     * Ищет онлайн-игрока по нику: сначала точное совпадение, затем регистронезависимое.
     *
     * @param name ник из аргумента команды
     * @return найденный игрок или {@code null}
     */
    private Player findPlayer(String name) {
        // getPlayerExact ищет только среди онлайн-игроков и не использует устаревший матчинг.
        return Bukkit.getPlayerExact(name);
    }

    /**
     * Отправляет сообщение без аргументов.
     *
     * @param receiver получатель
     * @param template шаблон MiniMessage
     */
    private void send(CommandSender receiver, String template) {
        receiver.sendMessage(miniMessage.deserialize(template));
    }

    /**
     * Отправляет сообщение, подставляя значение в тег {@code <value>}.
     * Значение вставляется как «unparsed», поэтому MiniMessage не интерпретирует
     * его как разметку (защита от инъекций через ник или название мода).
     *
     * @param receiver получатель
     * @param template шаблон MiniMessage
     * @param value    подставляемое значение
     */
    private void sendValue(CommandSender receiver, String template, String value) {
        receiver.sendMessage(miniMessage.deserialize(template, Placeholder.unparsed("value", value)));
    }

    /**
     * Отправляет заголовок отчёта, подставляя ник игрока в тег {@code <name>}.
     *
     * @param receiver получатель
     * @param template шаблон MiniMessage
     * @param playerName ник проверяемого игрока
     */
    private void sendHeader(CommandSender receiver, String template, String playerName) {
        receiver.sendMessage(miniMessage.deserialize(template, Placeholder.unparsed("name", playerName)));
    }

    /**
     * Пишет отладочное сообщение в лог сервера.
     *
     * @param message текст сообщения
     */
    public void debug(String message) {
        if (isDebugEnabled()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    /**
     * Включена ли отладка.
     *
     * @return {@code true}, если задан флаг {@code -Dmodchecker.debug=true}
     */
    private boolean isDebugEnabled() {
        return Boolean.getBoolean("modchecker.debug");
    }
}
