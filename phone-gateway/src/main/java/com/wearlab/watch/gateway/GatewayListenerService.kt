package com.wearlab.watch.gateway

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearlab.common.TransferProtocol
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Phone side of the watch -> phone transfer. Everything it receives lands under
 * <external files>/received/<session>/ and is only moved into place after its SHA-256, recomputed
 * from the received bytes, matches the one the watch announced in the OFFER.
 */
class GatewayListenerService : WearableListenerService() {

    override fun onMessageReceived(ev: MessageEvent) {
        if (ev.path != TransferProtocol.OFFER) return
        val (session, files) = TransferProtocol.parseOffer(ev.data)
        val dir = Store.dir(this, session)
        File(dir, Store.OFFER_FILE).writeBytes(ev.data)
        dir.listFiles { f -> f.name.endsWith(".attempted") }?.forEach { it.delete() }
        // Only ask for what is missing or does not match: makes retries cheap and idempotent.
        val wanted = files.filter { f ->
            val have = File(dir, f.name)
            !(have.exists() && have.length() == f.size && TransferProtocol.sha256(have) == f.sha256)
        }.map { it.name }
        File(dir, Store.WANT_FILE).writeText(wanted.joinToString(","))
        Store.log(this, "OFFER $session: ${files.size} files, ${wanted.size} wanted")
        val msgs = Wearable.getMessageClient(this)
        Tasks.await(msgs.sendMessage(ev.sourceNodeId, TransferProtocol.WANT, TransferProtocol.namesJson(session, "files", wanted)))
        if (wanted.isEmpty()) {
            Tasks.await(msgs.sendMessage(ev.sourceNodeId, TransferProtocol.ACK, TransferProtocol.ackJson(session, files.map { it.name }, emptyList())))
        }
    }

    override fun onChannelOpened(ch: ChannelClient.Channel) {
        val (session, name) = TransferProtocol.parseFilePath(ch.path) ?: return
        val dir = Store.dir(this, session)
        val offer = runCatching { TransferProtocol.parseOffer(File(dir, Store.OFFER_FILE).readBytes()).second }.getOrNull()
        val expected = offer?.firstOrNull { it.name == name }
        val part = File(dir, "$name.part")
        val client = Wearable.getChannelClient(this)
        var sha: String? = null
        try {
            val md = MessageDigest.getInstance("SHA-256")
            Tasks.await(client.getInputStream(ch)).let { java.util.zip.GZIPInputStream(it, 64 * 1024) }.use { input ->
                part.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            sha = TransferProtocol.hex(md.digest())
        } catch (e: Exception) {
            Store.log(this, "RECV $session/$name failed: ${e.message}")
        }
        val ok = sha != null && expected != null && sha == expected.sha256 && part.length() == expected.size
        if (ok) { File(dir, name).delete(); part.renameTo(File(dir, name)) } else part.delete()
        Store.log(this, "RECV $session/$name ${if (ok) "VERIFIED" else "REJECTED (checksum/size mismatch)"}")
        File(dir, "$name.attempted").writeText("1")
        runCatching { client.close(ch) }
        finishIfDone(session, dir, offer, ch.nodeId)
    }

    /** Sends the ACK once every offered file is present and matches; otherwise reports what failed. */
    private fun finishIfDone(session: String, dir: File, offer: List<TransferProtocol.FileEntry>?, node: String) {
        if (offer == null) return
        val verified = offer.filter { f -> File(dir, f.name).let { it.exists() && it.length() == f.size && TransferProtocol.sha256(it) == f.sha256 } }.map { it.name }
        val pending = offer.map { it.name } - verified.toSet()
        // Wait until every file the phone asked for has been attempted; the watch sends them one by one.
        val wanted = File(dir, Store.WANT_FILE).takeIf { it.exists() }?.readText()?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if (wanted.any { !File(dir, "$it.attempted").exists() }) return
        File(dir, Store.STATE_FILE).writeText(
            JSONObject().put("session_id", session).put("verified", verified.size).put("failed", pending.size)
                .put("received_utc_ms", System.currentTimeMillis()).toString(),
        )
        Tasks.await(Wearable.getMessageClient(this).sendMessage(node, TransferProtocol.ACK, TransferProtocol.ackJson(session, verified, pending)))
        Store.log(this, "ACK $session verified=${verified.size} failed=${pending.size}")
    }
}

object Store {
    const val OFFER_FILE = ".offer.json"
    const val STATE_FILE = ".received.json"
    const val WANT_FILE = ".want.txt"

    fun root(c: android.content.Context) = File(c.getExternalFilesDir(null), "received").apply { mkdirs() }
    fun dir(c: android.content.Context, session: String) = File(root(c), session).apply { mkdirs() }

    @Synchronized
    fun log(c: android.content.Context, line: String) {
        File(root(c), "gateway.log").appendText("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line\n")
    }
}
