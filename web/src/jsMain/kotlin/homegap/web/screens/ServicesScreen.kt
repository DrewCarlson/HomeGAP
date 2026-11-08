package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.services.ServicesScreenModel
import homegap.web.ui.Panel

@Composable
fun ServicesScreen(model: ServicesScreenModel) {
    Panel("Services") { ServiceTable(model.services, model.onTogglePause) }
}
