package info.nightscout.comboctl.base

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

internal actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

internal actual class SingleThreadDispatcherManager {
    private var internalExecutor: ExecutorService? = null
    private var internalDispatcher: CoroutineDispatcher? = null

    actual fun acquireDispatcher() {
        assert(internalDispatcher == null) { "Dispatcher was already acquired" }

        internalExecutor = Executors.newSingleThreadExecutor()
        assert(internalExecutor != null) { "Could not get single thread executor" }

        internalDispatcher = internalExecutor!!.asCoroutineDispatcher()
        assert(internalDispatcher != null) { "Could not get single thread dispatcher" }
    }

    actual fun releaseDispatcher() {
        internalDispatcher = null

        if (internalExecutor != null) {
            // It is important to explicitely call this function before
            // the application ends. Otherwise, the associated thread
            // may not be terminated properly, especially if C++ JNI
            // code is involved, and the JVM never quits.
            internalExecutor!!.shutdown()
            internalExecutor = null
        }
    }

    actual val dispatcher: CoroutineDispatcher
        get() {
            assert(internalDispatcher != null) { "Dispatcher was not acquired" }
            return internalDispatcher!!
        }
}
