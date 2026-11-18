package io.github.derlocke_ng.kiwi

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derlocke_ng.kiwi.core.AgeKey
import io.github.derlocke_ng.kiwi.core.ControlApi
import io.github.derlocke_ng.kiwi.core.Controller
import io.github.derlocke_ng.kiwi.core.KiwiException
import io.github.derlocke_ng.kiwi.core.Lock
import io.github.derlocke_ng.kiwi.core.Look
import io.github.derlocke_ng.kiwi.core.Membership
import io.github.derlocke_ng.kiwi.core.Network
import io.github.derlocke_ng.kiwi.core.Phone
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.core.Reading
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
    data class Out(val busy: Boolean = false, val problem: Problem? = null, val detail: String? = null) : Session

    /** Joined, the key locked. [locks] are the ways to open it; [ask] the one the owner is wanted for now. */
    data class Locked(
        val membership: Membership,
        val locks: Set<Lock>,
        val busy: Boolean = false,
        val ask: Lock? = null,
        val problem: Problem? = null,
        val detail: String? = null,
    ) : Session

    /** Unlocked and logged in: [api] carries the token, [key] is the opened key. Both live in memory only. */
    data class In(
        val membership: Membership,
        val api: ControlApi,
        val key: AgeKey,
        val locks: Set<Lock>,
        val busy: Boolean = false,
        val problem: Problem? = null,
    ) : Session
}

/** What was read from the control node since the last unlock. */
data class Seen(
    val loading: Boolean = false,
    val network: Network? = null,
    val look: Look? = null,
    val problem: Problem? = null,
    val detail: String? = null,
)

class SessionViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Android 17: the first to keep apps off the local network until the owner allows it. */
        const val LOCAL_NETWORK_SINCE = 37
    }

    private val vault = KeystoreVault(app)
    private val phone = Phone(File(app.filesDir, "controller.json"), vault)

    var session: Session by mutableStateOf(Session.Starting)
        private set

    var seen: Seen by mutableStateOf(Seen())
        private set

    private val crashFile = File(app.filesDir, MainActivity.CRASH_FILE)

    /** What the app said when it last stopped by itself, until the owner has seen it. */
    var crash: String? by mutableStateOf(runCatching { crashFile.takeIf { it.isFile }?.readText() }.getOrNull())
        private set

    fun crashSeen() {
        crashFile.delete()
        crash = null
    }

    init {
        viewModelScope.launch {
            val kept = runCatching { io { phone.membership()?.let { it to phone.locks() } } }.getOrNull()
            if (kept == null) {
                session = Session.Out()
            } else {
                session = Session.Locked(kept.first, kept.second)
            }
        }
    }

    /**
     * Off the main thread. An error nobody expected (a Keystore that behaves in its own way)
     * becomes a problem with its detail, shown where it happened, instead of stopping the app.
     */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: KiwiException) {
            throw e
        } catch (e: Exception) {
            throw KiwiException(Problem.Unexpected, e)
        }
    }

    private var triedByItself = false

    /**
     * Once per start of the app: try the locks that need no typing right away. Called by the
     * screen, after the phone was asked to let the app onto the local network.
     */
    fun unlockByItself() {
        if (triedByItself) return
        triedByItself = true
        val now = session as? Session.Locked ?: return
        listOf(Lock.Biometric, Lock.ScreenLock).firstOrNull { it in now.locks }?.let { unlock(it) }
    }

    /**
     * What to tell the owner. A connection that failed while the phone keeps the app off the
     * local network is most likely that: Android gives no better sign of it than no answer or
     * a blank address.
     */
    private fun KiwiException.told(): Problem {
        val app = getApplication<Application>()
        val shutOut = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SINCE &&
            app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        return if (shutOut && problem in setOf(Problem.NoAnswer, Problem.BlankAddress, Problem.NoName)) Problem.NoLocalNetwork else problem
    }

    /** Whether the phone can guard the key this way now. */
    fun can(lock: Lock): Boolean = vault.can(lock)

    fun join(link: String, name: String, password: String?) {
        if ((session as? Session.Out)?.busy != false) return
        session = Session.Out(busy = true)
        viewModelScope.launch {
            triedByItself = true
            val (me, key) = try {
                io { phone.join(link, name, password) }
            } catch (e: KiwiException) {
                session = Session.Out(problem = e.told(), detail = e.detail)
                return@launch
            }
            val locks = runCatching { io { phone.locks() } }.getOrDefault(emptySet())
            session = try {
                Session.In(me, io { Controller.login(me, key) }, key, locks)
            } catch (e: KiwiException) {
                Session.Locked(me, locks, problem = e.told(), detail = e.detail)
            }
            refresh()
        }
    }

    /**
     * Open the key by [lock] and log in. Where the lock wants the owner asked first, the screen
     * is told to; [afterAsking] it is not asked a second time.
     */
    fun unlock(lock: Lock, password: String? = null, afterAsking: Boolean = false) {
        val now = session as? Session.Locked ?: return
        if (now.busy) return
        session = now.copy(busy = true, ask = null, problem = null, detail = null)
        viewModelScope.launch {
            session = try {
                io {
                    val key = phone.open(lock, password)
                    Session.In(now.membership, Controller.login(now.membership, key), key, now.locks)
                }
            } catch (e: KiwiException) {
                if (e.problem == Problem.Locked && !afterAsking) {
                    now.copy(busy = false, ask = lock, problem = null, detail = null)
                } else {
                    now.copy(busy = false, ask = null, problem = e.told(), detail = e.detail)
                }
            }
            refresh()
        }
    }

    /** Read the machines and the last look again. A token that is no longer taken locks the app. */
    fun refresh() {
        val now = session as? Session.In ?: return
        if (seen.loading) return
        seen = seen.copy(loading = true, problem = null, detail = null)
        viewModelScope.launch {
            try {
                seen = io {
                    // the machines first: a control node too old for the app says so here
                    Seen(network = Reading.network(now.api), look = Reading.look(now.api))
                }
            } catch (e: KiwiException) {
                if (e.problem == Problem.LoggedOut) {
                    seen = Seen()
                    if (session == now) session = Session.Locked(now.membership, now.locks, problem = e.told())
                } else {
                    seen = seen.copy(loading = false, problem = e.told(), detail = e.detail)
                }
            }
        }
    }

    /** The owner is being asked, or declined. */
    fun asked(declined: Boolean = false) {
        val now = session as? Session.Locked ?: return
        session = if (declined) now.copy(ask = null, problem = Problem.Locked, detail = null) else now.copy(ask = null)
    }

    /** Set a lock (with another [password], set it anew), or take it away. Only while unlocked. */
    fun setLock(lock: Lock, on: Boolean, password: String? = null) {
        val now = session as? Session.In ?: return
        if (now.busy) return
        session = now.copy(busy = true, problem = null)
        viewModelScope.launch {
            session = try {
                io {
                    if (on) phone.set(lock, now.key, password) else phone.remove(lock)
                    now.copy(busy = false, locks = phone.locks())
                }
            } catch (e: KiwiException) {
                now.copy(busy = false, problem = e.told())
            }
        }
    }

    /** Lock: the token and the opened key are dropped. */
    fun lock() {
        val now = session as? Session.In ?: return
        triedByItself = true
        session = Session.Locked(now.membership, now.locks)
        seen = Seen()
    }

    /** Leave the fleet on this phone: back to the join screen. */
    fun forget() {
        viewModelScope.launch {
            runCatching { io { phone.forget() } }
            session = Session.Out()
            seen = Seen()
        }
    }
}
