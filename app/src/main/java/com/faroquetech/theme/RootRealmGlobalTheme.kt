package com.faroquetech.theme

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Root Realm global theme.
 *
 * Themes backgrounds, cards/bubbles, buttons, inputs and text across
 * every Activity while preserving the existing rounded shapes.
 */
class RootRealmGlobalTheme : Application() {

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: android.os.Bundle?
                ) {
                    activity.window.decorView.post {
                        applyTheme(activity)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    activity.window.decorView.post {
                        applyTheme(activity)
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: android.os.Bundle
                ) = Unit
            }
        )
    }

    companion object {

        /**
         * Re-runs the theming pass on demand.
         *
         * The lifecycle callbacks above cover normal navigation, but any
         * screen that updates its own views outside onCreate/onResume
         * (e.g. a manual refresh action, a broadcast receiver callback)
         * needs to call this itself afterward, or those views are left
         * with whatever colors the screen's own code just set — which
         * is not necessarily the active theme's palette.
         */
        fun applyTheme(activity: Activity) {
            if (activity is ThemeManagerActivity) {
                /*
                 * The picker's "Built-in Themes" list intentionally renders
                 * each row in THAT theme's own colors, so the user can
                 * compare palettes side by side — not the currently active
                 * one. Auto-recoloring would overwrite every row with the
                 * active palette and defeat the whole point of the picker,
                 * so this screen is left to theme itself.
                 */
                return
            }

            val root = activity.findViewById<View>(android.R.id.content) ?: return
            val palette = ThemeManager.current(activity)

            activity.window.statusBarColor = palette.background
            activity.window.navigationBarColor = palette.background

            root.setBackgroundColor(palette.background)
            applyView(root, palette, palette.background)
        }

        private fun applyView(
            view: View,
            palette: ThemeManager.Palette,
            parentColor: Int
        ) {
            val ownColor = solidBackgroundColor(view)
            val effectiveColor = ownColor ?: parentColor

            when (view) {
                is Button -> {
                    /*
                     * Buttons are bubbles too. Recolor the existing drawable
                     * instead of replacing it, so rounded corners/strokes remain.
                     */
                    themeBubbleBackground(view, palette, BubbleType.SURFACE2)
                    view.setTextColor(contrastingText(palette, BubbleType.SURFACE2))
                }

                is EditText -> {
                    themeBubbleBackground(view, palette, BubbleType.SURFACE)
                    view.setTextColor(contrastingText(palette, BubbleType.SURFACE))
                    view.setHintTextColor(palette.secondary)
                }

                is TextView -> {
                    val current = view.currentTextColor

                    when {
                        isRed(current) -> view.setTextColor(palette.error)
                        isGreen(current) -> view.setTextColor(palette.success)
                        isDark(effectiveColor) -> {
                            // Dark bubble/card -> always use readable light text.
                            view.setTextColor(Color.WHITE)
                        }
                        view.textSize <= 14f -> {
                            view.setTextColor(palette.secondary)
                        }
                        else -> {
                            view.setTextColor(palette.text)
                        }
                    }
                }

                else -> {
                    /*
                     * Theme ordinary card/bubble backgrounds. Only known simple
                     * shape/color drawables are modified; custom drawables stay
                     * untouched.
                     */
                    themeBubbleBackground(view, palette, BubbleType.SURFACE)
                }
            }

            if (view is ViewGroup) {
                val nextParentColor = solidBackgroundColor(view) ?: parentColor

                for (i in 0 until view.childCount) {
                    applyView(
                        view.getChildAt(i),
                        palette,
                        nextParentColor
                    )
                }
            }
        }

        private enum class BubbleType {
            SURFACE,
            SURFACE2
        }

        private fun themeBubbleBackground(
            view: View,
            palette: ThemeManager.Palette,
            type: BubbleType
        ) {
            val drawable = view.background ?: return

            val target = when (type) {
                BubbleType.SURFACE -> palette.surface
                BubbleType.SURFACE2 -> palette.surface2
            }

            when (drawable) {
                is ColorDrawable -> {
                    drawable.color = target
                }

                is GradientDrawable -> {
                    /*
                     * mutate() first. Views inflated from the same XML
                     * drawable resource share one ConstantState by default,
                     * so writing to `drawable` without unsharing it here
                     * writes into every other view built from that same
                     * resource too. That was invisible while every SURFACE
                     * bubble got the same single border color (the shared
                     * write was a no-op in practice), but now that
                     * retintBorder() gives each view its own stroke color,
                     * views sharing a resource fight over that one shared
                     * stroke value — which is what shows up as borders
                     * flickering or disappearing on the next redraw (e.g.
                     * on touch).
                     */
                    val mutable = drawable.mutate() as GradientDrawable
                    if (mutable !== view.background) {
                        view.background = mutable
                    }

                    /*
                     * setColor() changes only the fill. Existing:
                     * - corner radius
                     * - shape
                     * - padding/insets
                     * remain intact.
                     */
                    mutable.setColor(target)

                    if (type == BubbleType.SURFACE) {
                        retintBorder(mutable, palette, view)
                    }
                }
            }
        }

        /**
         * Re-tints a card's border to the palette's border color.
         *
         * GradientDrawable has no public getter to check whether a stroke
         * was already drawn (that's what failed to compile before), so this
         * relies on this app's own convention instead: every SURFACE bubble
         * (cards, list rows, EditText fields) is always built with a 1dp
         * stroke, while SURFACE2 bubbles (plain action buttons) never are.
         * Callers only reach this for SURFACE, never SURFACE2, so a button
         * never gains a border it wasn't designed with.
         *
         * Most themes give every SURFACE bubble the same single border
         * color. A theme with `outlineColors` set (currently "Lime") gets
         * a different color per view instead, picked by outlineColorFor() —
         * that's what makes its outline read as multi-colored across a
         * screen full of cards, rather than a flat lime line everywhere.
         */
        private fun retintBorder(
            drawable: GradientDrawable,
            palette: ThemeManager.Palette,
            view: View
        ) {
            val widthPx = (1 * view.resources.displayMetrics.density)
                .toInt()
                .coerceAtLeast(1)

            drawable.setStroke(widthPx, ThemeManager.outlineColorFor(view, palette))
        }

        private fun solidBackgroundColor(view: View): Int? {
            val drawable = view.background ?: return null

            return when (drawable) {
                is ColorDrawable -> drawable.color
                else -> null
            }
        }

        private fun contrastingText(
            palette: ThemeManager.Palette,
            type: BubbleType
        ): Int {
            val background = when (type) {
                BubbleType.SURFACE -> palette.surface
                BubbleType.SURFACE2 -> palette.surface2
            }

            return if (isDark(background)) {
                Color.WHITE
            } else {
                palette.text
            }
        }

        private fun isDark(color: Int): Boolean {
            if (Color.alpha(color) == 0) return false

            val luminance =
                (0.299 * Color.red(color)) +
                (0.587 * Color.green(color)) +
                (0.114 * Color.blue(color))

            return luminance < 105.0
        }

        private fun isRed(color: Int): Boolean {
            return Color.red(color) > 180 &&
                Color.green(color) < 100 &&
                Color.blue(color) < 100
        }

        private fun isGreen(color: Int): Boolean {
            return Color.green(color) > 150 &&
                Color.red(color) < 120 &&
                Color.blue(color) < 150
        }
    }
}
