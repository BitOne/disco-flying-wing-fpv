/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.peripheral.MediaStore
import com.parrot.drone.groundsdk.device.peripheral.RemovableUserStorage
import com.parrot.drone.groundsdk.device.peripheral.media.MediaDestination
import com.parrot.drone.groundsdk.device.peripheral.media.MediaDownloader
import com.parrot.drone.groundsdk.device.peripheral.media.MediaItem
import com.parrot.drone.groundsdk.device.peripheral.media.MediaTaskStatus
import java.io.File
import java.text.DateFormat
import java.util.Locale

/**
 * The photos and videos on the Disco (T9.6): its storage space, each
 * media with its thumbnail, date and size; downloaded to the phone's
 * gallery (DCIM/Disco FPV, through MediaStore: GroundSdk's own "platform
 * media store" writes to DCIM directly, which scoped storage refuses) with
 * a progress line and a stop button, the phone's free space checked
 * first; deleted from the Disco. GroundSdk's
 * MediaStore over FTP through the controller (SDK patch 0007 refreshes the
 * list when the Disco's media change).
 */
class MediaActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private val refs = mutableListOf<Ref<*>>()
    private val thumbRefs = mutableListOf<Ref<*>>()
    private var store: MediaStore? = null
    private var listRef: Ref<List<MediaItem>>? = null
    private var download: Ref<MediaDownloader>? = null

    private lateinit var storage: TextView
    private lateinit var status: TextView
    private lateinit var downloadAll: Button
    private lateinit var stop: Button
    private lateinit var list: LinearLayout
    private var items: List<MediaItem> = emptyList()

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
        place(TextView(this, null, 0, R.style.Title).apply { text = getString(R.string.media_title) }, 0)
        storage = TextView(this, null, 0, R.style.SettingNote).also { place(it, 12) }
        status = TextView(this, null, 0, R.style.Card).apply { visibility = View.GONE }.also { place(it, 12) }
        downloadAll = Button(this, null, 0, R.style.Button_Primary).apply {
            text = getString(R.string.media_download_all)
            setOnClickListener { startDownload(items) }
        }.also { place(it, 12, px(52)) }
        stop = Button(this, null, 0, R.style.Button_Secondary).apply {
            text = getString(R.string.media_stop)
            visibility = View.GONE
            setOnClickListener { stopDownload(getString(R.string.media_stopped)) }
        }.also { place(it, 8, px(48)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { place(it, 8) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        groundSdk = ManagedGroundSdk.obtainSession(this)
        val drone = intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID)?.let { groundSdk.getDrone(it) { finish() } }
        if (drone == null) {
            finish()
            return
        }
        watch(drone)
    }

    private fun watch(drone: Drone) {
        refs += drone.getPeripheral(RemovableUserStorage::class.java) { s ->
            storage.text = if (s == null || s.mediaInfo == null) getString(R.string.media_storage_unknown)
            else getString(R.string.media_storage, gb(s.availableSpace), gb(s.mediaInfo!!.capacity))
        }
        refs += drone.getPeripheral(MediaStore::class.java) { s ->
            val first = store == null && s != null
            store = s
            if (s == null) {
                listRef?.close()
                listRef = null
                show(emptyList())
            } else if (first) {
                listRef = s.browse { show(it.orEmpty()) }
            }
        }
    }

    override fun onDestroy() {
        // a download goes on only while this screen is open
        download?.close()
        listRef?.close()
        thumbRefs.forEach { it.close() }
        refs.forEach { it.close() }
        super.onDestroy()
    }

    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1e9)

    private fun size(bytes: Long): String =
        if (bytes >= 1_000_000) String.format(Locale.US, "%.1f MB", bytes / 1e6) else "${bytes / 1000} kB"

    private fun show(media: List<MediaItem>) {
        // newest first; FTP listings date to the minute, the names
        // (Disco_<date and time>_<run>) to the second
        items = media.sortedWith(compareByDescending<MediaItem> { it.creationDate }.thenByDescending { it.name })
        thumbRefs.forEach { it.close() }
        thumbRefs.clear()
        list.removeAllViews()
        downloadAll.isEnabled = items.isNotEmpty() && download == null
        if (items.isEmpty()) {
            list.addView(TextView(this, null, 0, R.style.SettingNote).apply { text = getString(R.string.media_none) })
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = item.name
            }
            row.addView(thumb, LinearLayout.LayoutParams(px(96), px(54)))
            store?.fetchThumbnailOf(item) { b -> if (b != null) thumb.setImageBitmap(b) }?.let { thumbRefs += it }
            val bytes = item.resources.sumOf { it.size }
            row.addView(TextView(this, null, 0, R.style.SettingNote).apply {
                text = getString(if (item.type == MediaItem.Type.VIDEO) R.string.media_video else R.string.media_photo,
                    format.format(item.creationDate), size(bytes))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) })
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this, null, 0, R.style.Button_Secondary).apply {
                text = getString(R.string.media_download)
                contentDescription = "${item.name} download"
                setOnClickListener { startDownload(listOf(item)) }
            }, LinearLayout.LayoutParams(0, px(44), 1f).apply { marginEnd = px(6) })
            buttons.addView(Button(this, null, 0, R.style.Button_Secondary).apply {
                text = getString(R.string.media_delete)
                contentDescription = "${item.name} delete"
                setOnClickListener { confirmDelete(item) }
            }, LinearLayout.LayoutParams(0, px(44), 1f))
            list.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        }
    }

    private fun say(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    /** Free space where they land (the app's folder on the shared storage, then the gallery). */
    private fun phoneFreeBytes(): Long = runCatching {
        StatFs((getExternalFilesDir(null) ?: filesDir).path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /** Into the gallery (Android 10+); before, it stays in the app's folder. */
    private fun publish(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val video = file.name.endsWith(".mp4", ignoreCase = true)
        val collection = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$ALBUM")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: return false
        val ok = runCatching {
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        }.isSuccess
        if (!ok) {
            contentResolver.delete(uri, null, null)
            return false
        }
        contentResolver.update(uri, ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        file.delete()
        return true
    }

    private fun startDownload(media: List<MediaItem>) {
        val s = store ?: return
        if (media.isEmpty() || download != null) return
        val need = media.sumOf { m -> m.resources.sumOf { it.size } }
        val free = phoneFreeBytes()
        // some margin for the gallery's own files
        if (need + MARGIN_BYTES > free) {
            say(getString(R.string.media_no_space, size(need), size(free)))
            return
        }
        val resources = media.flatMap { it.resources }
        downloadAll.isEnabled = false
        stop.visibility = View.VISIBLE
        say(getString(R.string.media_downloading, 0, media.size, 0))
        var inGallery = true
        download = s.download(resources, MediaDestination.appPrivateFiles(ALBUM)) { d ->
            if (d == null) return@download
            if (d.status == MediaTaskStatus.FILE_PROCESSED || d.status == MediaTaskStatus.COMPLETE) {
                d.downloadedFile?.let { if (!publish(it)) inGallery = false }
            }
            when (d.status) {
                MediaTaskStatus.RUNNING, MediaTaskStatus.FILE_PROCESSED ->
                    say(getString(R.string.media_downloading, d.currentMediaIndex, d.totalMediaCount, d.totalProgress))
                MediaTaskStatus.COMPLETE -> stopDownload(
                    if (inGallery) getString(R.string.media_downloaded, d.totalMediaCount, ALBUM)
                    else getString(R.string.media_downloaded_app, d.totalMediaCount,
                        File(getExternalFilesDir(Environment.DIRECTORY_DCIM), ALBUM).path))
                MediaTaskStatus.ERROR -> stopDownload(getString(R.string.media_download_failed))
                else -> {}
            }
        }
    }

    private fun stopDownload(message: String) {
        download?.close()
        download = null
        stop.visibility = View.GONE
        downloadAll.isEnabled = items.isNotEmpty()
        say(message)
    }

    private fun confirmDelete(item: MediaItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.media_delete_confirm, item.name))
            .setPositiveButton(R.string.media_delete_yes) { _, _ ->
                store?.delete(item.resources) { d ->
                    if (d?.status == MediaTaskStatus.COMPLETE) say(getString(R.string.media_deleted))
                    if (d?.status == MediaTaskStatus.ERROR) say(getString(R.string.media_delete_failed))
                }?.let { refs += it }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val ALBUM = "Disco FPV"
        const val MARGIN_BYTES = 50_000_000L
    }
}
