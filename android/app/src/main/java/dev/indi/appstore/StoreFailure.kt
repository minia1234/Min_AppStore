package dev.indi.appstore

import android.content.Context
import androidx.annotation.StringRes

internal class StoreFailure(@StringRes val messageResource: Int, cause: Throwable? = null) : IllegalArgumentException(null, cause)

internal fun Throwable.storeMessage(context: Context, @StringRes fallback: Int = R.string.operation_failed): String =
    if (this is StoreFailure) context.getString(messageResource) else message?.takeIf { it.isNotBlank() } ?: context.getString(fallback)
