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

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.google.api.client.http.HttpStatusCodes
import com.google.common.io.Files
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.nio.file.Paths

class ProcrustesArgs(parser: ArgParser) {
  val procrustesImpl by parser.storing("--procrustes-impl", help = "Volume manager implementation").default { "plain" }
  val procrustesAddress by parser.storing("--procrustes-address", help = "Procrustes address including port")
  val procrustesPassword by parser.storing("--procrustes-password", help = "Procrustes password")
  val procrustesRoot by parser.storing("--procrustes-root", help = "Root directory with procrustes volumes")
}

data class ProcrustesVolumeRequest(val id: String,
                                   val sizeMb: Int = 64,
                                   val makeClean: Boolean = false)
data class ProcrustesVolumeResponse(val path: Path, val code: Int, val message: String)


/**
 * @author dbarashev@bardsoftware.com
 */
interface Procrustes {
  @Throws(IOException::class)
  suspend fun makeVolume(req: ProcrustesVolumeRequest, outputChannel: Channel<ProcrustesVolumeResponse>)
  suspend fun rmVolume(id: String)
}

class PlainProcrustes : Procrustes {
  private val tempDir = Files.createTempDir()

  override suspend fun makeVolume(req: ProcrustesVolumeRequest, outputChannel: Channel<ProcrustesVolumeResponse>) {
    GlobalScope.launch {
      val dir = File(tempDir, req.id)
      if (!dir.exists()) {
        if (!dir.mkdirs()) {
          outputChannel.close(IOException("Failed to mkdirs directory ${dir.path}"))
        }
      }
      outputChannel.send(ProcrustesVolumeResponse(dir.toPath(), 200, ""))
    }
  }

  override suspend fun rmVolume(id: String) {
    val dir = File(tempDir, id)
    if (dir.exists()) {
      dir.deleteRecursively()
    }
  }
}

private val procrustesContext = newFixedThreadPoolContext(1, "ProcrustesThread")
private val EMPTY_PATH = Paths.get("")

class HttpProcrustes(private val procrustesRoot: Path,
                     private val procrustesAddress: String,
                     private val procrustesPassword: String) : Procrustes {
  override suspend fun makeVolume(req: ProcrustesVolumeRequest, outputChannel: Channel<ProcrustesVolumeResponse>) {
    val url = """http://$procrustesAddress/create"""
    GlobalScope.launch(procrustesContext) {
      val (_, resp, result) = Fuel.get(url, listOf(
          "password" to procrustesPassword,
          "namespace" to "tex",
          "name" to req.id,
          "paid" to if (req.sizeMb > 64) "true" else "false",
          "withReset" to req.makeClean.toString()
      )).timeout(10000).awaitStringResponseResult()
      result.fold({
        when (resp.statusCode) {
          HttpStatusCodes.STATUS_CODE_CREATED,
          HttpStatusCodes.STATUS_CODE_OK -> outputChannel.send(
              ProcrustesVolumeResponse(procrustesRoot.resolve(req.id), 200, "")
          )
          else -> outputChannel.send(
              ProcrustesVolumeResponse(EMPTY_PATH, resp.statusCode, resp.responseMessage)
          )
        }
      }, {
        val cause = it.exception
        LOG.error("Procrustes call failed", it)
        when (cause) {
          is SocketTimeoutException -> {
            Fuel.reset()
            outputChannel.send(ProcrustesVolumeResponse(EMPTY_PATH, 504, cause.message ?: ""))
          }
          is ConnectException -> {
            Fuel.reset()
            outputChannel.send(ProcrustesVolumeResponse(EMPTY_PATH, 502, cause.message ?: ""))
          }
          else -> {
            outputChannel.close(cause)
          }
        }
      })
    }
  }

  override suspend fun rmVolume(id: String) {
  }

}
private val LOG = LoggerFactory.getLogger("base.file")
