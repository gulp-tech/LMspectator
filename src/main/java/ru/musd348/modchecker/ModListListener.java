package ru.musd348.modchecker;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ModListListener implements PluginMessageListener {

    private final ModChecker plugin;

    public ModListListener(ModChecker plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (channel.equals("fml:handshake")) {
            handleFmlHandshake(player, message);
        } else if (channel.equals("minecraft:register")) {
            handleMinecraftRegister(player, message);
        }
    }

    private void handleFmlHandshake(Player player, byte[] message) {
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        
        try {
            byte discriminator = in.readByte();
            if (discriminator == 2) {
                List<String> mods = new ArrayList<>();
                try {
                    int modCount = in.readInt();
                    for (int i = 0; i < modCount; i++) {
                        String modId = readString(in);
                        String modVersion = readString(in);
                        mods.add(modId + " (" + modVersion + ")");
                    }
                    
                    if (!mods.isEmpty()) {
                        String modsList = String.join(", ", mods);
                        plugin.storePlayerMods(player.getName(), modsList);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void handleMinecraftRegister(Player player, byte[] message) {
        try {
            String channels = new String(message, StandardCharsets.UTF_8);
            if (channels.contains("fabric-tag-sync") || channels.contains("fabric:load")) {
                plugin.storePlayerMods(player.getName(), "Использует Fabric (Моды скрыты каналом)");
            }
        } catch (Exception ignored) {}
    }

    private String readString(ByteArrayDataInput in) {
        int length = in.readByte();
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = in.readByte();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
