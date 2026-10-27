package com.testing.aproxy.webui;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Job events the API can send, named as AutoSys {@code sendevent -E} names them. Quick View
 * offers them in the dropdown of the search results; an option is recognised by its label
 * ("Force Start" or "Force Start Job"). Some are sent through that dropdown's "Send Event",
 * whose dialog has its own event dropdown ({@link #dialogEvent()}).
 */
public enum JobAction {

    FORCE_STARTJOB("Force Start", null),
    STARTJOB("Start", null),
    KILLJOB("Kill", null),
    /* with a status: Running, Starting, Success, Failure, Inactive, Terminated (as the dialog offers) */
    CHANGE_STATUS(null, "Change Status");

    /** The option of the results' dropdown that opens the Send Event dialog. */
    public static final String SEND_EVENT = "Send Event";

    private final @Nullable String label;
    private final @Nullable String dialogEvent;

    JobAction(@Nullable String label, @Nullable String dialogEvent) {
        this.label = label;
        this.dialogEvent = dialogEvent;
    }

    /** The option of the results' dropdown; null when the event is sent through Send Event. */
    public @Nullable String label() {
        return label;
    }

    /** The event in the Send Event dialog; null when the dropdown option is the event itself. */
    public @Nullable String dialogEvent() {
        return dialogEvent;
    }

    /** How the action is named in messages. */
    public String title() {
        return label != null ? label : dialogEvent != null ? dialogEvent : name();
    }

    public boolean needsStatus() {
        return this == CHANGE_STATUS;
    }

    /** Whether a dropdown option means this action; case, spaces and punctuation do not matter. */
    public boolean matches(String optionLabel) {
        return label != null && offers(optionLabel, label);
    }

    /** Whether a dropdown option is {@code label}, with or without "Job". */
    public static boolean offers(String optionLabel, String label) {
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
