package com.github.junkydeveloper.jetbrainsflintplugin.uninstall

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginStateListener

/**
 * Registers a [PluginStateListener] at app start so the plugin can self-clean
 * when the user uninstalls it via Settings ▸ Plugins / the Marketplace. This is
 * the precise "user uninstalled a plugin" signal (also covers the
 * uninstall-after-restart path, where it fires while our classes are still
 * loaded).
 */
class FlintAppLifecycle : AppLifecycleListener {
    override fun appStarted() {
        PluginInstaller.addStateListener(object : PluginStateListener {
            override fun install(descriptor: IdeaPluginDescriptor) {}

            override fun uninstall(descriptor: IdeaPluginDescriptor) {
                if (FlintUninstaller.isSelf(descriptor)) {
                    FlintUninstaller.cleanup()
                }
            }
        })
    }
}
