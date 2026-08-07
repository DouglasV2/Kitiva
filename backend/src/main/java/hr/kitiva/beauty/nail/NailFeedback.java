package hr.kitiva.beauty.nail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One answer to the two pilot questions on a finished nail result.
 *
 * <p><strong>Deliberately not analytics.</strong> No session id, no user id, no IP, no device string, no
 * event stream — two enum answers, the execution mode they were given for, the kit status if there was one,
 * and a timestamp. That is enough to learn whether the result matched what she pictured and whether she would
 * use or pay for it, which are the only two questions this pilot needs answered. Anything more would be a
 * tracking system wearing a feedback form, and it would need a consent story this pilot does not have.</p>
 *
 * <p>The prompt is stored because "was this what you pictured?" is unreadable without knowing what she asked
 * for. It is free text she typed about her own nails, it is not joined to any identity, and it is the same
 * text already sent to {@code /api/nail/parse}.</p>
 */
@Entity
@Table(name = "nail_feedback")
public class NailFeedback {

    /** Answer to "Je li ovo ono što si zamislila?" */
    public enum Match { DA, DJELOMICNO, NE }

    /** Answer to "Bi li koristila ili platila ovakav rezultat?" */
    public enum Intent { KORISTILA, PLATILA, NE_BIH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "matched_expectation", length = 16)
    private String matchedExpectation;

    @Column(name = "would_use", length = 16)
    private String wouldUse;

    /** "SALON" or "AT_HOME" — which result view she answered from. */
    @Column(name = "execution_mode", length = 16)
    private String executionMode;

    /** The kit status she was looking at, for at-home answers. Null for salon. */
    @Column(name = "kit_status", length = 48)
    private String kitStatus;

    @Column(name = "prompt", length = 500)
    private String prompt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NailFeedback() {
    }

    static NailFeedback of(Match match, Intent intent, String executionMode, String kitStatus,
                          String prompt, Instant now) {
        NailFeedback row = new NailFeedback();
        row.matchedExpectation = match == null ? null : match.name();
        row.wouldUse = intent == null ? null : intent.name();
        row.executionMode = trim(executionMode, 16);
        row.kitStatus = trim(kitStatus, 48);
        row.prompt = trim(prompt, 500);
        row.createdAt = now;
        return row;
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getMatchedExpectation() {
        return matchedExpectation;
    }

    public String getWouldUse() {
        return wouldUse;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public String getKitStatus() {
        return kitStatus;
    }

    public String getPrompt() {
        return prompt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
