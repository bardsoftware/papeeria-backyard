package com.bardsoftware.papeeria.backend

import com.google.common.io.Files
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * @author dbarashev@bardsoftware.com
 */
interface Procrustes {
  @Throws(IOException::class)
  fun makeVolume(id: String): Path
}

class PlainProcrustes : Procrustes {
  private val tempDir = Files.createTempDir()

  override fun makeVolume(id: String): Path {
    val dir = File(tempDir, id)
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw IOException("Failed to mkdirs directory ${dir.path}")
      }
    }
    return dir.toPath()
  }
}
