package com.faroquetech.rootrealm

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import rikka.shizuku.Shizuku

class ShizukuManagerActivity : BaseActivity() {

    private val bg = Color.rgb(5, 5, 5)
    private val cardColor = Color.rgb(18, 18, 18)
    private val white = Color.WHITE
    private val gray = Color.rgb(170, 170, 170)
    private val green = Color.rgb(110, 210, 130)
    private val red = Color.rgb(235, 100, 100)
    private val requestCode = 2401

    private lateinit var status: TextView
    private lateinit var permission: TextView

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(15), dp(18), dp(25))
        }

        root.addView(TextView(this).apply {
            text = "Shizuku Manager"
            textSize = 26f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(20))
        })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val statusCard = card()
        label(statusCard, "Shizuku Status")
        status = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusCard.addView(status)
        content.addView(statusCard, params())

        val permissionCard = card()
        label(permissionCard, "Root Realm Permission")
        permission = TextView(this).apply { textSize = 16f }
        permissionCard.addView(permission)
        content.addView(permissionCard, params())

        content.addView(button("Grant Shizuku Permission").apply {
            setOnClickListener { requestPermission() }
        }, params())

        content.addView(button("Open Shizuku").apply {
            setOnClickListener { openShizuku() }
        }, params())

        content.addView(button("Refresh Status").apply {
            setOnClickListener { refreshStatus() }
        }, params())

        val info = card()
        label(
            info,
            "Shizuku provides a separate elevated execution backend using shell/ADB or root. Root Realm can use it when normal Android permissions are insufficient.",
            13f,
            gray
        )
        content.addView(info, params())

        setContentView(root)
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }

        status.text = if (running) "RUNNING" else "NOT RUNNING"
        status.setTextColor(if (running) green else red)

        if (!running) {
            permission.text = "Start Shizuku first."
            permission.setTextColor(gray)
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }

        permission.text = if (granted) "GRANTED" else "NOT GRANTED"
        permission.setTextColor(if (granted) green else red)
    }

    private fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Start Shizuku first.", Toast.LENGTH_SHORT).show()
                openShizuku()
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Unable to request Shizuku permission.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) refreshStatus()
    }

    private fun openShizuku() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(
                "moe.shizuku.privileged.api"
            )
            if (launch != null) {
                startActivity(launch)
            } else {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://shizuku.rikka.app/download/")
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(cardColor, 18)
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(white)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(Color.rgb(28, 28, 28), 18)
        minimumHeight = dp(58)
    }

    private fun label(parent: LinearLayout, text: String, size: Float = 14f, color: Int = gray) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(2), 0, dp(4))
        })
    }

    private fun params() = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(5)
        bottomMargin = dp(5)
    }

    private fun rounded(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
