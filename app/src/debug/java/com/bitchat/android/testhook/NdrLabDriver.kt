package com.bitchat.android.testhook

import android.app.Application
import android.content.Intent
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.NdrFeatureGate
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.nostr.NdrNostrService
import com.bitchat.android.nostr.NdrPairwiseRuntime
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.util.hexEncodedString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Commands observe production state; a send admission alone is never a receive assertion. */
object NdrLabDriver {
    const val PREFIX = "ndr-lab-"
    private lateinit var application: Application
    private lateinit var mesh: MeshService
    private lateinit var viewModel: ChatViewModel
    val isStarted: Boolean get() = ::mesh.isInitialized

    fun start(app: Application, service: MeshService, chatViewModel: ChatViewModel) {
        application = app
        mesh = service
        viewModel = chatViewModel
    }

    suspend fun execute(op: String, intent: Intent): JSONObject = withContext(Dispatchers.Main) {
        check(isStarted) { "Launch NdrLabActivity first" }
        if (op == "status") return@withContext status()
        if (op == "allow_peer") {
            val peer = requireNotNull(intent.getStringExtra("peer_id"))
            val noiseKey = requireNotNull(intent.getStringExtra("noise_pubkey"))
            val nickname = requireNotNull(intent.getStringExtra("nickname"))
            require(noiseKey.matches(Regex("[0-9a-f]{64}")) && nickname.startsWith(PREFIX))
            val info = requireNotNull(mesh.getPeerInfo(peer)) { "Discover the peer first" }
            check(info.nickname == nickname && info.noisePublicKey?.hexEncodedString() == noiseKey &&
                info.hasVerifiedAnnouncement && info.verifiedAnnouncementNoisePublicKey?.hexEncodedString() == noiseKey) {
                "Approval does not match the peer's signed announcement"
            }
            check(approvals().edit().putString(noiseKey, nickname).commit())
            return@withContext status()
        }
        if (op == "ble_stop" || op == "ble_start") {
            DebugSettingsManager.getInstance().setBleEnabled(op == "ble_start")
            return@withContext status()
        }
        check(unknownPeerCount() == 0) { "Unexpected nearby peer; stop the lab before sending" }
        val peer = requireNotNull(intent.getStringExtra("peer_id"))
        val info = mesh.getPeerInfo(peer)
        val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(peer)
        val nickname = requireNotNull(info?.nickname ?: relationship?.peerNickname)
        check(nickname.startsWith(PREFIX)) { "Only synthetic lab peers are allowed" }
        val noiseKey = requireNotNull(info?.noisePublicKey ?: relationship?.peerNoisePublicKey)
        check(approvals().getString(noiseKey.hexEncodedString(), null) == nickname) {
            "The host must explicitly approve this lab Noise key and nickname first"
        }
        when (op) {
            "handshake" -> {
                check(info != null) { "Discover the peer first" }
                mesh.initiateNoiseHandshake(peer)
                status()
            }
            "favorite" -> {
                check(info != null && mesh.hasEstablishedSession(peer)) { "Authenticate the peer first" }
                if (relationship?.isFavorite != true) viewModel.toggleFavorite(peer)
                status()
            }
            "ble_send", "ndr_send" -> {
                val id = requireNotNull(intent.getStringExtra("msg_id"))
                val content = requireNotNull(intent.getStringExtra("content"))
                require(id.startsWith(PREFIX) && content.startsWith("ndr-lab:"))
                require(id.toByteArray(Charsets.UTF_8).size <= 255 &&
                    content.toByteArray(Charsets.UTF_8).size <= 255)
                val response = if (op == "ble_send") {
                    check(mesh.hasEstablishedSession(peer)) { "Noise session is not established" }
                    mesh.sendPrivateMessage(content, peer, nickname, id)
                    JSONObject().put("status", "ok").put("admission", "submitted")
                } else {
                    val result = CompletableDeferred<String>()
                    NostrTransport.getInstance(application).sendPrivateMessage(
                        content, peer, nickname, id,
                        completion = { result.complete(it.name) }
                    )
                    JSONObject().put("status", "ok")
                        .put("admission", withTimeout(20_000) { result.await() })
                }
                response.put("msg_id", id)
            }
            else -> error("Unknown NDR lab operation")
        }
    }

    private fun approvals() = application.getSharedPreferences("ndr-lab-approved-peers", 0)

    private fun isApproved(info: PeerInfo): Boolean =
        info.noisePublicKey?.hexEncodedString()?.let { key ->
            approvals().getString(key, null) == info.nickname && info.hasVerifiedAnnouncement &&
                info.verifiedAnnouncementNoisePublicKey?.hexEncodedString() == key
        } == true

