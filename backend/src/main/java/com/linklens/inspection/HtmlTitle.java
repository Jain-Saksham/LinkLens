package com.linklens.inspection;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls a clean, bounded {@code <title>} out of partial HTML. Not a parser, and doesn't need to be. */
public final class HtmlTitle {

    static final int MAX_LENGTH = 300;

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");
    private static final Map<String, String> NAMED = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ",
            "mdash", "—", "ndash", "–", "hellip", "…", "middot", "·");

    private HtmlTitle() {
    }

    public static String extract(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = TITLE.matcher(html);
        if (!m.find()) {
            return null;
        }
        String title = decodeEntities(m.group(1)).replaceAll("\\s+", " ").strip();
        if (title.isEmpty()) {
            return null;
        }
        return title.length() > MAX_LENGTH ? title.substring(0, MAX_LENGTH - 1) + "…" : title;
    }

    static String decodeEntities(String text) {
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String entity = m.group(1);
            String replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                replacement = codePoint(Integer.parseInt(entity.substring(2), 16), m.group());
            } else if (entity.startsWith("#")) {
                replacement = codePoint(Integer.parseInt(entity.substring(1)), m.group());
            } else {
                replacement = NAMED.getOrDefault(entity.toLowerCase(), m.group());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String codePoint(int cp, String original) {
        return Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : original;
    }
}
