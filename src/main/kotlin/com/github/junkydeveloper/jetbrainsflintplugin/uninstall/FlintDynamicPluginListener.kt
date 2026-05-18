package com.github.junkydeveloper.jetbrainsflintplugin.uninstall

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/**
 * Covers the dynamic unload/uninstall-without-restart path. Runs while the
 * plugin classes are still loaded. Skips plugin updates (`isUpdate`).
 */
class FlintDynamicPluginListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (!isUpdate && FlintUninstaller.isSelf(pluginDescriptor)) {
            FlintUninstaller.cleanup()
        }
    }
}
