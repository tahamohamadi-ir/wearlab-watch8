package com.wearlab.watch.gateway

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wearlab.common.TransferProtocol
import org.json.JSONObject
import java.io.File

/** Minimal status screen: which sessions arrived, whether every checksum verified, and where they are. */
class GatewayActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        col.addView(TextView(this).apply { this.text = "WearLab Gateway"; textSize = 22f; setTypeface(null, Typeface.BOLD) })
        col.addView(Button(this).apply { this.text = "Refresh"; setOnClickListener { render() } })
        text = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE }
        col.addView(ScrollView(this).apply { addView(text) }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(col)
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val root = Store.root(this)
        val sb = StringBuilder("Folder:\n${root.absolutePath}\n\n")
        val sessions = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        if (sessions.isEmpty()) sb.append("No sessions received yet.\nOn the watch: Sessions > Send to phone.\n")
        for (d in sessions) {
            val offer = runCatching { TransferProtocol.parseOffer(File(d, Store.OFFER_FILE).readBytes()).second }.getOrNull()
            val ok = offer?.count { f -> File(d, f.name).let { it.exists() && it.length() == f.size && TransferProtocol.sha256(it) == f.sha256 } } ?: 0
            val state = when {
                offer == null -> "NO OFFER"
                ok == offer.size -> "VERIFIED $ok/${offer.size}"
                else -> "INCOMPLETE $ok/${offer.size}"
            }
            val kb = d.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024
            sb.append("${d.name}\n   $state   $kb KB\n")
        }
        File(root, "gateway.log").takeIf { it.exists() }?.readLines()?.takeLast(8)?.let { sb.append("\nlog:\n").append(it.joinToString("\n")) }
        text.text = sb
    }
}
