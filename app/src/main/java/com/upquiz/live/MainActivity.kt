package com.upquiz.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_OVERLAY = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nameInput = findViewById<EditText>(R.id.etName)
        val btnJoin   = findViewById<Button>(R.id.btnJoin)
        val btnStop   = findViewById<Button>(R.id.btnStop)
        val tvStatus  = findViewById<TextView>(R.id.tvStatus)

        updateUI(FloatingService.isRunning)

        btnJoin.setOnClickListener {
            val n = nameInput.text.toString().trim()
            if (n.isEmpty()) {
                Toast.makeText(this, "नाम डालो पहले!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("upquiz", MODE_PRIVATE).edit().putString("name", n).apply()
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.text = "⚙️ Permission दो — 'Display over other apps' ON करो"
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")), REQ_OVERLAY)
            } else {
                startService(n)
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            updateUI(false)
            findViewById<TextView>(R.id.tvStatus).text = "👆 नाम डालो और Join करो"
        }
    }

    private fun startService(name: String) {
        val intent = Intent(this, FloatingService::class.java)
        intent.putExtra("player_name", name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
        updateUI(true)
        findViewById<TextView>(R.id.tvStatus).text = "✅ Connected!\nYouTube खोलो — Question आने पर Popup दिखेगा!"
        moveTaskToBack(true)
    }

    private fun updateUI(running: Boolean) {
        val btnJoin = findViewById<Button>(R.id.btnJoin)
        val btnStop = findViewById<Button>(R.id.btnStop)
        btnJoin.isEnabled = !running
        btnStop.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                val name = getSharedPreferences("upquiz", MODE_PRIVATE)
                    .getString("name", "Student") ?: "Student"
                startService(name)
            } else {
                findViewById<TextView>(R.id.tvStatus).text =
                    "❌ Permission नहीं मिली — Settings में जाकर ON करो"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI(FloatingService.isRunning)
    }
}
