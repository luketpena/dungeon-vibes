package com.dungeonvibes.di

import com.dungeonvibes.data.bulb.DiscoveryLock
import android.os.SystemClock
import com.dungeonvibes.light.AndroidNetworkStatus
import com.dungeonvibes.light.ApplicationScope
import com.dungeonvibes.light.NetworkStatus
import com.dungeonvibes.light.LightAdapter
import com.dungeonvibes.light.MonotonicClock
import com.dungeonvibes.light.MulticastDiscoveryLock
import com.dungeonvibes.light.lifx.LifxLanAdapter
import com.dungeonvibes.light.lifx.LifxTransport
import com.dungeonvibes.light.lifx.UdpLifxTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// @IntoSet collects every LightAdapter into one Set<LightAdapter> that LightController receives.
// A new protocol later is one more @IntoSet binding here.

@Module
@InstallIn(SingletonComponent::class)
abstract class LightBindings {
    @Binds
    abstract fun bindLifxTransport(impl: UdpLifxTransport): LifxTransport

    @Binds
    abstract fun bindDiscoveryLock(impl: MulticastDiscoveryLock): DiscoveryLock

    @Binds
    abstract fun bindNetworkStatus(impl: AndroidNetworkStatus): NetworkStatus

    @Binds
    @IntoSet
    abstract fun bindLifxAdapter(impl: LifxLanAdapter): LightAdapter
}

@Module
@InstallIn(SingletonComponent::class)
object LightProviders {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
}
