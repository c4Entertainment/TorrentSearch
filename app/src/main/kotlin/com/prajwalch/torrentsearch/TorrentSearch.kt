package com.prajwalch.torrentsearch

import android.app.SearchManager
import android.content.Context
import android.content.Intent

import com.prajwalch.torrentsearch.data.repository.SettingsRepository
import com.prajwalch.torrentsearch.di.ViewModelModule
import com.prajwalch.torrentsearch.di.builtinSearchProvidersModule
import com.prajwalch.torrentsearch.di.dataStoreModule
import com.prajwalch.torrentsearch.di.databaseModule
import com.prajwalch.torrentsearch.di.domainModule
import com.prajwalch.torrentsearch.di.networkModule
import com.prajwalch.torrentsearch.di.repositoryModule
import com.prajwalch.torrentsearch.domain.SearchProviderManager
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.DarkTheme
import com.prajwalch.torrentsearch.domain.model.SearchTarget
import com.prajwalch.torrentsearch.providers.Torrentio
import com.prajwalch.torrentsearch.ui.main.MainActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.module

/** Entry points for apps that embed TorrentSearch as a library. */
object TorrentSearch {
    internal const val EXTRA_OPEN_SETTINGS = "com.prajwalch.torrentsearch.OPEN_SETTINGS"
    internal const val EXTRA_CATEGORY = "com.prajwalch.torrentsearch.CATEGORY"
    internal const val EXTRA_TARGET = "com.prajwalch.torrentsearch.TARGET"

    /** Set by the host app; hides TorrentSearch's Appearance settings. */
    @Volatile
    internal var hostManagesAppearance = false
        private set

    /** Set by the host app; hides TorrentSearch's Language setting. */
    @Volatile
    internal var hostManagesLanguage = false
        private set

    private lateinit var koin: Koin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts TorrentSearch's dependency graph. Call once from `Application.onCreate`.
     *
     * @param hostManagesAppearance The host app has its own theme settings and
     *   pushes them in with [setAppearance]; TorrentSearch's are hidden.
     * @param hostManagesLanguage The host app offers the app-wide language
     *   setting itself; TorrentSearch's Language row is hidden.
     */
    fun init(
        context: Context,
        hostManagesAppearance: Boolean = false,
        hostManagesLanguage: Boolean = false,
    ) {
        this.hostManagesAppearance = hostManagesAppearance
        this.hostManagesLanguage = hostManagesLanguage
        koin = startKoin {
            androidContext(context.applicationContext)
            torrentSearchModules()
        }.koin

        // The standalone app asks about this in a dialog on its Home screen, but
        // host apps open the Search screen directly, so the dialog never shows.
        scope.launch {
            val settings = koin.get<SettingsRepository>()
            if (settings.currentEnabledProviderIds() == null) {
                koin.get<SearchProviderManager>().enableDefaultSearchProviders()
            }
            // Providers added as defaults after the list was first saved.
            settings.enableNewDefaultProviderOnce(Torrentio.ID)
        }
    }

    /**
     * Returns an intent that opens TorrentSearch, searching for [query] in
     * [category] straight away.
     *
     * Pass a [target] to search for an exact movie or show: providers that
     * support it look up by IMDb ID, and results whose release names don't
     * match the target (wrong title, year, season or episode) are hidden.
     */
    fun searchIntent(
        context: Context,
        query: String,
        category: Category = Category.All,
        target: SearchTarget? = null,
    ): Intent {
        return Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(EXTRA_CATEGORY, category.name)
            .putExtra(EXTRA_TARGET, target?.encode())
    }

    /**
     * Themes TorrentSearch's screens like the host app's. Call after [init]
     * and whenever the host's theme settings change.
     */
    fun setAppearance(darkTheme: DarkTheme, dynamicTheme: Boolean, pureBlack: Boolean) {
        scope.launch {
            val settings = koin.get<SettingsRepository>()
            settings.setDarkTheme(darkTheme)
            settings.enableDynamicTheme(dynamicTheme)
            settings.enablePureBlack(pureBlack)
        }
    }

    /** Returns an intent that opens TorrentSearch's home screen. */
    fun homeIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java)
    }

    /** Returns an intent that opens TorrentSearch's settings. */
    fun settingsIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_SETTINGS, true)
    }
}

/** Registers every Koin module TorrentSearch needs. */
internal fun KoinApplication.torrentSearchModules() {
    modules(
        builtinSearchProvidersModule,
        dataStoreModule,
        databaseModule,
        domainModule,
        networkModule,
        repositoryModule,
    )
    module<ViewModelModule>()
}
