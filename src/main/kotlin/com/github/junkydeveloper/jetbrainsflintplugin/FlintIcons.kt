package com.github.junkydeveloper.jetbrainsflintplugin

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil

/** Shared plugin icons. */
object FlintIcons {
    /** Flint logo (64x64 source) scaled to 16x16 for run/debug configuration types. */
    @JvmField
    val FLINT = IconLoader.getIcon("/icon/flint.png", FlintIcons::class.java).let { icon ->
        if (icon.iconWidth > 0) IconUtil.scale(icon, null, 16f / icon.iconWidth) else icon
    }
}
