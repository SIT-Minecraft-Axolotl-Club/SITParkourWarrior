package club.sitmc.sitParkourWarrior.command;

import club.sitmc.sitParkourWarrior.pkws.PkwsRangeConfig;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * 对齐 PKWS 起点开赛倒计时命令的对外提示与补全候选。
 *
 * <p>起点开赛倒计时的秒数已不再限制在 3 至 5 秒：默认取 {@code gate-open-delay-seconds}
 * （默认 10 秒），管理员可在 {@code /sitpkw pkws start countdown [秒数]} 里自由指定任意
 * 大于等于 1 的整数秒。命令主体仍由 {@link DPCommand} 执行，这一层只统一两处与自由
 * 秒数有关的对外说法：非法秒数的提示语，以及秒数参数的补全候选。</p>
 */
public final class PkwsStartCountdownHints implements CommandExecutor, TabCompleter {

    private static final String INVALID_SECONDS_MESSAGE =
            "起点开赛倒计时的秒数必须是大于等于 1 的整数。";

    private final DPCommand delegate;
    private final PkwsRangeConfig rangeConfig;

    public PkwsStartCountdownHints(DPCommand delegate, PkwsRangeConfig rangeConfig) {
        this.delegate = delegate;
        this.rangeConfig = rangeConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (isInvalidCountdownSeconds(sender, args)) {
            Msg.send(sender, INVALID_SECONDS_MESSAGE);
            return true;
        }
        return delegate.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (!isCountdownSecondsArgument(args)) {
            return delegate.onTabComplete(sender, command, alias, args);
        }
        // 秒数可以自由指定，因此只提示当前配置的默认秒数。
        String suggestion = String.valueOf(rangeConfig.getStartCountdownSeconds());
        String typed = args[3];
        if (typed == null || typed.isBlank() || suggestion.startsWith(typed)) {
            return Collections.singletonList(suggestion);
        }
        return Collections.emptyList();
    }

    /**
     * 只有 {@code /sitpkw pkws start countdown <非正整数>} 才由本层提示；合法秒数与
     * 其他子命令一律交给 {@link DPCommand}，行为和权限判定保持不变。
     */
    private boolean isInvalidCountdownSeconds(CommandSender sender, String[] args) {
        if (!isCountdownSecondsArgument(args)) {
            return false;
        }
        if (sender instanceof Player && !sender.hasPermission("dynamicparkour.admin")) {
            // 让 DPCommand 照旧先给出权限提示。
            return false;
        }
        try {
            return !PkwsRangeConfig.isValidStartCountdownSeconds(Integer.parseInt(args[3]));
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private boolean isCountdownSecondsArgument(String[] args) {
        return args != null && args.length == 4
                && args[0] != null && args[1] != null && args[2] != null && args[3] != null
                && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("start")
                && args[2].equalsIgnoreCase("countdown");
    }
}
