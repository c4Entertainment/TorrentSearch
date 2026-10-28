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
import com.prajwalch.torrentsearch.ui.main.MainActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.module

/** Entry points for apps that embed TorrentSearch as a library. */
object TorrentSearch {
    internal const val EXTRA_OPEN_SETTINGS = "com.prajwalch.torrentsearch.OPEN_SETTINGS"

    /** Starts TorrentSearch's dependency graph. Call once from `Application.onCreate`. */
    fun init(context: Context) {
        val koin = startKoin {
            androidContext(context.applicationContext)
            torrentSearchModules()
        }.koin

        // The standalone app asks about this in a dialog on its Home screen, but
        // host apps open the Search screen directly, so the dialog never shows.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (koin.get<SettingsRepository>().currentEnabledProviderIds() == null) {
                koin.get<SearchProviderManager>().enableDefaultSearchProviders()
            }
        }
    }

    /** Returns an intent that opens TorrentSearch, searching for [query] straight away. */
    fun searchIntent(context: Context, query: String): Intent {
        return Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, query)
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
