package com.faroquetech.theme

import android.content.Context
import android.graphics.Color
import android.view.View

object ThemeManager {
    private const val PREFS = "rootrealm_theme"
    private const val KEY_THEME = "theme_name"

    data class Palette(
        val background: Int,
        val surface: Int,
        val surface2: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val success: Int,
        val error: Int,
        // Empty by default: themes that leave this out (every one except
        // "Lime" so far) keep the single hue-matched border from
        // borderColor(). A theme that sets this gets a multi-colored
        // outline instead — see outlineColorFor().
        val outlineColors: List<Int> = emptyList()
    )

    data class ThemeDefinition(val name: String, val palette: Palette)

    private fun p(
        bg: String,
        surface: String,
        surface2: String,
        text: String,
        secondary: String,
        accent: String,
        success: String,
        error: String,
        outline: List<String> = emptyList()
    ) = Palette(
        Color.parseColor(bg),
        Color.parseColor(surface),
        Color.parseColor(surface2),
        Color.parseColor(text),
        Color.parseColor(secondary),
        Color.parseColor(accent),
        Color.parseColor(success),
        Color.parseColor(error),
        outline.map { Color.parseColor(it) }
    )

    val builtInThemes = listOf(
        ThemeDefinition("Obsidian", p("#050505", "#121212", "#1C1C1C", "#FFFFFF", "#AAAAAA", "#5A96FF", "#6ED282", "#EB6969")),
        ThemeDefinition("Midnight", p("#070B14", "#101827", "#182337", "#F5F7FF", "#AAB5C7", "#6EA8FF", "#70D6A0", "#FF7474")),
        ThemeDefinition("Emerald", p("#050B08", "#0D1813", "#14251D", "#F3FFF8", "#A7BDB1", "#52D69A", "#70D6A0", "#FF7474")),
        ThemeDefinition("Purple", p("#09060F", "#171020", "#241733", "#FBF7FF", "#B9ADCA", "#B88CFF", "#B88CFF", "#FF7474")),
        ThemeDefinition("Amber", p("#0B0905", "#19140A", "#29200F", "#FFF9ED", "#C5B89C", "#FFB84D", "#70D6A0", "#FF7474")),
        ThemeDefinition("Graphite", p("#08090B", "#111318", "#1B1E24", "#F4F5F7", "#9AA0AA", "#A7B0BC", "#70D6A0", "#FF7474")),
        ThemeDefinition("Crimson", p("#0C0505", "#190A0A", "#261010", "#FFF5F5", "#C9A0A0", "#FF5C5C", "#70D6A0", "#FF8A65")),
        ThemeDefinition("Teal", p("#04090A", "#0B1416", "#122024", "#F0FBFC", "#9DBEC2", "#3FD8E0", "#70D6A0", "#FF7474")),
        ThemeDefinition("Rose", p("#0C0509", "#1A0D13", "#28151E", "#FFF3F8", "#CBA8B8", "#FF7AB8", "#70D6A0", "#FF7474")),
        ThemeDefinition("Indigo", p("#05050F", "#0D0D1F", "#15152E", "#F3F3FF", "#A6A6C7", "#7C83FD", "#70D6A0", "#FF7474")),
        ThemeDefinition("Copper", p("#0B0704", "#190F09", "#271A0F", "#FFF6ED", "#C9B49C", "#E8965A", "#70D6A0", "#FF7474")),
        ThemeDefinition("Slate", p("#07090A", "#101315", "#191E21", "#F2F5F6", "#9FAAB0", "#7FA8B8", "#70D6A0", "#FF7474")),
        ThemeDefinition(
            "Lime",
            p(
                "#050803", "#0D140A", "#182410", "#F6FFEE", "#AABF9A",
                "#C6FF00", "#33E0B0", "#FF6B6B",
                outline = listOf("#C6FF00", "#33E0B0", "#6EA8FF", "#FF7AB8", "#FFB84D")
            )
        )
    )

    fun current(context: Context): Palette {
        val n = currentName(context)
        return builtInThemes.firstOrNull { it.name == n }?.palette
            ?: builtInThemes.first().palette
    }

    fun currentName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "Obsidian") ?: "Obsidian"

    fun apply(context: Context, name: String): Boolean {
        if (builtInThemes.none { it.name == name }) return false

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, name)
            .apply()

        return true
    }

    /**
     * Themed border/stroke color for cards and bubbles.
     *
     * Cards already get their fill re-tinted to `palette.surface` /
     * `surface2` at runtime. Before this, their stroke kept whatever
     * hardcoded gray the original screen was written with, so under a
     * colored theme (e.g. "Midnight"'s navy) the border no longer
     * belonged to the same hue family as the fill or the background —
     * that's what makes a themed card look like it's floating on top
     * of the screen instead of sitting flush with it.
     *
     * Deriving the border from `surface2` (rather than a 13th hardcoded
     * hex per theme) keeps it automatically in the same hue family as
     * the card itself for every current and future theme.
     */
    fun borderColor(palette: Palette): Int {
        return blend(palette.surface2, Color.WHITE, 0.12f)
    }

    /**
     * Outline color for one specific card/input under [palette].
     *
     * Themes that leave `outlineColors` empty (the default) all get
     * borderColor()'s single hue-matched border, same as before.
     *
     * A theme that sets `outlineColors` (currently just "Lime") instead
     * cycles each bordered view through that list. The pick is derived
     * from the view's identity hash rather than, say, an incrementing
     * counter, so the same view lands on the same color every time
     * applyTheme() re-runs (rotation, resume, a manual refresh) instead
     * of jumping to a different color on every pass.
     */
    fun outlineColorFor(view: View, palette: Palette): Int {
        if (palette.outlineColors.isEmpty()) return borderColor(palette)

        val index = (System.identityHashCode(view) and 0x7FFFFFFF) % palette.outlineColors.size
        return palette.outlineColors[index]
    }

    private fun blend(base: Int, target: Int, ratio: Float): Int {
        val inverse = 1f - ratio

        val r = (Color.red(base) * inverse + Color.red(target) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(base) * inverse + Color.green(target) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inverse + Color.blue(target) * ratio).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
    }
}
