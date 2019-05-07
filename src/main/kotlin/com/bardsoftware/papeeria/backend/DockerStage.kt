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

import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.messages.ContainerConfig
import com.spotify.docker.client.messages.HostConfig
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.math.BigDecimal
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Executors

private val NANO = 1000000000L

data class DockerTask(val workspaceRoot: Path, val dockerCmdArgs: List<String>)
/**
 * @author dbarashev@bardsoftware.com
 */
class DockerStage(val args: DockerStageArgs) {
  private val logsExecutor = Executors.newSingleThreadExecutor()
  private val dockerContext = newSingleThreadContext("DockerThread")
  private val docker = getDefaultDockerClient()

  suspend fun process(
      task: DockerTask, exitCodeChannel: Channel<Long>,
      out: BaseOutput,
      configCode: (hostConfig: HostConfig.Builder, containerConfig: ContainerConfig.Builder) -> Unit = { _, _ -> }) {
    LOG.debug("Running docker task {}", task)

    val hostConfigBuilder = HostConfig.builder()
        .appendBinds("${task.workspaceRoot.toAbsolutePath()}:/workspace")
        .nanoCpus(args.dockerCpu.toBigDecimal()
            .multiply(BigDecimal.valueOf(NANO))
            .toLong())
        .memory(args.dockerMem.toLong() * (1 shl 20))

    val containerConfigBuilder = ContainerConfig.builder()
        .image(args.dockerImage)
        .workingDir("/workspace")
        .cmd(task.dockerCmdArgs)
    configCode(hostConfigBuilder, containerConfigBuilder)
    val hostConfig = hostConfigBuilder.build()
    val containerConfig = containerConfigBuilder.hostConfig(hostConfig).build()

    GlobalScope.launch(dockerContext) {
      var containerId: String? = null

      try {
        LOG.debug("Creating container: $containerConfig")
        val creation = this@DockerStage.docker.createContainer(containerConfig)
        containerId = creation.id()
        LOG.debug("Container id=$containerId")
        attachOutput(containerId!!, out)
        this@DockerStage.docker.startContainer(containerId)
        val exitCode = this@DockerStage.docker.waitContainer(containerId)
        LOG.debug("Exit code =$exitCode")
        exitCodeChannel.send(exitCode.statusCode())
      } catch (e: Exception) {
        LOG.error("DockerStage failed", e)
      } finally {
        containerId?.let {
          this@DockerStage.docker.stopContainer(it, 0)
          this@DockerStage.docker.removeContainer(it)
        }
      }
    }
  }

  fun attachOutput(containerId: String, out: BaseOutput) {
    logsExecutor.submit {
      docker.attachContainer(containerId,
                   DockerClient.AttachParameter.LOGS, DockerClient.AttachParameter.STDOUT,
                   DockerClient.AttachParameter.STDERR, DockerClient.AttachParameter.STREAM)
                 .attach(out.stdoutPipe, out.stderrPipe)
    }
    out.attach()
  }
}

class DockerStageArgs(parser: ArgParser) {
  val dockerImage by parser.storing("--docker-image", help = "Docker image to run").default { "" }
  val dockerCpu by parser.storing("--docker-cpus", help = "Container CPU limit").default { "1.0" }
  val dockerMem by parser.storing("--docker-mem", help = "Container memory limit in megabytes").default { "64" }
}

abstract class BaseOutput {
  protected val stdout = PipedInputStream()
  protected val stderr = PipedInputStream()
  val stdoutPipe = PipedOutputStream(stdout)
  val stderrPipe = PipedOutputStream(stderr)

  abstract fun attach()
}

class ConsoleOutput : BaseOutput() {
  override fun attach() {
      // Print docker outputs and errors
      GlobalScope.launch(Dispatchers.IO) {
        Scanner(stdout).use { scanner ->
          while (scanner.hasNextLine()) {
            println(scanner.nextLine())
          }
        }
      }
      GlobalScope.launch(Dispatchers.IO) {
        Scanner(stderr).use { scanner ->
          while (scanner.hasNextLine()) {
            println(scanner.nextLine())
          }
        }
      }
  }
}

class FileOutput(val fileOut: PrintWriter) : BaseOutput() {
  override fun attach() {
    GlobalScope.launch(Dispatchers.IO) {
      Scanner(stdout).use { scanner ->
        while (scanner.hasNextLine()) {
          fileOut.println(scanner.nextLine())
        }
      }
    }
    GlobalScope.launch(Dispatchers.IO) {
      Scanner(stderr).use { scanner ->
        while (scanner.hasNextLine()) {
          fileOut.println(scanner.nextLine())
        }
      }
    }
  }

}

fun getDefaultDockerClient(): DefaultDockerClient {
  return DefaultDockerClient.fromEnv().build()
}

private val LOG = LoggerFactory.getLogger("base.docker")
