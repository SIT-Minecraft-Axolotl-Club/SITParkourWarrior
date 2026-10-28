package club.sitmc.sitParkourWarrior.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public final class Msg {

    private static final String PREFIX = "&3&lSIT-Parkour&8| &f";

    private Msg() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(color(PREFIX + message));
    }

    public static void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(color(message));
    }
}
