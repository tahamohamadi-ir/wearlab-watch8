package com.wearlab.watch.storage

import android.content.Context
import com.wearlab.watch.session.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SessionSummary(val id: String, val status: String, val samples: Long, val bytes: Long, val startUtc: String?)

/** Layout: files/sessions/<session_id>/{manifest.json, metadata.json, <sensor>.csv, events.jsonl}. */
class SessionStore(context: Context) {
    val root = File(context.filesDir, "sessions").apply { mkdirs() }
    private val appContext = context.applicationContext

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun nextIndex(): Int = (root.listFiles()?.count { it.isDirectory } ?: 0) + 1

    fun loadProtocol(name: String): Protocol {
        val j = JSONObject(appContext.assets.open("protocols/$name.json").bufferedReader().use { it.readText() })
        val meta = j.optJSONObject("metadata")
        return Protocol(
            id = j.getString("protocol_id"), label = j.getString("label"),
            participantId = j.getString("participant_id"), durationSec = j.getInt("duration_sec"),
            sensors = j.getJSONArray("sensors").let { a -> List(a.length()) { a.getString(it) } },
            rateHz = j.getDouble("rate_hz").toFloat(),
            metadata = meta?.keys()?.asSequence()?.associateWith { meta.getString(it) } ?: emptyMap(),
            intervalMin = j.optInt("interval_min", 0), windowSec = j.optInt("window_sec", 0),
        )
    }

    fun protocolNames(): List<String> =
        appContext.assets.list("protocols")?.map { it.removeSuffix(".json") }?.sorted() ?: emptyList()

    /** Atomic write (tmp + rename) so a crash never leaves a half-written manifest. */
    fun writeJson(file: File, json: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun appendEvent(id: String, line: JSONObject) {
        File(dir(id), "events.jsonl").appendText(line.toString() + "\n")
    }

    fun list(): List<SessionSummary> =
        (root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()).map { d ->
            val m = File(d, "manifest.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val streams = m?.optJSONObject("streams")
            var n = 0L; var b = 0L
            streams?.keys()?.forEach { k -> streams.getJSONObject(k).let { n += it.optLong("samples"); b += it.optLong("bytes") } }
            SessionSummary(d.name, m?.optString("status") ?: "NO_MANIFEST", n, b, m?.optString("start_utc"))
        }

    /**
     * Crash recovery: any session whose manifest still says RUNNING/STARTING was cut off (app kill,
     * reboot, battery). Raw files are never modified; counts and checksums are recomputed as they are.
     */
    fun recoverInterrupted(): List<String> {
        val recovered = mutableListOf<String>()
        for (d in root.listFiles { f -> f.isDirectory } ?: emptyArray()) {
            val mf = File(d, "manifest.json")
            if (!mf.exists()) continue
            val m = runCatching { JSONObject(mf.readText()) }.getOrNull() ?: continue
            if (m.optString("status") !in setOf("RUNNING", "STARTING", "STOPPING", "FINALIZING")) continue
            val streams = m.optJSONObject("streams") ?: JSONObject().also { m.put("streams", it) }
            for (k in streams.keys()) {
                val s = streams.getJSONObject(k)
                val f = File(d, s.optString("file"))
                if (f.exists()) {
                    val (rows, sha) = CsvStreamWriter.scan(f)
                    s.put("samples", rows); s.put("bytes", f.length()); s.put("sha256", sha)
                    s.put("last_row_may_be_truncated", true)
                }
            }
            m.put("status", "INTERRUPTED")
            (m.optJSONArray("errors") ?: JSONArray().also { m.put("errors", it) })
                .put("session was not finalized; recovered at next app start")
            writeJson(mf, m)
            recovered += d.name
        }
        return recovered
    }
}
