package io.github.derlocke_ng.kiwi

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derlocke_ng.kiwi.core.ControlApi
import io.github.derlocke_ng.kiwi.core.KiwiException
import io.github.derlocke_ng.kiwi.core.Membership
import io.github.derlocke_ng.kiwi.core.Phone
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.lock.KeystoreVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where this phone stands with its fleet. */
sealed interface Session {
    /** Reading what the phone kept. */
    data object Starting : Session

    /** In no fleet: the join screen. */
    data class Out(val busy: Boolean = false, val problem: Problem? = null) : Session

    /** Joined, not logged in. [ask] says the screen lock is wanted now. */
    data class Locked(
        val membership: Membership,
        val busy: Boolean = false,
        val ask: Boolean = false,
        val problem: Problem? = null,
    ) : Session

    /** Logged in: [api] carries the token. */
    data class In(val membership: Membership, val api: ControlApi) : Session
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {
    private val phone = Phone(File(app.filesDir, "controller.json"), KeystoreVault(app))

    var session: Session by mutableStateOf(Session.Starting)
        private set

    init {
        viewModelScope.launch {
            val me = withContext(Dispatchers.IO) { phone.membership() }
            if (me == null) {
                session = Session.Out()
            } else {
                session = Session.Locked(me)
                logIn()
            }
        }
    }

    fun join(link: String, name: String) {
        if ((session as? Session.Out)?.busy != false) return
        session = Session.Out(busy = true)
        viewModelScope.launch {
            try {
                session = Session.Locked(withContext(Dispatchers.IO) { phone.join(link, name) })
                logIn()
            } catch (e: KiwiException) {
                session = Session.Out(problem = e.problem)
            }
        }
    }

    /**
     * Open the key and log in. Where the key is locked, the screen is told to ask for the screen
     * lock; [afterAsking] it is not asked a second time.
     */
    fun logIn(afterAsking: Boolean = false) {
        val now = session as? Session.Locked ?: return
        if (now.busy) return
        session = now.copy(busy = true, ask = false, problem = null)
        viewModelScope.launch {
            session = try {
                Session.In(now.membership, withContext(Dispatchers.IO) { phone.login() })
            } catch (e: KiwiException) {
                if (e.problem == Problem.Locked && !afterAsking) {
                    now.copy(busy = false, ask = true, problem = null)
                } else {
                    now.copy(busy = false, ask = false, problem = e.problem)
                }
            }
        }
    }

    /** The screen lock is being asked for, or was declined. */
    fun asked(declined: Boolean = false) {
        val now = session as? Session.Locked ?: return
        session = now.copy(ask = false, problem = if (declined) Problem.Locked else now.problem)
    }

    /** Leave the fleet on this phone: back to the join screen. */
    fun forget() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { phone.forget() }
            session = Session.Out()
        }
    }
}
