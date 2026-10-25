package com.testing.aproxy.webui;

import java.util.Locale;

/**
 * Job events the API can send, named as AutoSys {@code sendevent -E} names them. Quick View
 * offers them in the dropdown of the search results; an option is recognised by its label
 * ("Force Start" or "Force Start Job").
 */
public enum JobAction {

    FORCE_STARTJOB("Force Start"),
    STARTJOB("Start"),
    KILLJOB("Kill");

    private final String label;

    JobAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether a dropdown option means this action; case, spaces and punctuation do not matter. */
    public boolean matches(String optionLabel) {
        String option = letters(optionLabel);
        String own = letters(label);
        return option.equals(own) || option.equals(own + "job");
    }

    private static String letters(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
