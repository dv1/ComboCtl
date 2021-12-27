package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineDispatcher

// Abstract away the IO dispatcher, since Dispatchers.IO is
// not available on all platforms.
internal expect fun ioDispatcher(): CoroutineDispatcher

// This manages a dispatcher that always runs coroutines in
// a sequence, one task after the other. It is useful for cases
// where parallelism would cause errors. With the Combo, it is
// useful to do this, since parallel IO is not supported by the
// pump, and only causes IO errors. Implementations may use
// one dedicated thread just for this dispatcher, or may use
// a thread pool but disallow parallelism.
//
// Currently, there is no such dispatcher available in
// kotlin.coroutines, but specific platforms do, so use the
// expect-actual framework in kotlin-multiplatform for this.
// We have to use a class like this though because the platform
// specific dispatcher may need to be acquired and released
// or shut down once it is no longer needed. Otherwise, a
// resource leak occurs. The prime example is the single
// threaded executor service in the JVM, which provides this
// functionality, but has to be manually ended by calling
// its shutdown() function.
//
// Note that the functions must not throw.
//
// TODO: Once we move to Kotlin 1.6, replace platform specific
// code with the new "limitedParallelism" feature in dispatchers.
//
// See: https://github.com/Kotlin/kotlinx.coroutines/issues/2919
internal expect class SequencedDispatcherManager() {
    fun acquireDispatcher()
    fun releaseDispatcher()
    val dispatcher: CoroutineDispatcher
}
