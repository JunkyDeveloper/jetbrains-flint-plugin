package com.github.junkydeveloper.jetbrainsflintplugin.uninstall

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginStateListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers a [PluginStateListener] once the app frame is created so the plugin can self-clean
 * when the user uninstalls it via Settings ▸ Plugins / the Marketplace. This is
 * the precise "user uninstalled a plugin" signal (also covers the
 * uninstall-after-restart path, where it fires while our classes are still
 * loaded).
 */
class FlintAppLifecycle : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        initialize()
    }

    override fun welcomeScreenDisplayed() {
        initialize()
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        FlintUninstaller.cleanupBeforeShutdown()
    }

    private fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        FlintUninstaller.retryPendingDeleteAsync()

        PluginInstaller.addStateListener(object : PluginStateListener {
            override fun install(descriptor: IdeaPluginDescriptor) {}

            override fun uninstall(descriptor: IdeaPluginDescriptor) {
                if (FlintUninstaller.isSelf(descriptor)) {
                    FlintUninstaller.cleanupAsync()
                }
            }
        })
    }

    companion object {
        private val initialized = AtomicBoolean(false)
    }
}
