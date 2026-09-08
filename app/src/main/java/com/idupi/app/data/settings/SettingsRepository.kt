package com.idupi.app.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the Settings toggles the server-side spawn
 * path reads (currently just the OpenCode auto-approve gate).
 *
 * The OpenCode auto-approve toggle ships OFF by default: a fresh install
 * surfaces permission cards instead of silently self-approving with
 * `opencode run --auto`. See `opencode-permission-sidecar/spec.md`
 * ("Auto-Approve Toggle" / "Toggle OFF" scenario) and `design.md` D8.
 */
interface SettingsRepository {

    /**
     * Observed OpenCode auto-approve setting. `true` means the spawn path
     * is allowed to launch `opencode run --auto` (legacy autopilot); `false`
     * means cards are surfaced through the sidecar. Defaults to `false`.
     */
    val opencodeAutoApprove: Flow<Boolean>

    /**
     * Persists the user's toggle choice. The Flow MUST emit the new value
     * to every collector on completion so the Settings UI and the chat
     * header stay in sync.
     */
    suspend fun setOpencodeAutoApprove(value: Boolean)
}

/**
 * In-memory [SettingsRepository] backed by a [MutableStateFlow]. Used by
 * tests and as the default when no DataStore-backed repository has been
 * wired in (e.g. unit-test ViewModels).
 *
 * Production uses [DataStoreSettingsRepository] for crash-safe persistence.
 */
class InMemorySettingsRepository : SettingsRepository {

    private val _opencodeAutoApprove = MutableStateFlow(false)

    override val opencodeAutoApprove: Flow<Boolean> = _opencodeAutoApprove.asStateFlow()

    override suspend fun setOpencodeAutoApprove(value: Boolean) {
        _opencodeAutoApprove.value = value
    }
}
