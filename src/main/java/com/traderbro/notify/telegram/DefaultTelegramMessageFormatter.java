package com.traderbro.notify.telegram;

import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link TelegramMessageFormatter}: loads an HTML template per notification type from
 * {@code classpath:telegram/templates/<TYPE>.txt} and substitutes {@code ${key}} placeholders
 * from the event payload. Numbers are grouped with spaces; the trailing {@code ${ts}} (if the
 * payload does not supply it) is rendered in Europe/Moscow.
 */
@Slf4j
public class DefaultTelegramMessageFormatter implements TelegramMessageFormatter {

    private static final String TEMPLATE_DIR = "telegram/templates/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter MSK_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(MSK);

    private final Map<NotificationType, String> cache = new ConcurrentHashMap<>();
    private final String fallbackTemplate;

    public DefaultTelegramMessageFormatter() {
        this.fallbackTemplate = """
                ${levelMark} <b>${type}</b>
                ━━━━━━━━━━━━━━━
                ${message}
                Время: <code>${ts}</code>""";
    }

    @Override
    public String format(TraderEvent event) {
        String template = load(event.type());
        String text = substitute(template, event);
        // Replace any leftover unresolved placeholders with a dash.
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("—"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String substitute(String template, TraderEvent event) {
        Map<String, Object> p = event.payload();
        String text = template;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value;
            if ("ts".equals(key)) {
                value = p.get("ts") != null ? String.valueOf(p.get("ts")) : MSK_FMT.format(event.at());
            } else if ("type".equals(key)) {
                value = event.type().name();
            } else if ("levelMark".equals(key)) {
                value = switch (event.level()) {
                    case INFO -> "🟢";
                    case WARNING -> "🟡";
                    case CRITICAL -> "🔴";
                };
            } else {
                value = fmt(p.get(key));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String fmt(Object v) {
        if (v == null) {
            return "—";
        }
        if (v instanceof Number n) {
            return group(n);
        }
        return String.valueOf(v);
    }

    private String group(Number n) {
        DecimalFormat df = new DecimalFormat("#,##0.######", new DecimalFormatSymbols(Locale.US));
        df.getDecimalFormatSymbols().setGroupingSeparator(' ');
        df.applyPattern("#,##0.######");
        return df.format(n);
    }

    private String load(NotificationType type) {
        return cache.computeIfAbsent(type, t -> read(type));
    }

    private String read(NotificationType type) {
        String path = TEMPLATE_DIR + type.name() + ".txt";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                log.warn("no telegram template for {}, using fallback", type);
                return fallbackTemplate;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("failed to read telegram template {}: {}", path, e.getMessage());
            return fallbackTemplate;
        }
    }
}