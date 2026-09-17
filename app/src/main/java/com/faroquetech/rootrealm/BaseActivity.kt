package com.faroquetech.rootrealm

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.faroquetech.theme.ThemeManager

open class BaseActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow the system to report the real system-bar insets.
        // The BaseActivity then applies those insets to the
        // activity's root content automatically.
        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySystemBarInsets(view)
    }

    override fun setContentView(
        view: View,
        params: ViewGroup.LayoutParams
    ) {
        super.setContentView(view, params)
        applySystemBarInsets(view)
    }

    /**
     * Themed toast with no icon.
     *
     * Toast.makeText() only carries text, but some OEM skins (Samsung/
     * MIUI-style launchers) composite the app's own launcher icon into
     * the system toast chrome automatically. That's OS-level behavior
     * tied to icon caching, not something app code controls or can
     * refresh on demand. Building the toast from our own view bypasses
     * that OS-composed chrome entirely, so no icon renders regardless
     * of device skin or icon-cache state.
     *
     * Wrapped in runOnUiThread so screens that trigger a toast off the
     * UI thread (root/shell command results, background workers) don't
     * each need to remember to hop threads before calling this.
     *
     * Lives here, once, instead of duplicated per-Activity so every
     * screen shares one implementation.
     */
    protected fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        runOnUiThread {
            val palette = ThemeManager.current(this)
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density + .5f).toInt()

            val toastView = TextView(this).apply {
                text = message
                textSize = 14f
                setTextColor(palette.text)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    setColor(palette.surface2)
                    setStroke(dp(1), ThemeManager.borderColor(palette))
                    cornerRadius = dp(12).toFloat()
                }
            }

            // Built without .apply{} deliberately: inside a Toast.apply
            // block, an unqualified `duration` resolves to the Toast
            // receiver's own `duration` property rather than this
            // function's parameter of the same name, silently making the
            // assignment a no-op. Plain statements avoid that shadowing.
            val toast = Toast(this)
            toast.duration = duration
            toast.view = toastView
            toast.show()
        }
    }

    private var pressedButton: Button? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = findButtonAt(event.rawX, event.rawY)
                pressedButton?.let { button ->
                    if (button.isEnabled) {
                        button.isClickable = true
                        button.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(70)
                            .start()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                pressedButton?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(70)
                    ?.start()
                pressedButton = null
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private fun findButtonAt(rawX: Float, rawY: Float): Button? {
        val root = window.decorView
        return findButtonInView(root, rawX, rawY)
    }

    private fun findButtonInView(view: View, rawX: Float, rawY: Float): Button? {
        if (!view.isShown) return null

        if (view is Button && view.isEnabled) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)

            if (
                rawX >= location[0] &&
                rawX <= location[0] + view.width &&
                rawY >= location[1] &&
                rawY <= location[1] + view.height
            ) {
                return view
            }
        }

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val result = findButtonInView(
                    view.getChildAt(i),
                    rawX,
                    rawY
                )
                if (result != null) return result
            }
        }

        return null
    }

    private fun applySystemBarInsets(root: View) {

        // Save the original padding so existing UI spacing
        // is not destroyed.
        val originalLeft =
            root.paddingLeft

        val originalTop =
            root.paddingTop

        val originalRight =
            root.paddingRight

        val originalBottom =
            root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(
            root
        ) { view, insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

            view.setPadding(
                originalLeft + systemBars.left,
                originalTop + systemBars.top,
                originalRight + systemBars.right,
                originalBottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)
    }
}