import androidx.compose.runtime.*
import kotlinx.browser.window
import org.w3c.dom.events.Event

const val MOBILE_BREAKPOINT = 768

@Composable
fun rememberIsMobile(): Boolean {
    var isMobile by remember {
        mutableStateOf(window.innerWidth < MOBILE_BREAKPOINT)
    }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            isMobile = window.innerWidth < MOBILE_BREAKPOINT
        }
        window.addEventListener("resize", listener)
        onDispose { window.removeEventListener("resize", listener) }
    }
    return isMobile
}
