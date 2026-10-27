package com.testing.aproxy.webui;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Job events the API can send, named as AutoSys {@code sendevent -E} names them. Quick View
 * offers them in the dropdown of the search results; an option is recognised by its label
 * ("Force Start" or "Force Start Job"). Some are sent through "Send Event", whose dialog has its
 * own event dropdown ({@link #dialogEvent()}).
 */
public enum JobAction {

    FORCE_STARTJOB("Force Start", null),
    STARTJOB("Start", null),
    KILLJOB("Kill", null),
    /* with a status: Running, Starting, Success, Failure, Inactive, Terminated (as the dialog offers) */
    CHANGE_STATUS("Send Event", "Change Status");

    private final String label;
    private final @Nullable String dialogEvent;

    JobAction(String label, @Nullable String dialogEvent) {
        this.label = label;
        this.dialogEvent = dialogEvent;
    }

    /** The option of the results' dropdown. */
    public String label() {
        return label;
    }

    /** The event in the Send Event dialog; null when the dropdown option is the event itself. */
    public @Nullable String dialogEvent() {
        return dialogEvent;
    }

    public boolean needsStatus() {
        return this == CHANGE_STATUS;
    }

    /** Whether a dropdown option means this action; case, spaces and punctuation do not matter. */
    public boolean matches(String optionLabel) {
        String option = letters(optionLabel);
        String own = letters(label);
        return option.equals(own) || option.equals(own + "job");
    }

    /** Whether an option label means {@code wanted}, ignoring case, spaces and punctuation. */
    public static boolean same(String optionLabel, String wanted) {
        return letters(optionLabel).equals(letters(wanted));
    }

    private static String letters(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
