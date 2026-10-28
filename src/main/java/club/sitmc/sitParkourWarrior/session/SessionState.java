package club.sitmc.sitParkourWarrior.session;

public enum SessionState {
    /** Player is actively running a course segment. */
    RUNNING,
    /** Player has passed this segment's end and is waiting to step into the next node. */
    AWAITING_HANDOFF
}
