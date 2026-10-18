package com.linklens.health;

import com.linklens.health.StoredHealth.HealthState;

/** Plain-language explanations of a link's health, shared by the API and the preview page. */
public final class HealthMessages {

    private HealthMessages() {
    }

    public static String describe(StoredHealth h) {
        if (h.state() == HealthState.PENDING) {
            return "Not checked yet.";
        }
        if (h.state() == HealthState.HEALTHY) {
            return "The destination is up and responding normally.";
        }
        String what = switch (h.issue() == null ? "" : h.issue()) {
            case HealthEvaluator.TITLE_CHANGED -> "The page's title changed from “" + h.baselineTitle() + "” to “"
                    + h.title() + "”. The page this link pointed to may have moved or been replaced.";
            case "NOT_FOUND" -> h.httpStatus() != null && h.httpStatus() == 410
                    ? "The destination page has been removed (410)."
                    : "The destination returns “page not found” (404).";
            case "SERVER_ERROR" -> "The destination's server is failing (" + h.httpStatus() + ").";
            case "CLIENT_ERROR" -> "The destination rejects requests (" + h.httpStatus() + ").";
            case "ACCESS_RESTRICTED" -> "The site blocks automated checks (" + h.httpStatus()
                    + "), so it couldn't be verified. It may still work in a browser.";
            case "REDIRECT_LOOP" -> "The destination redirects in a loop and never loads.";
            case "TOO_MANY_REDIRECTS" -> "The destination redirects too many times.";
            case "INVALID_REDIRECT" -> "The destination redirects to an invalid address.";
            case "DNS_FAILURE" -> "The destination's domain no longer exists.";
            case "TIMEOUT" -> "The destination took too long to respond.";
            case "TLS_ERROR" -> "The destination's secure connection is broken (certificate or TLS error).";
            case "UNREACHABLE" -> "The destination's server can't be reached.";
            case "BLOCKED" -> "The destination points to a private network address, so it wasn't checked.";
            default -> "There is a problem with the destination.";
        };
        if (h.state() == HealthState.BROKEN) {
            return "Your link points to a dead page. " + what + " (failed " + h.consecutiveFailures() + " checks in a row)";
        }
        return what;
    }
}
