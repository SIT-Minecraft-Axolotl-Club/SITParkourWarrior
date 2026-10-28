package club.sitmc.sitParkourWarrior.map;

import org.bukkit.ChatColor;

public enum Difficulty {
    EASY(ChatColor.GREEN),
    NORMAL(ChatColor.YELLOW),
    HARD(ChatColor.RED),
    EXTREME(ChatColor.DARK_PURPLE);

    private final ChatColor titleColor;

    Difficulty(ChatColor titleColor) {
        this.titleColor = titleColor;
    }

    public ChatColor getTitleColor() {
        return titleColor;
    }

    public static Difficulty fromString(String value) {
        if (value == null) {
            return EASY;
        }
        try {
            return Difficulty.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return EASY;
        }
    }
}
