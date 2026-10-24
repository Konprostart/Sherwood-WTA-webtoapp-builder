package com.sherwood.wta.di

import com.sherwood.wta.core.activation.ActivationManager
import com.sherwood.wta.core.adblock.AdBlocker
import com.sherwood.wta.core.announcement.AnnouncementManager
import com.sherwood.wta.core.crypto.KeyManager
import com.sherwood.wta.core.shell.ShellModeManager
import com.sherwood.wta.core.stats.AppStatsRepository
import com.sherwood.wta.core.stats.AppUsageTracker
import com.sherwood.wta.core.stats.AppHealthMonitor
import com.sherwood.wta.core.stats.WebsiteScreenshotService
import com.sherwood.wta.core.stats.BatchImportService
import com.sherwood.wta.data.database.AppDatabase
import com.sherwood.wta.data.repository.AppCategoryRepository
import com.sherwood.wta.data.repository.WebAppRepository
import com.sherwood.wta.core.usecase.SaveAppUseCase
import com.sherwood.wta.ui.viewmodel.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val databaseModule = module {
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().webAppDao() }
    single { get<AppDatabase>().appCategoryDao() }
    single { get<AppDatabase>().appUsageStatsDao() }
}

val repositoryModule = module {
    single { WebAppRepository(get()) }
    single { AppCategoryRepository(get()) }
    single { AppStatsRepository(get()) }
}

val managerModule = module {
    single { ActivationManager(androidContext()) }
    single { AnnouncementManager(androidContext()) }
    single { AdBlocker() }
    single { ShellModeManager(androidContext()) }
    single { KeyManager.getInstance(androidContext()) }
    single { AppUsageTracker(get()) }
    single { AppHealthMonitor.getInstance(androidContext(), get()) }
    single { WebsiteScreenshotService.getInstance(androidContext()) }
    single { BatchImportService(androidContext(), get()) }
}

val useCaseModule = module {
    factory { SaveAppUseCase(get()) }
}

val viewModelModule = module {
    viewModel { MainViewModel(get(), get(), get()) }
}

val appModules = listOf(
    databaseModule,
    repositoryModule,
    managerModule,
    useCaseModule,
    viewModelModule
)
