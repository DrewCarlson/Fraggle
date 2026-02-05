import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest

/**
 * State for data that supports background refresh without showing loading.
 */
sealed class DataState<out T> {
    /** Initial loading - no data yet */
    data object Loading : DataState<Nothing>()

    /** Data loaded successfully */
    data class Success<T>(
        val data: T,
        val isRefreshing: Boolean = false,
    ) : DataState<T>()

    /** Error loading data */
    data class Error(
        val message: String,
        val previousData: Any? = null,
    ) : DataState<Nothing>()
}

/**
 * Helper to get data from a DataState, or null if not loaded.
 */
fun <T> DataState<T>.dataOrNull(): T? = when (this) {
    is DataState.Success -> data
    else -> null
}

/**
 * Helper to check if data is in initial loading state.
 */
fun <T> DataState<T>.isInitialLoading(): Boolean = this is DataState.Loading

/**
 * Helper to check if data is refreshing (but has previous data).
 */
fun <T> DataState<T>.isRefreshing(): Boolean = this is DataState.Success && isRefreshing

/**
 * Composable for loading data with support for background refresh.
 *
 * @param key Key to trigger reload when changed
 * @param refreshTriggers Flow of triggers that should cause a background refresh
 * @param load Suspend function to load the data
 */
@Composable
fun <T> rememberDataLoader(
    vararg key: Any?,
    wsService: WebSocketService? = null,
    refreshOn: Set<RefreshTrigger> = emptySet(),
    load: suspend () -> T,
): DataState<T> {
    var state by remember { mutableStateOf<DataState<T>>(DataState.Loading) }

    // Initial load and key-based reload
    LaunchedEffect(*key) {
        // If we already have data, mark as refreshing instead of loading
        val currentData = state.dataOrNull()
        if (currentData != null) {
            state = DataState.Success(currentData, isRefreshing = true)
        }

        state = try {
            val data = load()
            DataState.Success(data)
        } catch (e: Exception) {
            if (currentData != null) {
                // Keep showing old data on refresh error
                DataState.Success(currentData as T, isRefreshing = false)
            } else {
                DataState.Error(e.message ?: "Failed to load data")
            }
        }
    }

    // WebSocket-triggered refresh
    if (wsService != null && refreshOn.isNotEmpty()) {
        LaunchedEffect(wsService, refreshOn) {
            wsService.refreshTriggers.collectLatest { trigger ->
                if (trigger in refreshOn) {
                    val currentData = state.dataOrNull()
                    if (currentData != null) {
                        state = DataState.Success(currentData, isRefreshing = true)
                    }

                    state = try {
                        val data = load()
                        DataState.Success(data)
                    } catch (e: Exception) {
                        if (currentData != null) {
                            DataState.Success(currentData as T, isRefreshing = false)
                        } else {
                            state // Keep current state on error
                        }
                    }
                }
            }
        }
    }

    return state
}

/**
 * Composable for triggering manual refresh.
 */
@Composable
fun <T> rememberRefreshableDataLoader(
    vararg key: Any?,
    wsService: WebSocketService? = null,
    refreshOn: Set<RefreshTrigger> = emptySet(),
    load: suspend () -> T,
): Pair<DataState<T>, () -> Unit> {
    var refreshCounter by remember { mutableStateOf(0) }

    val state = rememberDataLoader(
        *key,
        refreshCounter,
        wsService = wsService,
        refreshOn = refreshOn,
        load = load,
    )

    val refresh: () -> Unit = { refreshCounter++ }

    return state to refresh
}
