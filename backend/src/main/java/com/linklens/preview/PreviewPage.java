package com.linklens.preview;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

import com.linklens.domain.ShortUrl;
import com.linklens.health.HealthMessages;
import com.linklens.health.StoredHealth;
import com.linklens.health.StoredHealth.HealthState;
import com.linklens.inspection.InspectionResult.Hop;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-rendered preview HTML. Every interpolated value goes through {@link #e}: titles and URLs come
 * from arbitrary third-party sites, so they are attacker-controlled. The page also ships with a CSP
 * that forbids all scripts, so a missed escape would still not execute.
 */
final class PreviewPage {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private PreviewPage() {
    }

    /** @param health null while the first check is still running (the page then refreshes itself) */
    static String render(ShortUrl link, String shortUrl, StoredHealth health, Instant now) {
        boolean expired = link.isExpiredAt(now);
        boolean checking = health == null;
        String host = hostOf(link.getOriginalUrl());

        StringBuilder rows = new StringBuilder();
        if (health != null && health.title() != null) {
            rows.append(row("Page title", e(health.title())));
        }
        if (health != null && health.finalUrl() != null && !health.finalUrl().equals(link.getOriginalUrl())) {
            int hops = Math.max(0, health.redirectChain().size() - 1);
            rows.append(row("Ends up at", "<span class=\"url\">" + e(health.finalUrl()) + "</span>"
                    + "<span class=\"sub\">after " + hops + (hops == 1 ? " redirect" : " redirects") + "</span>"));
        }
        if (health != null && health.finalUrl() != null) {
            rows.append(row("Connection", health.finalIsHttps()
                    ? pill("ok", "Encrypted (HTTPS)")
                    : pill("warn", "Not encrypted (HTTP)")));
        }
        rows.append(row("Destination status", status(health)));
        rows.append(row("Expires", expired
                ? pill("bad", "Expired " + relative(link.getExpiresAt(), now))
                : link.getExpiresAt() == null ? "Never" : e(DATE.format(link.getExpiresAt())) + " <span class=\"sub\">" + e(relative(link.getExpiresAt(), now)) + "</span>"));
        rows.append(row("Short link created", e(DATE.format(link.getCreatedAt()))));
        if (health != null && health.checkedAt() != null) {
            rows.append(row("Last checked", e(relative(health.checkedAt(), now))));
        }

        String chain = "";
        if (health != null && health.redirectChain().size() > 1) {
            StringBuilder hops = new StringBuilder();
            for (Hop hop : health.redirectChain()) {
                hops.append("<li><code>").append(hop.status() == null ? "—" : hop.status()).append("</code><span class=\"url\">")
                        .append(e(hop.url())).append("</span></li>");
            }
            chain = "<details><summary>Redirect chain (" + health.redirectChain().size() + " steps)</summary><ol>" + hops + "</ol></details>";
        }

        boolean broken = health != null && health.state() == HealthState.BROKEN;
        String action;
        if (expired) {
            action = "<p class=\"notice bad\">This link has expired and no longer redirects.</p>";
        } else {
            action = (broken ? "<p class=\"notice bad\">" + e(HealthMessages.describe(health)) + "</p>" : "")
                    + "<a class=\"btn" + (broken ? " secondary" : "") + "\" href=\"/" + e(link.getShortCode()) + "\" rel=\"noreferrer\">"
                    + (broken ? "Continue anyway" : "Continue to " + e(host)) + " →</a>";
        }

        return fill(TEMPLATE, Map.of(
                "refresh", checking ? "<meta http-equiv=\"refresh\" content=\"2\">" : "",
                "host", e(host),
                "url", e(link.getOriginalUrl()),
                "short", e(shortUrl),
                "shortDisplay", e(shortUrl.replaceFirst("^https?://", "")),
                "rows", rows.toString(),
                "chain", chain,
                "action", action));
    }

    static String notFound(String shortCode) {
        return fill(TEMPLATE_NOT_FOUND, Map.of("code", e(shortCode)));
    }

    /**
     * Single pass over the template only. Chained String.replace calls would re-scan inserted values,
     * so a destination titled "{{action}}" could splice markup into the page.
     */
    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 2048);
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private static String status(StoredHealth h) {
        if (h == null) {
            return pill("neutral", "Checking…");
        }
        return switch (h.state()) {
            case PENDING -> pill("neutral", "Not checked yet");
            case HEALTHY -> pill("ok", "Working");
            case WARNING -> pill("warn", "Warning") + "<span class=\"sub\">" + e(HealthMessages.describe(h)) + "</span>";
            case BROKEN -> pill("bad", "Broken"); // the full explanation is in the notice above the button
        };
    }

    private static String row(String label, String valueHtml) {
        return "<div class=\"row\"><dt>" + e(label) + "</dt><dd>" + valueHtml + "</dd></div>";
    }

    private static String pill(String kind, String text) {
        return "<span class=\"pill " + kind + "\">" + e(text) + "</span>";
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }

    static String relative(Instant then, Instant now) {
        Duration d = Duration.between(now, then);
        boolean future = !d.isNegative();
        long s = Math.abs(d.getSeconds());
        String amount = s < 60 ? "moments" : s < 3600 ? plural(s / 60, "minute") : s < 86400 ? plural(s / 3600, "hour")
                : plural(s / 86400, "day");
        if (amount.equals("moments")) {
            return future ? "in moments" : "just now";
        }
        return future ? "in " + amount : amount + " ago";
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private static String e(String s) {
        return s == null ? "" : htmlEscape(s, "UTF-8");
    }

    private static final String STYLE = """
            <style>
              :root { --paper:#f4f1ea; --surface:#fffdf8; --ink:#16151a; --soft:#4a4851; --muted:#7c7884; --line:#e2ddd1;
                      --accent:#e8492a; --accent-ink:#fffdf8; --ok:#2f7d4f; --ok-bg:#dcefe2; --warn:#9a6400; --warn-bg:#f6ead0;
                      --bad:#b4321c; --bad-bg:#f8ddd6; color-scheme: light; }
              @media (prefers-color-scheme: dark) {
                :root { --paper:#0e0e11; --surface:#17171c; --ink:#f3f0e9; --soft:#c4c0b8; --muted:#8a8792; --line:#26262d;
                        --accent:#ff6a48; --accent-ink:#160c09; --ok:#6fcf97; --ok-bg:#15291e; --warn:#f0b85a; --warn-bg:#2e2413;
                        --bad:#ff8a73; --bad-bg:#341712; color-scheme: dark; } }
              * { box-sizing: border-box; }
              body { margin:0; min-height:100vh; background:var(--paper); color:var(--ink);
                     font:15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; -webkit-font-smoothing:antialiased; }
              main { max-width:600px; margin:0 auto; padding:32px 16px 48px; }
              header { display:flex; align-items:center; gap:10px; margin-bottom:40px; font-weight:600; letter-spacing:-.01em; }
              header .tag { margin-left:auto; font:500 11px ui-monospace, monospace; color:var(--muted); border:1px solid var(--line);
                            border-radius:999px; padding:3px 10px; }
              .eyebrow { font-size:13px; color:var(--muted); margin:0 0 6px; }
              .eyebrow .code { font:500 13px ui-monospace, monospace; color:var(--soft); } /* codes are case-sensitive: never uppercase */
              h1 { font:400 clamp(34px, 8vw, 48px)/1.05 ui-serif, Georgia, serif; margin:0; letter-spacing:-.02em; overflow-wrap:anywhere; }
              .full { margin:12px 0 28px; color:var(--soft); overflow-wrap:anywhere; font:13px/1.5 ui-monospace, monospace; }
              .card { background:var(--surface); border:1px solid var(--line); border-radius:24px; padding:8px 20px; }
              .row { display:grid; grid-template-columns: 150px 1fr; gap:12px; padding:14px 0; border-bottom:1px solid var(--line); }
              .row:last-child { border-bottom:0; }
              dt { color:var(--muted); font-size:13px; } dd { margin:0; min-width:0; overflow-wrap:anywhere; }
              .sub { display:block; color:var(--muted); font-size:13px; margin-top:4px; }
              .url { font:13px/1.5 ui-monospace, monospace; overflow-wrap:anywhere; }
              .pill { display:inline-block; font-size:12px; font-weight:600; padding:2px 10px; border-radius:999px; }
              .pill.ok { background:var(--ok-bg); color:var(--ok); } .pill.warn { background:var(--warn-bg); color:var(--warn); }
              .pill.bad { background:var(--bad-bg); color:var(--bad); } .pill.neutral { background:var(--line); color:var(--soft); }
              details { margin-top:16px; color:var(--soft); font-size:13px; } summary { cursor:pointer; }
              ol { padding-left:20px; } li { margin:6px 0; } li code { margin-right:8px; color:var(--muted); }
              .actions { margin-top:28px; }
              .btn { display:inline-block; background:var(--accent); color:var(--accent-ink); text-decoration:none; font-weight:600;
                     padding:13px 22px; border-radius:16px; }
              .btn.secondary { background:transparent; color:var(--ink); border:1px solid var(--line); }
              .notice { padding:12px 16px; border-radius:14px; font-size:14px; margin:0 0 14px; }
              .notice.bad { background:var(--bad-bg); color:var(--bad); }
              footer { margin-top:40px; color:var(--muted); font-size:12px; }
              a:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
              @media (max-width:520px) { .row { grid-template-columns:1fr; gap:4px; } }
            </style>
            """;

    private static final String LOGO = """
            <svg width="26" height="26" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="9" fill="currentColor"/>
            <circle cx="16" cy="16" r="9" fill="none" stroke="var(--paper)" stroke-width="2"/><circle cx="16" cy="16" r="4" fill="var(--accent)"/></svg>
            """;

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">{{refresh}}
            <title>Where {{short}} goes · LinkLens</title>""" + STYLE + """
            </head><body><main>
            <header>""" + LOGO + """
            LinkLens<span class="tag">link preview</span></header>
            <p class="eyebrow"><span class="code">{{shortDisplay}}</span> goes to</p>
            <h1>{{host}}</h1>
            <p class="full">{{url}}</p>
            <dl class="card">{{rows}}</dl>
            {{chain}}
            <div class="actions">{{action}}</div>
            <footer>Preview any LinkLens link by adding <strong>+</strong> to the end of it. LinkLens checked this destination for you; it never stores who views this page.</footer>
            </main></body></html>
            """;

    private static final String TEMPLATE_NOT_FOUND = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex"><title>Link not found · LinkLens</title>""" + STYLE + """
            </head><body><main>
            <header>""" + LOGO + """
            LinkLens<span class="tag">link preview</span></header>
            <p class="eyebrow">Link preview</p>
            <h1>Link not found</h1>
            <p class="full">There is no LinkLens link with the code “{{code}}”.</p>
            </main></body></html>
            """;
}
