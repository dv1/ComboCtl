package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// This should be set to a separate IO dispatcher on platforms that
// have such dispatchers, like Dispatchers.IO on JVM and Android.
internal var currentIODispatcher: CoroutineDispatcher = Dispatchers.Default

// Abstract away the IO dispatcher, since Dispatchers.IO is
// not available on all platforms.
internal fun ioDispatcher(): CoroutineDispatcher = currentIODispatcher

// Dispatcher that enforces sequential execution of coroutines,
// thus disallowing parallelism. This is important, since parallel
// IO is not supported by the Combo and only causes IO errors.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal val sequencedDispatcher = Dispatchers.Default.limitedParallelism(1)