    private fun unknownPeerCount(): Int = AppStateStore.peers.value.count { peer ->
        mesh.getPeerInfo(peer)?.let(::isApproved) != true
    }

    private fun isSendReady(ndr: NdrNostrService, key: String?): Boolean = synchronized(ndr) {
        if (key == null || !NdrFeatureGate.isEnabled()) return@synchronized false
        runCatching {
            field<NdrPairwiseRuntime?>(ndr, "pairwiseRuntime")?.sessionInfo(key)?.sendReady == true
        }.getOrDefault(false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T

    private fun status(): JSONObject {
        val favorites = FavoritesPersistenceService.shared
        val ndr = NdrNostrService.getInstance(application)
        val active = AppStateStore.peers.value.mapNotNull { mesh.getPeerInfo(it) }
            .filter { it.nickname.startsWith(PREFIX) }
        val peers = JSONArray()
        active.forEach { info ->
            val relationship = info.noisePublicKey?.let(favorites::getFavoriteStatus)
            val ndrKey = relationship?.peerNdrSessionPubkeyHex
            peers.put(JSONObject()
                .put("peer_id", info.id)
                .put("contact_id", info.noisePublicKey?.hexEncodedString())
                .put("noise_pubkey", info.noisePublicKey?.hexEncodedString())
                .put("nickname", info.nickname)
                .put("approved", isApproved(info))
                .put("signed_announcement", info.hasVerifiedAnnouncement)
                .put("direct", info.isDirectConnection)
                .put("authenticated", mesh.hasEstablishedSession(info.id))
                .put("ndr_capability", mesh.peerSupportsAuthenticatedCapability(info.id, PeerCapabilities.NOSTR_DOUBLE_RATCHET))
                .put("mutual_favorite", relationship?.isMutual == true)
                .put("pinned", relationship?.ndrRequired == true)
                .put("pairwise_exists", ndrKey?.let(ndr::hasPairwiseSession) == true)
                .put("ndr_ready", isSendReady(ndr, ndrKey)))
        }
        val contacts = JSONArray()
        favorites.getAllRelationships().filter {
            approvals().getString(it.peerNoisePublicKey.hexEncodedString(), null) == it.peerNickname
        }.forEach { relationship ->
            contacts.put(JSONObject()
                .put("peer_id", relationship.peerNoisePublicKey.hexEncodedString())
                .put("nickname", relationship.peerNickname)
                .put("mutual_favorite", relationship.isMutual)
                .put("pinned", relationship.ndrRequired)
                .put("pairwise_exists", relationship.peerNdrSessionPubkeyHex?.let(ndr::hasPairwiseSession) == true)
                .put("ndr_ready", isSendReady(ndr, relationship.peerNdrSessionPubkeyHex)))
        }
        val received = JSONArray()
        AppStateStore.privateMessages.value.values.flatten()
            .filter { it.id.startsWith(PREFIX) && it.content.startsWith("ndr-lab:") }
            .forEach { message ->
                received.put(JSONObject()
                    .put("id", message.id)
                    .put("content", message.content)
                    .put("sender", message.sender)
                    .put("sender_peer_id", message.senderPeerID)
                    .put("transport", if (message.senderNostrPubkey != null) "nostr" else "ble"))
            }
        val connectionManager = requireNotNull(MeshServiceHolder.meshService).connectionManager
        val client = field<Any>(connectionManager, "clientManager")
        val server = field<Any>(connectionManager, "serverManager")
        val ble = JSONObject()
            .put("enabled", DebugSettingsManager.getInstance().bleEnabled.value)
            .put("scanning", field<Boolean>(client, "isCurrentlyScanning"))
            .put("client_active", field<Boolean>(client, "isActive"))
            .put("server_active", field<Boolean>(server, "isActive"))
            .put("gatt_server_open", field<Any?>(server, "gattServer") != null)
            .put("connected_links", connectionManager.getConnectedDeviceCount())
        return JSONObject().put("status", "ok")
            .put("peer_id", mesh.myPeerID)
            .put("noise_pubkey", mesh.getStaticNoisePublicKey()?.hexEncodedString())
            .put("nickname", AppStateStore.nickname.value)
            .put("peers", peers)
            .put("contacts", contacts)
            .put("unexpected_peer_count", unknownPeerCount())
            .put("ble", ble)
            .put("received", received)
            .put("relay_connected", NostrRelayManager.getInstance(application).isConnected.value)
    }
}
