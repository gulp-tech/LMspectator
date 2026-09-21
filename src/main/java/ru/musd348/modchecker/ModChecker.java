package ru.musd348.modchecker;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.HashMap;
import java.util.Map;

public class ModChecker extends JavaPlugin implements CommandExecutor {

    private final Map<String, String> playerMods = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        getCommand("checkmods").setExecutor(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, "fml:handshake", new ModListListener(this));
        getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:register", new ModListListener(this));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("checkmods")) {
            return false;
        }

        if (!sender.hasPermission("modchecker.use")) {
            sender.sendMessage(mm.deserialize("<red>У вас нет прав для использования этой команды.</red>"));
            return true;
        }

        Player target = null;

        if (args.length == 0) {
            if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(mm.deserialize("<red>Игрок не найден.</red>"));
                return true;
            }
        } else {
            target = Bukkit.getPlayer(args[0]);
        }

        if (target == null || !target.isOnline()) {
            sender.sendMessage(mm.deserialize("<red>Игрок не найден.</red>"));
            return true;
        }

        String clientBrand = getClientName(target.getClientBrandName());
        String mods = playerMods.getOrDefault(target.getName(), "Нет модов");

        if (clientBrand.equals("Vanilla") || mods.equals("Нет модов")) {
            mods = "Нет модов";
        }

        sender.sendMessage(mm.deserialize("<gray>[<gold>ModChecker</gold>] <white>Информация о игроке <yellow>" + target.getName() + "</yellow>:</white>"));
        sender.sendMessage(mm.deserialize("<gray>• <white>Клиент: <green>" + clientBrand + "</green>"));
        sender.sendMessage(mm.deserialize("<gray>• <white>Моды: <yellow>" + mods + "</yellow>"));

        return true;
    }

    private String getClientName(@Nullable String brand) {
        if (brand == null || brand.isEmpty()) {
            return "Vanilla";
        }

        String lowerBrand = brand.toLowerCase();

        if (lowerBrand.contains("fabric")) {
            return "Fabric";
        } else if (lowerBrand.contains("forge")) {
            return "Forge";
        } else if (lowerBrand.contains("lunar")) {
            return "LunarClient";
        } else if (lowerBrand.contains("badlion")) {
            return "BadlionClient";
        } else if (lowerBrand.contains("feather")) {
            return "FeatherClient";
        } else if (lowerBrand.contains("labymod") || lowerBrand.contains("laby")) {
            return "LabyMod";
        } else if (lowerBrand.contains("vanilla") || lowerBrand.equals("minecraft")) {
            return "Vanilla";
        }

        return brand.substring(0, 1).toUpperCase() + brand.substring(1).toLowerCase();
    }

    public void storePlayerMods(String playerName, String modsList) {
        playerMods.put(playerName, modsList);
    }

    public String getPlayerMods(String playerName) {
        return playerMods.get(playerName);
    }
}
