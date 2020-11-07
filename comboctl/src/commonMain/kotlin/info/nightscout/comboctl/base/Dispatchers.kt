package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineDispatcher

internal expect fun ioDispatcher(): CoroutineDispatcher
