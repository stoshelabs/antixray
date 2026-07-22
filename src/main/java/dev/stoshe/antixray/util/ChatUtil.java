package dev.stoshe.antixray.util;

import com.hypixel.hytale.server.core.Message;
import dev.stoshe.antixray.AntiXray;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts strings with {@code {#RRGGBB}} hex tags (and legacy {@code &}-codes) into Hytale
 * {@link Message} objects. A colour persists until the next tag. Mirrors the AeroWars convention.
 */
public final class ChatUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("\\{#([A-Fa-f0-9]{6})\\}");
    private static final String DEFAULT_PREFIX = ColorConstants.PRIMARY + "[AntiXray] " + ColorConstants.SECONDARY;

    private ChatUtil() {
    }

    @Nonnull
    public static Message colorize(@Nonnull String text) {
        String normalized = translateLegacy(text);
        if (normalized.isEmpty()) {
            return Message.raw("");
        }

        Matcher matcher = HEX_PATTERN.matcher(normalized);
        List<Message> parts = new ArrayList<>();
        int lastEnd = 0;
        String currentColor = null;

        while (matcher.find()) {
            String segment = normalized.substring(lastEnd, matcher.start());
            if (!segment.isEmpty()) {
                Message part = Message.raw(segment);
                if (currentColor != null) {
                    part.color(currentColor);
                }
                parts.add(part);
            }
            currentColor = "#" + matcher.group(1);
            lastEnd = matcher.end();
        }

        String remaining = normalized.substring(lastEnd);
        if (!remaining.isEmpty()) {
            Message part = Message.raw(remaining);
            if (currentColor != null) {
                part.color(currentColor);
            }
            parts.add(part);
        }

        if (parts.isEmpty()) {
            return Message.raw("");
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return Message.join(parts.toArray(new Message[0]));
    }

    @Nonnull
    public static Message info(@Nonnull String text) {
        return colorize(resolvePrefix() + ColorConstants.INFO + text);
    }

    @Nonnull
    public static Message success(@Nonnull String text) {
        return colorize(resolvePrefix() + ColorConstants.SUCCESS + text);
    }

    @Nonnull
    public static Message error(@Nonnull String text) {
        return colorize(resolvePrefix() + ColorConstants.ERROR + text);
    }

    @Nonnull
    public static Message warning(@Nonnull String text) {
        return colorize(resolvePrefix() + ColorConstants.WARNING + text);
    }

    /** Colorizes without any plugin prefix. */
    @Nonnull
    public static Message plain(@Nonnull String text) {
        return colorize(text);
    }

    @Nonnull
    public static String stripColor(@Nonnull String text) {
        return HEX_PATTERN.matcher(translateLegacy(text)).replaceAll("");
    }

    @Nonnull
    private static String resolvePrefix() {
        try {
            AntiXray plugin = AntiXray.getInstance();
            if (plugin == null || plugin.getConfig() == null || plugin.getConfig().General == null) {
                return DEFAULT_PREFIX;
            }
            var general = plugin.getConfig().General;
            if (!general.PrefixEnabled) {
                return "";
            }
            return general.Prefix != null && !general.Prefix.isBlank() ? general.Prefix : DEFAULT_PREFIX;
        } catch (Exception ignored) {
            return DEFAULT_PREFIX;
        }
    }

    private static String translateLegacy(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        return text
                .replace("&0", "{#000000}").replace("&1", "{#0000aa}")
                .replace("&2", "{#00aa00}").replace("&3", "{#00aaaa}")
                .replace("&4", "{#aa0000}").replace("&5", "{#aa00aa}")
                .replace("&6", "{#ffaa00}").replace("&7", "{#aaaaaa}")
                .replace("&8", "{#555555}").replace("&9", "{#5555ff}")
                .replace("&a", "{#55ff55}").replace("&b", "{#55ffff}")
                .replace("&c", "{#ff5555}").replace("&d", "{#ff55ff}")
                .replace("&e", "{#ffff55}").replace("&f", "{#ffffff}");
    }
}
