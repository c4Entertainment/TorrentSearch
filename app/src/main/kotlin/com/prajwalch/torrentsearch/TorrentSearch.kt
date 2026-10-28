package com.prajwalch.torrentsearch

import android.app.SearchManager
import android.content.Context
import android.content.Intent

import com.prajwalch.torrentsearch.di.ViewModelModule
import com.prajwalch.torrentsearch.di.builtinSearchProvidersModule
import com.prajwalch.torrentsearch.di.dataStoreModule
import com.prajwalch.torrentsearch.di.databaseModule
import com.prajwalch.torrentsearch.di.domainModule
import com.prajwalch.torrentsearch.di.networkModule
import com.prajwalch.torrentsearch.di.repositoryModule
import com.prajwalch.torrentsearch.ui.main.MainActivity

import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.module

/** Entry points for apps that embed TorrentSearch as a library. */
object TorrentSearch {
    /** Starts TorrentSearch's dependency graph. Call once from `Application.onCreate`. */
    fun init(context: Context) {
        startKoin {
            androidContext(context.applicationContext)
            torrentSearchModules()
        }
    }

    /** Returns an intent that opens TorrentSearch, searching for [query] straight away. */
    fun searchIntent(context: Context, query: String): Intent {
        return Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, query)
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
