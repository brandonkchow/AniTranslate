package com.example.utils

import android.content.Context
import android.graphics.Typeface

object FontHelper {
    private var comicTypeface: Typeface? = null

    fun getComicTypeface(context: Context): Typeface {
        if (comicTypeface != null) return comicTypeface!!
        comicTypeface = try {
            Typeface.createFromAsset(context.assets, "fonts/comic_font.ttf")
        } catch (e: Exception) {
            // Fallback to bold sans-serif comic style
            Typeface.create("casual", Typeface.BOLD) ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        return comicTypeface!!
    }
}
