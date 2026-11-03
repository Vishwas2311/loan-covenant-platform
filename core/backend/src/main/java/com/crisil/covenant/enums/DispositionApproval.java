package com.crisil.covenant.enums;

/**
 * FR-12 maker-checker state of a Waiver/Extension disposition. Only {@link #APPROVED} changes the
 * covenant's effective status; {@link #REQUESTED} waits for an approver, {@link #REJECTED} leaves the
 * breach standing.
 */
public enum DispositionApproval {
    REQUESTED,
    APPROVED,
    REJECTED,
    /** The requester took the request back before it was decided; the breach stands. */
    WITHDRAWN
}
