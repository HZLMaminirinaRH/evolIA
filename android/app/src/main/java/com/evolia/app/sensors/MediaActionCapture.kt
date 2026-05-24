package com.evolia.app.sensors

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.EvoliaPaths

/**
 * Captures new photos/videos by observing MediaStore and enqueuing
 * photo_taken / video_taken into the ActionQueue — the Android analog of the
 * MediaWatcher in evolia_actions.py.
 *
 * Each collection is watched separately so the kind is known without inspecting
 * the row; we track the highest _ID seen and enqueue the count of newer rows.
 * Needs READ_MEDIA_IMAGES/VIDEO (API 33+) or READ_EXTERNAL_STORAGE; without
 * them the queries return nothing and capture silently no-ops (graceful degrade).
 */
class MediaActionCapture(context: Context, private val paths: EvoliaPaths) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val imageUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    @Volatile private var lastImageId = 0L
    @Volatile private var lastVideoId = 0L

    private val imageObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = drainNew(imageUri, "photo_taken")
    }
    private val videoObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = drainNew(videoUri, "video_taken")
    }

    fun start() {
        lastImageId = maxId(imageUri)
        lastVideoId = maxId(videoUri)
        app.contentResolver.registerContentObserver(imageUri, true, imageObserver)
        app.contentResolver.registerContentObserver(videoUri, true, videoObserver)
    }

    fun stop() {
        app.contentResolver.unregisterContentObserver(imageObserver)
        app.contentResolver.unregisterContentObserver(videoObserver)
    }

    private fun drainNew(uri: Uri, kind: String) {
        val since = if (uri == imageUri) lastImageId else lastVideoId
        val (count, newMax) = countNewerThan(uri, since)
        if (uri == imageUri) lastImageId = maxOf(lastImageId, newMax) else lastVideoId = maxOf(lastVideoId, newMax)
        if (count > 0) ActionQueue.enqueue(paths, kind, count)
    }

    private fun maxId(uri: Uri): Long = try {
        app.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            "${MediaStore.MediaColumns._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun countNewerThan(uri: Uri, sinceId: Long): Pair<Int, Long> = try {
        app.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns._ID} > ?",
            arrayOf(sinceId.toString()),
            "${MediaStore.MediaColumns._ID} DESC",
        )?.use { c ->
            val count = c.count
            val max = if (c.moveToFirst()) c.getLong(0) else sinceId
            count to max
        } ?: (0 to sinceId)
    } catch (_: Exception) {
        0 to sinceId
    }
}
