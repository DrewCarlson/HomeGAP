package homegap.presentation.data

import dev.zacsweers.metro.Qualifier

/**
 * The application-lifetime coroutine scope. Work launched here (controller actions) outlives the
 * screen that started it, so navigating away never cancels a request half-way.
 */
@Qualifier
annotation class AppCoroutineScope
