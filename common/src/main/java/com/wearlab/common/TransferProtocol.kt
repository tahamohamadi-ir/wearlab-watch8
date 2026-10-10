package com.wearlab.common

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Watch -> phone transfer protocol (Phase 4, modules M12/M13), shared by both apps.
 *
 *   watch  --OFFER {session_id, files:[{name,size,sha256}]}-->  phone
 *   watch  <--WANT  {session_id, files:[name]}----------------  phone   (only what is missing/corrupt)
 *   watch  --channel /wearlab/file/<session>/<name> (bytes)-->  phone   (phone hashes while receiving)
 *   watch  <--ACK   {session_id, verified:[..], failed:[..]}--  phone
 *
 * The watch never deletes anything: a session is only marked ACKNOWLEDGED, and only after the phone
 * has recomputed every checksum from the received bytes ("source checksum == destination checksum").
 */
object TransferProtocol {
    const val OFFER = "/wearlab/offer"
    const val WANT = "/wearlab/want"
    const val ACK = "/wearlab/ack"
    const val FILE_PREFIX = "/wearlab/file/"

    fun filePath(session: String, name: String) = "$FILE_PREFIX$session/$name"

    /** "/wearlab/file/<session>/<name>" -> (session, name), or null for foreign paths. */
    fun parseFilePath(path: String): Pair<String, String>? {
        if (!path.startsWith(FILE_PREFIX)) return null
        val rest = path.removePrefix(FILE_PREFIX).split('/')
        return if (rest.size == 2 && rest.all { it.isNotEmpty() && it != ".." }) rest[0] to rest[1] else null
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    class FileEntry(val name: String, val size: Long, val sha256: String)

    fun offerJson(session: String, files: List<FileEntry>): ByteArray = JSONObject().apply {
        put("session_id", session)
        put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("size", it.size).put("sha256", it.sha256) }))
    }.toString().toByteArray()

    fun parseOffer(data: ByteArray): Pair<String, List<FileEntry>> {
        val j = JSONObject(String(data))
        val a = j.getJSONArray("files")
        return j.getString("session_id") to List(a.length()) {
            a.getJSONObject(it).let { f -> FileEntry(f.getString("name"), f.getLong("size"), f.getString("sha256")) }
        }
    }

    fun namesJson(session: String, key: String, names: List<String>): ByteArray =
        JSONObject().put("session_id", session).put(key, JSONArray(names)).toString().toByteArray()

    fun ackJson(session: String, verified: List<String>, failed: List<String>): ByteArray =
        JSONObject().put("session_id", session).put("verified", JSONArray(verified)).put("failed", JSONArray(failed))
            .toString().toByteArray()

    fun names(j: JSONObject, key: String): List<String> = j.optJSONArray(key).let { a -> List(a?.length() ?: 0) { a!!.getString(it) } }
}
