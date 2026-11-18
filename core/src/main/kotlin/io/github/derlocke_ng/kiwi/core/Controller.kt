package io.github.derlocke_ng.kiwi.core

import java.util.Base64

/** The fleet as a joining phone first sees it: where it is, its CA and the store's id. */
data class Fleet(val url: String, val caPem: String, val store: String)

/** This phone as a controller of a fleet. Nothing here is secret. */
data class Membership(val url: String, val name: String, val store: String, val caPem: String)

object Controller {
    /**
     * The first half of joining: fetch the fleet's CA and check it against the invite. Nothing
     * of this phone is sent. The connection is not trusted yet; the invite's code decides.
     */
    fun fleetOf(invite: Invite): Fleet {
        val answer = ControlApi(invite.url, caPem = null).call("GET", "/api/ca")
        val ca = answer.text("ca")
        val store = answer.text("store")
        if (answer.status != 200 || ca.isNullOrEmpty() || store.isNullOrEmpty()) {
            throw KiwiException(Problem.NotAControlNode)
        }
        if (!invite.isFrom(ca)) throw KiwiException(Problem.WrongFleet)
        return Fleet(invite.url, ca, store)
    }

    /**
     * The second half: send the public key with the invite's code, over a connection checked
     * against the fleet's CA. Returns the membership, with the name the control node gave
     * (a taken name gets a number).
     */
    fun join(invite: Invite, fleet: Fleet, name: String, key: AgeKey): Membership {
        if (!isControllerName(name)) throw KiwiException(Problem.BadName)
        val answer = ControlApi(fleet.url, fleet.caPem)
            .call("POST", "/api/join", jsonOf("code" to invite.code, "name" to name, "key" to key.public))
        val got = answer.text("name")
        if (answer.status != 200 || got.isNullOrEmpty()) throw KiwiException(Problem.InviteRefused)
        return Membership(fleet.url, got, fleet.store, fleet.caPem)
    }

    /** Log in: open the control node's challenge with this phone's key. The token in it is the pass. */
    fun login(membership: Membership, key: AgeKey): ControlApi {
        val api = ControlApi(membership.url, membership.caPem)
        val answer = api.call("POST", "/api/controller/hello", jsonOf("name" to membership.name))
        val challenge = answer.text("challenge")
        if (answer.status != 200 || challenge == null) throw KiwiException(Problem.UnknownController)
        val blob = try {
            Base64.getDecoder().decode(challenge)
        } catch (e: IllegalArgumentException) {
            throw KiwiException(Problem.UnknownController, e)
        }
        api.token = key.open(blob).decodeToString().trim()
        return api
    }
}
