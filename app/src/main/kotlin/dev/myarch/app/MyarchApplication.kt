package dev.myarch.app

import android.app.Application
import dev.myarch.app.data.MyarchSettings
import dev.myarch.runtime.MyarchPaths
import dev.myarch.runtime.container.ContainerManager
import dev.myarch.runtime.theme.BuiltInThemes
import dev.myarch.runtime.theme.OmarchyTheme

/**
 * Holds the few objects that outlive any one screen.
 *
 * A dependency injection framework would be more machinery than this app needs:
 * there are four long-lived objects and one of them is the compositor service,
 * which Android owns anyway.
 */
public class MyarchApplication : Application() {

    public lateinit var paths: MyarchPaths
        private set

    public lateinit var settings: MyarchSettings
        private set

    public lateinit var containerManager: ContainerManager
        private set

    override fun onCreate() {
        super.onCreate()
        paths = MyarchPaths(this)
        settings = MyarchSettings(this)
        containerManager = ContainerManager(
            paths = paths,
            sharedBindings = ContainerManager.optionalSharedStorage(this),
        )
        paths.prepare()
        AssetStaging(this, paths).stageIfNeeded()
    }

    /** The theme the user selected, falling back to the default. */
    public val theme: OmarchyTheme
        get() = BuiltInThemes.byId(settings.themeId) ?: BuiltInThemes.default

    public companion object {
        /** Convenience accessor; every Context in the app can reach it. */
        public fun of(context: android.content.Context): MyarchApplication =
            context.applicationContext as MyarchApplication
    }
}
