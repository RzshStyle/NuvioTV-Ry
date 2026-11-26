package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.iptv.IptvEngine
import com.nuvio.iptv.net.IptvHttp
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.data.iptv.IptvSourceStore
import com.nuvio.tv.data.local.ProfileDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IptvModule {

    @Provides
    @Singleton
    fun iptvEngine(): IptvEngine {
        // Own client: the shared one rewrites User-Agent (providers often require a specific one) and
        // records request URLs in breadcrumbs, which for IPTV would contain the user's credentials.
        // No HTTP cache: the repository caches playlists and guides as files.
        val client = OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
        return IptvEngine(IptvHttp(client))
    }

    @Provides
    @Singleton
    fun iptvRepository(
        @ApplicationContext context: Context,
        store: IptvSourceStore,
        engine: IptvEngine,
        profileDataStore: ProfileDataStore,
    ): IptvRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = IptvRepository(context, store, engine, scope)
        scope.launch { profileDataStore.activeProfileId.collect(repository::selectProfile) }
        return repository
    }
}
