package com.bardsoftware.papeeria.backend

import com.google.common.io.Closer
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.io.*
import java.util.zip.InflaterInputStream

private val LOG = LoggerFactory.getLogger("base.file.content-storage")

/**
 * @author dbarashev@bardsoftware.com
 */
interface ContentStorage {
  @Throws(ContentStorageException::class)
  suspend fun getContent(file: FileProcessingBackendProto.FileDto): ByteArray?
}
class ContentStorageException : Exception()

@Throws(IOException::class)
fun inflate(contentStream: InputStream): ByteArray {
  val closer = Closer.create()
  try {
    val bufferedStream = closer.register(BufferedInputStream(closer.register(contentStream)))
    val ois = closer.register(ObjectInputStream(closer.register(InflaterInputStream(bufferedStream))))
    return ois.readObject() as ByteArray
  } catch (e: Throwable) {
    throw closer.rethrow(e)
  } finally {
    closer.close()
  }
}

@Throws(IOException::class)
fun inflate(compressed: ByteArray): ByteArray {
  return inflate(ByteArrayInputStream(compressed))
}

class PostgresContentStorage(args: FileProcessingBackendArgs) : ContentStorage {
  private val dataSource = HikariDataSource().apply {
    username = args.postgresUser
    password = args.postgresPassword
    jdbcUrl = "jdbc:postgresql://${args.postgresAddress}"
  }

  override suspend fun getContent(file: FileProcessingBackendProto.FileDto): ByteArray? {
    LOG.debug("Fetching file id={} name={}", file.id, file.name)
    dataSource.connection.use {
      val stmt = it.prepareStatement("SELECT value FROM FileContent WHERE id=?")
      stmt.setString(1, "${file.id}-data")
      stmt.executeQuery().use { resultSet ->
        return if (resultSet.next()) {
          inflate(resultSet.getBytes(1))
        } else {
          null
        }
      }
    }
  }
}
