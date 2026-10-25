package com.tabeer.travelhub.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small helpers for crash-safe local file persistence. */
object FileIo {
    /** Writes [text] to a temp file and atomically moves it over [file], so a crash never leaves a half-written file. */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Moves an unreadable file aside (never deletes user data silently). */
    fun quarantine(file: File) {
        if (file.exists()) file.renameTo(File(file.path + ".corrupt"))
    }
}
