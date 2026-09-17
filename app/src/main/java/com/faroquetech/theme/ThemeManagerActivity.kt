package com.faroquetech.theme

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.*

class ThemeManagerActivity : Activity() {

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density + .5f).toInt()

    private fun rounded(fill: Int, r: Int, stroke: Int) =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(r).toFloat()
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        build()
    }

    private fun build() {
        val t = ThemeManager.current(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.background)
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = "Theme Manager"
                textSize = 27f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(t.text)
                setPadding(0, 0, 0, dp(8))
            }
        )

        root.addView(
            TextView(this).apply {
                text = "Global Root Realm UI theme"
                textSize = 13f
                setTextColor(t.secondary)
                setPadding(0, 0, 0, dp(12))
            }
        )

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        content.addView(
            TextView(this).apply {
                text = "Built-in Themes"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(t.text)
                setPadding(dp(3), dp(14), 0, dp(8))
            }
        )

        ThemeManager.builtInThemes.forEach { theme ->
            val b = Button(this).apply {
                text = if (
                    theme.name == ThemeManager.currentName(this@ThemeManagerActivity)
                ) "✓ ${theme.name}" else theme.name

                textSize = 15f
                isAllCaps = false
                setTextColor(theme.palette.text)
                background = rounded(
                    theme.palette.surface2,
                    16,
                    theme.palette.accent
                )

                setOnClickListener {
                    ThemeManager.apply(
                        this@ThemeManagerActivity,
                        theme.name
                    )

                    Toast.makeText(
                        this@ThemeManagerActivity,
                        "Theme applied: ${theme.name}",
                        Toast.LENGTH_SHORT
                    ).show()

                    recreate()
                }
            }

            content.addView(
                b,
                LinearLayout.LayoutParams(-1, dp(52)).apply {
                    setMargins(0, dp(5), 0, dp(5))
                }
            )
        }

        content.addView(
            TextView(this).apply {
                text = "Preview"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(t.text)
                setPadding(dp(3), dp(14), 0, dp(8))
            }
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(t.surface, 18, ThemeManager.borderColor(t))
        }

        card.addView(
            TextView(this).apply {
                text = "Root Realm Preview"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(t.text)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Cards, buttons and status text use the global palette."
                textSize = 14f
                setTextColor(t.secondary)
                setPadding(0, dp(8), 0, dp(12))
            }
        )

        card.addView(
            Button(this).apply {
                text = "Accent Button"
                isAllCaps = false
                setTextColor(t.text)
                background = rounded(t.accent, 14, t.accent)
            },
            LinearLayout.LayoutParams(-1, dp(50))
        )

        content.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(5), 0, dp(12))
            }
        )

        content.addView(
            Button(this).apply {
                text = "Reset to Obsidian"
                isAllCaps = false
                setTextColor(t.text)
                background = rounded(t.surface2, 14, t.accent)

                setOnClickListener {
                    ThemeManager.apply(
                        this@ThemeManagerActivity,
                        "Obsidian"
                    )

                    Toast.makeText(
                        this@ThemeManagerActivity,
                        "Theme reset to Obsidian",
                        Toast.LENGTH_SHORT
                    ).show()

                    recreate()
                }
            },
            LinearLayout.LayoutParams(-1, dp(52))
        )

        setContentView(root)
    }
}
