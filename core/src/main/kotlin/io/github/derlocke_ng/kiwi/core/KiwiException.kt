package io.github.derlocke_ng.kiwi.core

/** Something the owner should read: the message is short and says what to do. */
class KiwiException(val problem: Problem, cause: Throwable? = null) : Exception(problem.name, cause) {
    /**
     * What the system said, for the problems of the connection: the error and what it came from,
     * as it is. Not for the owner to understand, but to tell someone who can.
     */
    val detail: String?
        get() {
            val first = cause ?: return null
            var last: Throwable = first
            while (last.cause != null && last.cause !== last) last = last.cause!!
            fun say(e: Throwable) = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            return (if (last === first) say(first) else say(first) + " / " + say(last)).take(300)
        }
}

enum class Problem {
    /** The text is not an invite link. */
    NotAnInvite,

    /** The name is not one the control node takes. */
    BadName,

    /** The phone finds no address for the control node's name. */
    NoName,

    /** Nothing answers at the control node's address. */
    NoAnswer,

    /** Something answers, but the connection could not be secured: the certificate is not the fleet's, or not for this name. */
    NotTrusted,

    /** The address answers, but not as a control node, or it has no CA yet. */
    NotAControlNode,

    /** The fleet there is not the one the invite is from. Nothing was sent. */
    WrongFleet,

    /** The invite is used, or older than fifteen minutes. */
    InviteRefused,

    /** The control node knows no controller by this name: removed, or never joined. */
    UnknownController,

    /** This key does not open the control node's challenge. */
    WrongKey,

    /** The token is no longer taken: log in again. */
    LoggedOut,

    /** The control node does not have what the app asks for: it is older than the app. */
    OldControlNode,

    /** The control node answered something else than expected. */
    BadAnswer,

    /** The phone has no screen lock, so it cannot guard the key. Nothing was sent. */
    NoScreenLock,

    /** The phone cannot guard a key by fingerprint or face alone: none is set up, or it is older than Android 11. */
    NoBiometric,

    /** The password is shorter than [MIN_PASSWORD] characters. Nothing was sent. */
    WeakPassword,

    /** The password does not open the key. */
    WrongPassword,

    /** The key rests wrapped: the fingerprint, face or screen lock opens it. */
    Locked,

    /** This way to unlock is the only one left: it stays. */
    LastLock,

    /** An error the app did not expect: its detail says what. */
    Unexpected,

    /** The wrapped key cannot be opened any more (the screen lock was removed, a new fingerprint added), or was never kept. */
    KeyLost,
}
