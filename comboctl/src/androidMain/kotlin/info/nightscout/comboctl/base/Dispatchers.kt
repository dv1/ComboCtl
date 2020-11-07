package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
