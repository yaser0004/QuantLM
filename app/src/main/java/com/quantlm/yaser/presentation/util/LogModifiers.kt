package com.quantlm.yaser.presentation.util

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import com.quantlm.yaser.data.diagnostics.AppEventLogger

/**
 * Aggressive-logging composables and modifiers. All write through the async
 * [AppEventLogger] pipeline so they never block the UI thread.
 */

/**
 * Drop-in replacement for `Modifier.clickable { … }` that also writes an
 * info-level event before invoking the handler. Use for any user action that
 * matters for diagnostics (toolbar icons, dialog buttons, list-item selects).
 *
 * Example:
 * ```
 * IconButton(onClick = {})
 *   .let { /* prefer using Modifier-level wrappers */ }
 *
 * Box(modifier = Modifier.logClick(
 *     component = "ChatScreen",
 *     action = "open_image_picker"
 * ) { onPick() })
 * ```
 */
fun Modifier.logClick(
    component: String,
    action: String,
    details: String = "",
    onClick: () -> Unit,
): Modifier = this.clickable {
    AppEventLogger.info(component = component, action = action, details = details)
    onClick()
}

/**
 * One-liner screen-entry/exit instrumentation. Drop into the top of any
 * top-level screen composable to log when the screen enters composition and
 * when it leaves. Pairs with the NavController destination logger to give a
 * complete picture of "what screen is the user actually looking at".
 *
 * `DisposableEffect(Unit)` only re-runs if the composable leaves and re-enters
 * composition, which matches our "screen entered / exited" intent — it does
 * not fire on every recomposition.
 */
@Composable
fun LogScreenLifecycle(name: String) {
    DisposableEffect(Unit) {
        AppEventLogger.info(component = "Screen", action = "entered", details = "name=$name")
        onDispose {
            AppEventLogger.info(component = "Screen", action = "exited", details = "name=$name")
        }
    }
}
