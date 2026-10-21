package com.bitchat.android.testhook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.bitchat.android.BuildConfig
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.model.NdrFeatureGate
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.nostr.BitchatNdrRelayAdapter
import com.bitchat.android.nostr.NdrNostrService
import com.bitchat.android.nostr.NdrPanicStartupRecovery
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.wifiaware.WifiAwareController
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/** Disposable debug-lab startup. No Tor, public relay directory, or location startup. */
class NdrLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.DEBUG)
        NdrFeatureGate.setEnabledForTests(true)
        check(NdrPanicStartupRecovery.recoverBeforeNetwork(this))

        // Install the network boundary before constructing any transport or ViewModel.
        val localClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .dns { host ->
                check(host == "127.0.0.1") { "Lab DNS permits loopback only" }
                listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }
            .addInterceptor { chain ->
                val url = chain.request().url
                check(url.host == "127.0.0.1" && url.port == 18762) {
                    "Lab networking permits the controlled loopback relay only"
                }
                chain.proceed(chain.request())
            }
            .build()
        field<AtomicReference<OkHttpClient?>>(OkHttpProvider, "wsClientRef")
            .set(localClient)
        field<AtomicReference<OkHttpClient?>>(OkHttpProvider, "privateWsClientRef")
            .set(localClient)
        field<AtomicReference<OkHttpProvider.RoutedClient?>>(OkHttpProvider, "httpClientRef")
            .set(OkHttpProvider.RoutedClient(localClient, OkHttpProvider.Route.DIRECT))

        val relay = NostrRelayManager.getInstance(this)
        field<MutableList<NostrRelayManager.Relay>>(relay, "relaysList").apply {
            clear()
            add(NostrRelayManager.Relay(LOCAL_RELAY))
        }
        field<MutableSet<String>>(relay, "nonLiveRelayUrls").apply {
            clear()
            add(LOCAL_RELAY)
        }
        field<MutableStateFlow<List<NostrRelayManager.Relay>>>(relay, "_relays").value =
            listOf(NostrRelayManager.Relay(LOCAL_RELAY))
        val ndr = NdrNostrService.getInstance(this)
        ndr.javaClass.getDeclaredField("relayManager").apply { isAccessible = true }
            .set(ndr, BitchatNdrRelayAdapter(relay, listOf(LOCAL_RELAY)))

        DataManager(this).apply {
            saveLocationServicesEnabled(false)
            if (!loadNickname().startsWith(NdrLabDriver.PREFIX)) {
                saveNickname("ndr-lab-android")
            }
        }
        LiveLocationPrivacyGate.update(false)
        DebugPreferenceManager.init(this)
        DebugPreferenceManager.setWifiAwareEnabled(false)
        WifiAwareController.initialize(this, false)
        PowerManager.getInstance(this).start()
        FavoritesPersistenceService.initialize(this)
        FavoritesPersistenceService.shared.setNdrPeerRetirementGuard { oldPeer ->
            val identity = NostrIdentityBridge.getCurrentNostrIdentity(this)
                ?: return@setNdrPeerRetirementGuard false
            ndr.configureIfNeeded(identity) && ndr.retirePeer(oldPeer)
        }
        AppStateStore.initializeConversationPersistence(this)
    }

    companion object {
        private const val LOCAL_RELAY = "ws://127.0.0.1:18762"

        // This isolated debug branch wires existing singleton dependencies without adding
        // test switches or replacement protocol implementations to production sources.
        @Suppress("UNCHECKED_CAST")
        private fun <T> field(instance: Any, name: String): T =
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
                .get(instance) as T
    }
}

class NdrLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val nickname = intent.getStringExtra("nickname") ?: DataManager(this).loadNickname()
        require(nickname.startsWith(NdrLabDriver.PREFIX))
        DataManager(this).saveNickname(nickname)
        AppStateStore.setNickname(nickname)
        if (!NdrLabDriver.isStarted) {
            val bluetooth = MeshServiceHolder.getOrCreate(this)
            val mesh = MeshServiceHolder.getUnifiedOrCreate(this)
            val viewModel = ChatViewModel(application, bluetooth, mesh)
            mesh.delegate = viewModel
            NdrLabDriver.start(application, mesh, viewModel)
            NostrBackgroundRuntime.initialize(application)
            mesh.startServices()
        }
        setContentView(TextView(this).apply {
            text = "NDR physical lab\n$nickname\nBluetooth and loopback relay only"
            textSize = 22f
            setPadding(24, 48, 24, 24)
        })
    }
}
