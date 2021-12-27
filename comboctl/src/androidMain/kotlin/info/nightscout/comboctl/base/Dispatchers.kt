package info.nightscout.comboctl.base

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

private val logger = Logger.get("ExtraAndroidDispatchers")

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
            try {
                // It is important to explicitly call this function before
                // the application ends. Otherwise, the associated thread
                // may not be terminated properly, especially if C++ JNI
                // code is involved, and the JVM never quits.
                internalExecutor!!.shutdown()
            } catch (t: Throwable) {
                logger(LogLevel.WARN) { "Error while shutting down executor: $t ; swallowing this error" }
                // In theory, shutdown() should not throw, since its only
                // possible exception is SecurityException, which should
                // not be a concern to us. Still, to be safe, swallow
                // exceptions here. (Can't do anything else at this stage.)
            } finally {
                internalExecutor = null
            }
        }
    }

    actual val dispatcher: CoroutineDispatcher
        get() {
            assert(internalDispatcher != null) { "Dispatcher was not acquired" }
            return internalDispatcher!!
        }
}
