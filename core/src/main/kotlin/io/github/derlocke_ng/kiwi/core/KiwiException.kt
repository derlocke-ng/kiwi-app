package io.github.derlocke_ng.kiwi.core

/** Something the owner should read: the message is short and says what to do. */
class KiwiException(val problem: Problem, cause: Throwable? = null) : Exception(problem.name, cause)

enum class Problem {
    /** The text is not an invite link. */
    NotAnInvite,

    /** The name is not one the control node takes. */
    BadName,

    /** Nothing answers at the control node's address. */
    NoAnswer,

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
}
