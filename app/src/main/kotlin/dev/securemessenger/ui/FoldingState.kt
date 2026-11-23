package dev.securemessenger.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.collectLatest

data class FoldState(
    val halfOpened: Boolean = false,
    val horizontal: Boolean = false,
)

@Composable
fun rememberFoldState(): State<FoldState> {
    val activity = LocalContext.current.findActivity()
    val state = remember { mutableStateOf(FoldState()) }
    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collectLatest { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                .firstOrNull { it.isSeparating }
            state.value = FoldState(
                halfOpened = fold?.state == FoldingFeature.State.HALF_OPENED,
                horizontal = fold?.orientation == FoldingFeature.Orientation.HORIZONTAL,
            )
        }
    }
    return state
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
