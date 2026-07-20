package one.globalconnect.xtmsagent

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

internal fun loggingCoroutineExceptionHandler(tag: String) =
    CoroutineExceptionHandler { context, error ->
        Log.e(tag, "Uncaught background operation failure in $context", error)
    }
