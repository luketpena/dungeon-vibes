package com.dungeonvibes.light

import javax.inject.Qualifier

/**
 * A CoroutineScope that lives as long as the app process. Used for work that must outlive a single
 * screen, like an in-flight scene apply or the live-preview senders.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
