package com.bardsoftware.papeeria.backend

import com.bardsoftware.papeeria.backend.FileProcessingBackendProto.FileDto
import com.bardsoftware.papeeria.backend.FileProcessingBackendProto.FileRequestDto
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import org.slf4j.LoggerFactory
import java.lang.Integer.min
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

data class SaveTask(val dto: FileDto,
                    val contents: ByteArray,
                    val isSavedLocally: Boolean,
                    val fetchError: Pair<Boolean, String?> = false to null)

typealias FetchPredicate = (FileDto) -> Boolean
typealias SaveTaskConsumer = (SaveTask) -> Unit

private val LOG = LoggerFactory.getLogger("FileProcessingBackend")

/**
 * @author dbarashev@bardsoftware.com
 */
open class FileProcessingBackend(
    private val args: FileProcessingBackendArgs,
    private val contentStorage: ContentStorage,
    private val procrustes: Procrustes) {
  private val fetchContext = newFixedThreadPoolContext(args.postgresConnections, "FetchThread")

  fun process(taskId: String, task: FileRequestDto): Path {
    val volumePath = procrustes.makeVolume(taskId)
    fetch(task = task, saveTaskConsumer = createSaveTaskConsumer(volumePath))
    return volumePath
  }

  fun fetch(task: FileRequestDto, isFetchNeeded: FetchPredicate = { true },
            saveTaskConsumer: SaveTaskConsumer) {
    val taskChannel = Channel<SaveTask>()
    for (file in task.fileList) {
      GlobalScope.launch(fetchContext) {
        if (!file.contents.isEmpty) {
          taskChannel.send(SaveTask(file, file.contents.toByteArray(), isSavedLocally = false))
          return@launch
        }

        if (isFetchNeeded(file)) {
          taskChannel.send(fetchFile(file))
        } else {
          taskChannel.send(SaveTask(file, ByteArray(0), isSavedLocally = true))
        }
      }
    }

    val barrier = CountDownLatch(task.fileList.size)
    repeat(min(this.args.postgresConnections, task.fileList.size)) {
      GlobalScope.launch {
        for (saveTask in taskChannel) {
          saveTaskConsumer(saveTask)
          barrier.countDown()
        }
      }
    }
    barrier.await()
  }

  fun createSaveTaskConsumer(rootAbsPath: Path): SaveTaskConsumer {
    return { saveTask ->
      if (saveTask.fetchError.first) {
        LOG.error("Failed to fetch file content. File={} message={}", saveTask.dto.id, saveTask.fetchError.second)
      } else {
        if (saveTask.isSavedLocally) {
          // TODO: update fingerprints (see compiler/files.go:319 in texbe)
        } else {
          val relPath = saveTask.dto.name
          val absFilePath = rootAbsPath.resolve(relPath)
          val file = absFilePath.toFile()
          file.parentFile.also {
            it.mkdirs()
          }
          file.writeBytes(saveTask.contents)
        }
      }
    }
  }

  private fun fetchFile(file: FileDto): SaveTask {
    return try {
      val contents = contentStorage.getContent(file)
      return if (contents == null) {
        SaveTask(file, ByteArray(0), false, true to "File not found")
      } else {
        SaveTask(file, contents, false)
      }
    } catch (e: ContentStorageException) {
      LOG.error("Failed to fetch contents of file={}", file.id, e)
      SaveTask(file, ByteArray(0), false, true to e.message)
    }
  }
}


class FileProcessingBackendArgs(parser: ArgParser) {
  val postgresAddress by parser.storing("--pg-address", help = "PostgreSQL server address").default { "localhost" }
  val postgresPassword by parser.storing("--pg-password", help = "PostgreSQL password").default { "foobar" }
  val postgresUser by parser.storing("--pg-user", help = "PostgreSQL user").default { "papeeria" }
  val postgresConnections by parser.storing("--pg-connections", help = "How many concurrent connections can we open") { toInt() }.default { 5 }
}
