/*
 * Disco FPV test helper. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv.fakedisco

import android.app.Activity
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log

/**
 * Registers the ARSDK mDNS service of a drone (default: Disco, product
 * 0x090e, "_arsdk-090e._udp", port 44444, TXT {"device_id":...}) and
 * finishes. Extras: name, product (hex), port, device_id.
 *   adb shell am start -n io.github.bitone.discofpv.fakedisco/.PublishActivity
 */
class PublishActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: "DISCO-FAKE01"
        val product = intent.getStringExtra("product") ?: "090e"
        val port = intent.getIntExtra("port", 44444)
        val deviceId = intent.getStringExtra("device_id") ?: "PI040409FAKEDISCO"
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_arsdk-$product._udp"
            this.port = port
            // Parrot devices publish a raw TXT string {"device_id":"<serial>"}
            // (arsdk-ng AVAHI_SERVICE_TXTDATA_FMT), not key=value; FreeFlight
            // parses it as JSON. An attribute without value is encoded as the
            // bare key, which gives exactly that string.
            setAttribute("{\"device_id\":\"$deviceId\"}", null)
        }
        val nsd = getSystemService(NsdManager::class.java)
        if (registration != null) nsd.unregisterService(registration)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                Log.i(TAG, "registered ${info.serviceName} ${info.serviceType} port $port").let { }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) =
                Log.e(TAG, "registration failed: $error").let { }
            override fun onServiceUnregistered(info: NsdServiceInfo) =
                Log.i(TAG, "unregistered ${info.serviceName}").let { }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) =
                Log.e(TAG, "unregistration failed: $error").let { }
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        finish()
    }

    companion object {
        private const val TAG = "FakeDiscoMdns"

        /** Kept for the process lifetime so the registration stays alive. */
        private var registration: NsdManager.RegistrationListener? = null
    }
}
