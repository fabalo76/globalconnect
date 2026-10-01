package one.globalconnect.xtmsagent.remote

import java.io.File

internal class PublicFilePolicy(root: File) {
    private val root = root.canonicalFile

    fun resolve(path: String): File {
        require(path == "/sdcard" || path.startsWith("/sdcard/")) { "FILE_PATH_DENIED" }
        require(path.length <= 4096 && !path.contains('\u0000')) { "FILE_PATH_DENIED" }
        val file = File(root, path.removePrefix("/sdcard").trimStart('/')).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "FILE_PATH_DENIED" }
        val relative = file.relativeTo(root).invariantSeparatorsPath
        require(listOf("Android/data", "Android/obb").none {
            relative == it || relative.startsWith("$it/")
        }) { "FILE_PATH_DENIED" }
        return file
    }

    fun path(file: File): String = "/sdcard" + file.canonicalFile.relativeTo(root)
        .invariantSeparatorsPath.let { if (it.isEmpty()) "" else "/$it" }
}
