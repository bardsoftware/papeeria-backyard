/**
Copyright 2019 BarD Software s.r.o

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
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
