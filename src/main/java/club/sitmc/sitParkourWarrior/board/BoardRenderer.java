package club.sitmc.sitParkourWarrior.board;

import club.sitmc.sitParkourWarrior.records.RecordsManager;

import java.util.List;

/**
 * Generates TextDisplay content strings for leaderboard boards.
 */
public final class BoardRenderer {

    private BoardRenderer() {}

    /**
     * Build the full display text for a board.
     * @param board   the board
     * @param records records manager for data queries
     * @param nearestPlayerName  name of the nearest player in range, or null
     * @param nearestPlayerRank  rank of the nearest player (1-based), or -1
     * @param nearestPlayerTime  time string for nearest player, or null
     * @param nearestPlayerInTop10  true if nearest player is already in top 10
     */
    public static String build(BoardData board, RecordsManager records,
                                String nearestPlayerName, int nearestPlayerRank,
                                String nearestPlayerTime, boolean nearestPlayerInTop3) {
        StringBuilder sb = new StringBuilder();

        String tierName = board.tierDisplayName();
        boolean isCountdown = "countdown".equals(board.getTier());

        // Line 1: title
        sb.append("§6§l").append(tierName).append(" §7- §f").append(board.getWorldName()).append("\n");

        if (isCountdown) {
            List<RecordsManager.CountdownRankEntry> all = records.getCountdownTop(board.getWorldName(), Integer.MAX_VALUE);
            sb.append("§7总人数: §f").append(all.size()).append("\n");
            if (all.isEmpty()) {
                sb.append("§7暂无记录");
            } else {
                int limit = Math.min(all.size(), 3);
                for (int i = 0; i < limit; i++) {
                    appendCountdownLine(sb, i + 1, all.get(i));
                }
                // Viewer's own line (always shown if not in top 3)
                if (nearestPlayerName != null && !nearestPlayerInTop3 && nearestPlayerRank > 0) {
                    sb.append("§8----------------\n");
                    sb.append(nearestPlayerTime).append("\n");
                }
            }
        } else {
            List<RecordsManager.RankEntry> all = records.getTop(board.getWorldName(), board.getTier(), Integer.MAX_VALUE);
            sb.append("§7总人数: §f").append(all.size()).append("\n");
            if (all.isEmpty()) {
                sb.append("§7暂无记录");
            } else {
                int limit = Math.min(all.size(), 3);
                for (int i = 0; i < limit; i++) {
                    appendRankLine(sb, i + 1, all.get(i));
                }
                if (nearestPlayerName != null && !nearestPlayerInTop3 && nearestPlayerRank > 0) {
                    sb.append("§8----------------\n");
                    sb.append(nearestPlayerTime).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private static void appendRankLine(StringBuilder sb, int rank, RecordsManager.RankEntry e) {
        String medal = rankMedal(rank);
        sb.append(medal).append(String.format("%2d. ", rank))
          .append("§f").append(e.playerName)
          .append("  §b").append(formatTime(e.timeMs))
          .append("  §7(").append(e.medals).append("牌)\n");
    }

    private static void appendCountdownLine(StringBuilder sb, int rank, RecordsManager.CountdownRankEntry e) {
        String medal = rankMedal(rank);
        sb.append(medal).append(String.format("%2d. ", rank))
          .append("§f").append(e.playerName)
          .append("  §b").append(e.score).append("分")
          .append("  §7石").append(e.stone).append(" 铜").append(e.bronze)
          .append(" 银").append(e.silver).append(" 金").append(e.gold)
          .append("  §7").append(endTierDisplay(e.endTier)).append("\n");
    }

    private static String endTierDisplay(String tier) {
        if (tier == null) return "超时";
        switch (tier) {
            case "easy":   return "简单";
            case "normal": return "普通";
            case "hard":   return "困难";
            default:       return tier;
        }
    }

    private static String rankMedal(int rank) {
        switch (rank) {
            case 1:  return "§6";
            case 2:  return "§7";
            case 3:  return "§c";
            default: return "§8";
        }
    }

    public static String formatTime(long ms) {
        long totalSec = ms / 1000;
        long millis = ms % 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
        }
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }

    /**
     * 低精度时间格式，用于 Action Bar 等高频刷新场景。
     * 精度到十分之一秒（四舍五入），如 "01:02.3"。
     */
    public static String formatTimeTenths(long ms) {
        long totalTenths = (ms + 50) / 100;  // 四舍五入到 0.1s
        long tenths = totalTenths % 10;
        long totalSec = totalTenths / 10;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d.%d", hours, minutes, seconds, tenths);
        }
        return String.format("%02d:%02d.%d", minutes, seconds, tenths);
    }

    /**
     * Find the rank (1-based) and time string of a player in a COUNTUP tier.
     * Returns null if not found.
     */
    public static PlayerRankInfo findPlayerRankCountup(String worldName, String tier, String playerName, RecordsManager records) {
        List<RecordsManager.RankEntry> all = records.getTop(worldName, tier, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) {
            RecordsManager.RankEntry e = all.get(i);
            if (e.playerName.equals(playerName)) {
                int rank = i + 1;
                String line = rankMedal(rank) + String.format("%2d. ", rank)
                        + "§f" + e.playerName
                        + "  §b" + formatTime(e.timeMs)
                        + "  §7(" + e.medals + "牌)";
                return new PlayerRankInfo(rank, line);
            }
        }
        return null;
    }

    public static PlayerRankInfo findPlayerRankCountdown(String worldName, String playerName, RecordsManager records) {
        List<RecordsManager.CountdownRankEntry> all = records.getCountdownTop(worldName, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) {
            RecordsManager.CountdownRankEntry e = all.get(i);
            if (e.playerName.equals(playerName)) {
                int rank = i + 1;
                String line = rankMedal(rank) + String.format("%2d. ", rank)
                        + "§f" + e.playerName
                        + "  §b" + e.score + "分"
                        + "  §7石" + e.stone + " 铜" + e.bronze
                        + " 银" + e.silver + " 金" + e.gold
                        + "  §7" + endTierDisplay(e.endTier);
                return new PlayerRankInfo(rank, line);
            }
        }
        return null;
    }

    public static class PlayerRankInfo {
        public final int rank;
        public final String display;
        PlayerRankInfo(int rank, String display) { this.rank = rank; this.display = display; }
    }
}
