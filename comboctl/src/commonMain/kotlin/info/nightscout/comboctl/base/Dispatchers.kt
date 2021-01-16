package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineDispatcher

// Abstract away the IO dispatcher, since Dispatchers.IO is
// not available on all platforms.
internal expect fun ioDispatcher(): CoroutineDispatcher

// This dispatcher explicitely uses one thread for running tasks.
// It is useful for cases where tasks must not be run in parallel
// on separate threads (for thread safety reasons). Sometimes
// achieving thread safety that way is more efficient than using
// synchronization primitives, especially if the tasks don't
// block the thread or don't do it for very long.
// There is no default single-threaded dispatcher in
// kotlin.coroutines, but specific platforms do, so use the
// expect-actual framework in kotlin-multiplatform for this.
// We have to use a class like this though because the platform
// specific dispatcher may need to be acquired and released
// or shut down once it is no longer needed. Otherwise, a
// resource leak occurs. The prime example is the single
// threaded executor service in the JVM, which provides this
// functionality, but has to be manually ended by calling
// its shutdown() function.
internal expect class SingleThreadDispatcherManager() {
    fun acquireDispatcher()
    fun releaseDispatcher()
    val dispatcher: CoroutineDispatcher
}
