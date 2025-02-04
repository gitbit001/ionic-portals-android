package io.ionic.portals

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.NonNull
import androidx.fragment.app.Fragment
import com.getcapacitor.*
import io.ionic.liveupdates.LiveUpdateManager
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import kotlin.reflect.KVisibility

open class PortalFragment : Fragment {
    val PORTAL_NAME = "PORTALNAME"
    var portal: Portal? = null
    var liveUpdateFiles: File? = null

    private var bridge: Bridge? = null
    private var keepRunning = true
    private val initialPlugins: MutableList<Class<out Plugin?>> = ArrayList()
    private var config: CapConfig? = null
    private val webViewListeners: MutableList<WebViewListener> = ArrayList()
    private var subscriptions = mutableMapOf<String, Int>()
    private var initialContext: Any? = null

    constructor()

    constructor(portal: Portal?) {
        this.portal = portal
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layout = if(PortalManager.isRegistered()) R.layout.fragment_portal else R.layout.fragment_unregistered
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load(savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bridge != null) {
            bridge?.onDestroy()
            bridge?.onDetachedFromWindow()
        }
        for ((topic, ref) in subscriptions) {
            PortalsPlugin.unsubscribe(topic, ref)
        }
    }

    override fun onResume() {
        super.onResume()
        bridge?.app?.fireStatusChange(true)
        bridge?.onResume()
        Logger.debug("App resumed")
    }

    override fun onPause() {
        super.onPause()
        bridge?.onPause()
        Logger.debug("App paused")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PORTAL_NAME, portal?.name)
    }

    override fun onConfigurationChanged(@NonNull newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bridge?.onConfigurationChanged(newConfig)
    }

    fun addPlugin(plugin: Class<out Plugin?>?) {
        initialPlugins.add(plugin!!)
    }

    fun setConfig(config: CapConfig?) {
        this.config = config
    }

    fun getBridge(): Bridge? {
        return bridge
    }

    fun addWebViewListener(webViewListener: WebViewListener) {
        webViewListeners.add(webViewListener)
    }

    /**
     * Set an Initial Context that will be loaded in lieu of one set on the Portal object.
     */
    fun setInitialContext(initialContext: Any) {
        this.initialContext = initialContext
    }

    /**
     * Get the Initial Context that will be loaded in lieu of one set on the Portal object, if set.
     */
    fun getInitialContext(): Any? {
        return this.initialContext
    }

    fun reload() {
        if(portal?.liveUpdateConfig != null) {
            val latestLiveUpdateFiles = LiveUpdateManager.getLatestAppDirectory(requireContext(), portal?.liveUpdateConfig?.appId!!)
            if (latestLiveUpdateFiles != null) {
                if (liveUpdateFiles == null || liveUpdateFiles!!.path != latestLiveUpdateFiles.path) {
                    liveUpdateFiles = latestLiveUpdateFiles

                    // Reload the bridge to the new files path
                    bridge?.serverBasePath = liveUpdateFiles!!.path
                    return
                }
            } else {
                liveUpdateFiles = null
                bridge?.setServerAssetPath(portal?.startDir!!)
            }
        }

        // Reload the bridge to the existing start url
        bridge?.reload()
    }

    /**
     * Load the WebView and create the Bridge
     */
    private fun load(savedInstanceState: Bundle?) {
        if (PortalManager.isRegistered()) {
            if (bridge == null) {
                Logger.debug("Loading Bridge with Portal")

                val existingPortalName = savedInstanceState?.getString(PORTAL_NAME, null)
                if (existingPortalName != null && portal == null) {
                    portal = PortalManager.getPortal(existingPortalName)
                }

                if (portal != null) {
                    val startDir: String = portal?.startDir!!
                    initialPlugins.addAll(portal?.plugins!!)

                    if(config == null) {
                        config = CapConfig.Builder(requireContext()).setInitialFocus(false).create()
                    }

                    bridge = Bridge.Builder(this)
                        .setInstanceState(savedInstanceState)
                        .setPlugins(initialPlugins)
                        .setConfig(config)
                        .addWebViewListeners(webViewListeners)
                        .create()

                    setupInitialContextListener()

                    if (portal?.liveUpdateConfig != null) {
                        liveUpdateFiles = LiveUpdateManager.getLatestAppDirectory(requireContext(), portal?.liveUpdateConfig?.appId!!)
                        if (liveUpdateFiles != null) {
                            bridge?.serverBasePath = liveUpdateFiles!!.path
                        } else {
                            bridge?.setServerAssetPath(startDir)
                        }
                    } else {
                        bridge?.setServerAssetPath(startDir)
                    }

                    keepRunning = bridge?.shouldKeepRunning()!!
                }
            }
        } else if (PortalManager.isRegisteredError()) {
            if(activity != null) {
                val alert = AlertDialog.Builder(activity)
                alert.setMessage("Error validating your key for Ionic Portals. Check your key and try again.")
                alert.setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                alert.show()
            }
        }
    }

    private fun setupInitialContextListener() {
        val initialContext = this.initialContext ?: portal?.initialContext ?: return
        val jsonObject: JSONObject = when (initialContext) {
            is String -> {
                try {
                    JSONObject(initialContext)
                } catch (ex: JSONException) {
                    throw Error("initialContext must be a JSON string or a Map")
                }
            }
            is Map<*, *> -> {
                JSONObject(initialContext.toMap())
            }
            else -> {
                throw Error("initialContext must be a JSON string or a Map")
            }
        }
        val portalInitialContext = "{ \"name\": \"" + portal?.name + "\"," +
                " \"value\": " + jsonObject.toString() + " } "

        val newWebViewClient = object: BridgeWebViewClient(bridge) {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                view?.post {
                    run {
                        view.evaluateJavascript(
                            "window.portalInitialContext = $portalInitialContext", null
                        )
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        bridge?.webView?.webViewClient = newWebViewClient
    }

    /**
     * Link a class with methods decorated with the [PortalMethod] annotation to use as Portals
     * message receivers.
     *
     * The name of the method should match the message name used to send messages via the Portal.
     * Alternatively the [PortalMethod] annotation topic property can be used to designate a
     * different name. The registered methods should accept a single String representing the payload
     * of a message sent through the Portal.
     */
    fun linkMessageReceivers(messageReceiverParent: Any) {
        val members = messageReceiverParent.javaClass.kotlin.members.filter { it.annotations.any { annotation -> annotation is PortalMethod } }

        for (member in members) {
            var methodName = member.name
            for (annotation in member.annotations) {
                if (annotation is PortalMethod && annotation.topic.isNotEmpty()) {
                    methodName = annotation.topic
                }
            }

            if(member.visibility != KVisibility.PUBLIC) {
                throw IllegalAccessException("Portal Method '${member.name}' must be public!")
            }

            when (member.parameters.size) {
                1 -> {
                    val ref = PortalsPlugin.subscribe(methodName) { result ->
                        member.call(messageReceiverParent)
                    }
                    subscriptions[methodName] = ref
                }
                2 -> {
                    val ref = PortalsPlugin.subscribe(methodName) { result ->
                        member.call(messageReceiverParent, result.data)
                    }
                    subscriptions[methodName] = ref
                }

                else -> {
                    throw IllegalArgumentException("Portal Method '${member.name}' must" +
                            " contain zero parameters or a single String parameter!")
                }
            }
        }
    }
}
