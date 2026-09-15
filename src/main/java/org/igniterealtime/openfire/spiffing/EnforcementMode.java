package org.igniterealtime.openfire.spiffing;

/** Controls what happens to an inbound message whose security label fails validation or access control. */
public enum EnforcementMode {
    /** Log the violation and let the message through unchanged. Safe default for staged rollout. */
    WARN,
    /** Reject the message with a {@code forbidden} error, as in prior releases. */
    ENFORCE
}
