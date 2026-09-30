package com.repmate.di

import javax.inject.Qualifier

/** Marks the process-lifetime [kotlinx.coroutines.CoroutineScope], for work that must outlive any screen or ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
