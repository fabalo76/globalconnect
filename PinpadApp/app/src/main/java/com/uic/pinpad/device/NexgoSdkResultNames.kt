package com.uic.pinpad.device

import com.nexgo.oaf.apiv3.SdkResult
import java.lang.reflect.Modifier

object NexgoSdkResultNames {
    private val namesByCode: Map<Int, List<String>> by lazy {
        SdkResult::class.java.declaredFields
            .mapNotNull { field ->
                val isStaticInt = Modifier.isStatic(field.modifiers) &&
                    field.type == Int::class.javaPrimitiveType
                if (!isStaticInt) return@mapNotNull null
                runCatching {
                    field.isAccessible = true
                    field.getInt(null) to field.name
                }.getOrNull()
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    fun format(resultCode: Int): String {
        val names = namesByCode[resultCode].orEmpty()
        return if (names.isEmpty()) {
            "$resultCode [Unknown]"
        } else {
            "$resultCode [${names.joinToString(separator = "|")}]"
        }
    }
}
