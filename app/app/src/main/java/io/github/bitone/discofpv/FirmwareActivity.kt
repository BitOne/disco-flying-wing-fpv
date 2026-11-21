/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.peripheral.SystemInfo
import com.parrot.drone.groundsdk.device.peripheral.Updater
import com.parrot.drone.groundsdk.facility.FirmwareManager
import com.parrot.drone.groundsdk.facility.firmware.FirmwareIdentifier
import com.parrot.drone.groundsdk.facility.firmware.FirmwareVersion
import java.io.File
import java.util.Locale

/**
 * The Disco's firmware (T9.9): the installed version, and an update from
 * a file on the phone (only Parrot's own files, recognised by their
 * SHA-256: FirmwareFiles) or from Parrot's server while it still answers
 * (asks it which versions exist: no personal data). GroundSdk's Updater
 * uploads it (FTP, port 51) and the Disco installs it and restarts; it
 * must be landed, with enough battery (the updater says why not).
 * SDK patch 0008 lets the app register the file.
 */
class FirmwareActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private val refs = mutableListOf<Ref<*>>()
    private var updater: Updater? = null
    private var manager: FirmwareManager? = null
    private var installed: String? = null

    private lateinit var current: TextView
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var fileNote: TextView
    private lateinit var download: Button

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }
        fun place(v: View, top: Int, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            column.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
                .apply { topMargin = px(top) })
        place(TextView(this, null, 0, R.style.Title).apply { text = getString(R.string.fw_title) }, 0)
        current = TextView(this, null, 0, R.style.Card).also { place(it, 16) }
        status = TextView(this, null, 0, R.style.Card).also { place(it, 8) }
        install = Button(this, null, 0, R.style.Button_Primary).apply {
            setOnClickListener { confirmInstall() }
        }.also { place(it, 10, px(52)) }
        place(TextView(this, null, 0, R.style.SettingNote).apply { text = getString(R.string.fw_install_note) }, 6)

        place(TextView(this, null, 0, R.style.Section).apply { text = getString(R.string.fw_section_file) }, 24)
        place(Button(this, null, 0, R.style.Button_Secondary).apply {
            text = getString(R.string.fw_choose_file)
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQUEST_FILE)
            }
        }, 4, px(48))
        fileNote = TextView(this, null, 0, R.style.SettingNote).apply { text = getString(R.string.fw_file_note) }
            .also { place(it, 6) }

        place(TextView(this, null, 0, R.style.Section).apply { text = getString(R.string.fw_section_server) }, 24)
        download = Button(this, null, 0, R.style.Button_Secondary).apply {
            setOnClickListener {
                val u = updater ?: return@setOnClickListener
                if (u.downloadableFirmwares().isEmpty()) manager?.queryRemoteFirmwares() else u.downloadAllFirmwares()
            }
        }.also { place(it, 4, px(48)) }
        place(TextView(this, null, 0, R.style.SettingNote).apply { text = getString(R.string.fw_server_note) }, 6)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        groundSdk = ManagedGroundSdk.obtainSession(this)
        refs += groundSdk.getFacility(FirmwareManager::class.java) { manager = it; render() }
        val drone = intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID)?.let { groundSdk.getDrone(it) { finish() } }
        if (drone == null) {
            finish()
            return
        }
        watch(drone)
        render()
    }

    private fun watch(drone: Drone) {
        refs += drone.getPeripheral(SystemInfo::class.java) { installed = it?.firmwareVersion; render() }
        refs += drone.getPeripheral(Updater::class.java) { updater = it; render() }
    }

    override fun onDestroy() {
        refs.forEach { it.close() }
        super.onDestroy()
    }

    private fun render() {
        current.text = getString(R.string.fw_installed, installed ?: "?")
        val u = updater
        val update = u?.currentUpdate()
        val reasons = u?.updateUnavailabilityReasons().orEmpty()
        val latest = u?.takeIf { it.applicableFirmwares().isNotEmpty() }?.idealVersion()?.toString()
        status.text = when {
            u == null -> getString(R.string.fw_waiting)
            update != null -> when (update.state()) {
                Updater.Update.State.UPLOADING -> getString(R.string.fw_uploading, update.currentFirmwareProgress())
                Updater.Update.State.PROCESSING -> getString(R.string.fw_processing)
                Updater.Update.State.WAITING_FOR_REBOOT -> getString(R.string.fw_rebooting)
                Updater.Update.State.SUCCESS -> getString(R.string.fw_success)
                Updater.Update.State.FAILED -> getString(R.string.fw_failed)
                Updater.Update.State.CANCELED -> getString(R.string.fw_canceled)
            }
            latest == null -> getString(R.string.fw_up_to_date)
            reasons.isNotEmpty() -> getString(R.string.fw_unavailable, latest, reasons.joinToString(", ") {
                when (it) {
                    Updater.Update.UnavailabilityReason.NOT_LANDED -> getString(R.string.fw_not_landed)
                    Updater.Update.UnavailabilityReason.NOT_ENOUGH_BATTERY -> getString(R.string.fw_battery)
                    else -> it.name.replace('_', ' ').lowercase(Locale.US)
                }
            })
            else -> getString(R.string.fw_available, latest)
        }
        install.text = getString(R.string.fw_install, latest ?: "")
        install.isEnabled = u != null && latest != null && reasons.isEmpty() && update == null
        install.visibility = if (latest == null && update == null) View.GONE else View.VISIBLE
        val downloading = u?.currentDownload()
        download.text = when {
            downloading != null -> getString(R.string.fw_downloading, downloading.totalProgress())
            u != null && u.downloadableFirmwares().isNotEmpty() ->
                getString(R.string.fw_download, u.downloadableFirmwares().joinToString { it.firmware.version.toString() })
            manager?.isQueryingRemoteFirmwares == true -> getString(R.string.fw_asking)
            else -> getString(R.string.fw_ask_server)
        }
        download.isEnabled = u != null && downloading == null && manager?.isQueryingRemoteFirmwares != true
    }

    private fun confirmInstall() {
        val u = updater ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.fw_confirm_title)
            .setMessage(R.string.fw_confirm)
            .setPositiveButton(R.string.fw_confirm_yes) { _, _ -> u.updateToLatestFirmware() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK || uri == null) return
        fileNote.text = getString(R.string.fw_checking)
        Thread {
            val dir = File(filesDir, "firmware").apply { mkdirs() }
            val tmp = File(dir, "import.tmp")
            val result = runCatching {
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                FirmwareFiles.check(FirmwareFiles.header(tmp), tmp.inputStream().use { FirmwareFiles.sha256(it) })
            }.getOrNull()
            val message = when (result) {
                is FirmwareFiles.Check.Official -> {
                    val file = File(dir, "disco_update-${result.version}.plf")
                    tmp.renameTo(file)
                    val id = FirmwareIdentifier(Drone.Model.DISCO, FirmwareVersion.parse(result.version)!!)
                    val ok = runOnMain { manager?.importLocalFirmware(id, file) == true }
                    if (ok) getString(R.string.fw_file_ok, result.version) else getString(R.string.fw_file_failed)
                }
                is FirmwareFiles.Check.Mismatch -> getString(R.string.fw_file_mismatch)
                FirmwareFiles.Check.NotPlf -> getString(R.string.fw_file_not_plf)
                FirmwareFiles.Check.Unknown -> getString(R.string.fw_file_unknown)
                null -> getString(R.string.fw_file_unreadable)
            }
            if (result !is FirmwareFiles.Check.Official) tmp.delete()
            runOnUiThread {
                fileNote.text = message
                render()
            }
        }.start()
    }

    /** Runs on the main thread (GroundSdk's), waiting for the answer. */
    private fun <T> runOnMain(block: () -> T): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: T? = null
        runOnUiThread { value = block(); latch.countDown() }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object { const val REQUEST_FILE = 2 }
}
