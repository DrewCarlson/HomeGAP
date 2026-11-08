package homegap.web.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import homegap.presentation.app.AppPresenter
import homegap.presentation.data.AppCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlin.time.Clock

/**
 * The dashboard's object graph. Browser implementations (fetch API, localStorage, Date) are
 * contributed with `@ContributesBinding`; presenters come from the presentation module.
 */
@DependencyGraph(AppScope::class)
interface WebAppGraph {
    val appPresenter: AppPresenter

    @Provides
    @SingleIn(AppScope::class)
    @AppCoroutineScope
    fun appScope(): CoroutineScope = MainScope()

    @Provides
    fun clock(): Clock = Clock.System
}
