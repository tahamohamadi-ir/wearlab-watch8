package com.wearlab.watch.transfer

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearlab.common.TransferProtocol
import com.wearlab.watch.storage.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One state per session, stored beside (never inside) the immutable raw files. */
enum class TransferStatus { PENDING, ACKNOWLEDGED, FAILED }

data class SyncResult(val sent: Int, val acknowledged: Int, val failed: Int, val message: String)

/**
 * Watch side of the transfer (M12/M13). Sessions still RUNNING are skipped; nothing is ever deleted
 * from the watch - a session becomes ACKNOWLEDGED only when the phone reports that every file's
 * SHA-256, recomputed from the bytes it received, matches the one announced here.
 */
object PhoneSync {
    private val wants = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val acks = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    internal fun dispatch(ev: MessageEvent) {
        val j = runCatching { JSONObject(String(ev.data)) }.getOrNull() ?: return
        val id = j.optString("session_id")
        when (ev.path) {
            TransferProtocol.WANT -> wants[id]?.complete(j)
            TransferProtocol.ACK -> acks[id]?.complete(j)
        }
    }

    fun statusOf(dir: File): TransferStatus = runCatching {
        TransferStatus.valueOf(JSONObject(File(dir, "transfer_state.json").readText()).getString("status"))
    }.getOrDefault(TransferStatus.PENDING)

    suspend fun syncAll(context: Context, store: SessionStore, onProgress: (String) -> Unit): SyncResult = withContext(Dispatchers.IO) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: return@withContext SyncResult(0, 0, 0, "phone not connected")
        val pending = store.root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.filter { d ->
            val status = runCatching { JSONObject(File(d, "manifest.json").readText()).getString("status") }.getOrDefault("RUNNING")
            status != "RUNNING" && statusOf(d) != TransferStatus.ACKNOWLEDGED
        }
        var ok = 0
        var bad = 0
        for ((i, d) in pending.withIndex()) {
            onProgress("${i + 1}/${pending.size} ${d.name.takeLast(14)}")
            val st = runCatching { syncOne(context, d, node.id) }.getOrElse { e ->
                writeState(d, TransferStatus.FAILED, node.displayName, emptyList(), listOf(e.message ?: e.toString())); false
            }
            if (st) ok++ else bad++
        }
        SyncResult(pending.size, ok, bad, if (pending.isEmpty()) "nothing to send" else "$ok verified, $bad failed (${node.displayName})")
    }

    private suspend fun syncOne(context: Context, dir: File, nodeId: String): Boolean {
        val session = dir.name
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") && f.name != "transfer_state.json" }.orEmpty().sortedBy { it.name }
        val entries = files.map { TransferProtocol.FileEntry(it.name, it.length(), TransferProtocol.sha256(it)) }
        val wantFut = CompletableDeferred<JSONObject>().also { wants[session] = it }
        val ackFut = CompletableDeferred<JSONObject>().also { acks[session] = it }
        try {
            val msgs = Wearable.getMessageClient(context)
            msgs.sendMessage(nodeId, TransferProtocol.OFFER, TransferProtocol.offerJson(session, entries)).await()
            val want = TransferProtocol.names(withTimeout(20_000) { wantFut.await() }, "files")
            val channels = Wearable.getChannelClient(context)
            for (name in want) {
                val f = File(dir, name)
                val ch = channels.openChannel(nodeId, TransferProtocol.filePath(session, name)).await()
                // gzip on the wire (CSV shrinks ~5x over the slow Bluetooth link); checksums stay on the original bytes
                channels.getOutputStream(ch).await().let { java.util.zip.GZIPOutputStream(it, 64 * 1024) }.use { out ->
                    f.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
                channels.close(ch).await()
            }
            val ack = withTimeout(60_000) { ackFut.await() }
            val failed = TransferProtocol.names(ack, "failed")
            val verified = TransferProtocol.names(ack, "verified")
            val all = failed.isEmpty() && verified.toSet() == entries.map { it.name }.toSet()
            writeState(dir, if (all) TransferStatus.ACKNOWLEDGED else TransferStatus.FAILED, nodeId, verified, failed)
            return all
        } finally {
            wants.remove(session); acks.remove(session)
        }
    }

    private fun writeState(dir: File, s: TransferStatus, node: String, verified: List<String>, failed: List<String>) {
        File(dir, "transfer_state.json").writeText(
            JSONObject().put("status", s.name).put("node", node).put("utc_ms", System.currentTimeMillis())
                .put("verified_files", verified.size).put("failed", failed.toString()).toString(2),
        )
    }
}

/** Receives the phone's WANT / ACK replies (declared in the manifest). */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(ev: MessageEvent) = PhoneSync.dispatch(ev)
}
