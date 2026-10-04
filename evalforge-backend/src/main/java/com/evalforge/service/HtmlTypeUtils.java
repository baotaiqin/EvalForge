package com.evalforge.service;

/** Identifies HTML reference answers used by report-comparison evaluations. */
public final class HtmlTypeUtils {

    private HtmlTypeUtils() {
    }

    public static boolean isHtmlExpectedType(String expectedType) {
        return "html".equalsIgnoreCase(expectedType) || "htm".equalsIgnoreCase(expectedType);
    }
}
