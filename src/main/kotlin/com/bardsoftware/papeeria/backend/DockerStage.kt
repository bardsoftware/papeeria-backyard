package com.bardsoftware.papeeria.backend

import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.messages.ContainerConfig
import com.spotify.docker.client.messages.HostConfig
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

data class DockerTask(val workspaceRoot: Path, val dockerCmdArgs: List<String>)
/**
 * @author dbarashev@bardsoftware.com
 */
class DockerStage(val args: DockerStageArgs) {
  private val dockerContext = newSingleThreadContext("DockerThread")
  private val docker = getDefaultDockerClient()

  suspend fun process(task: DockerTask, outputChannel: Channel<Long>) {
    LOG.debug("Running docker task {}", task)

    val hostConfig = HostConfig.builder()
        .appendBinds("${task.workspaceRoot.toAbsolutePath()}:/workspace")
        .build()

    val containerConfig = ContainerConfig.builder()
        .image(args.dockerImage)
        .cmd(task.dockerCmdArgs)
        .hostConfig(hostConfig)
        .build()

    GlobalScope.launch(dockerContext) {
      var containerId: String? = null

      try {
        LOG.debug("Creating container: $containerConfig")
        val creation = this@DockerStage.docker.createContainer(containerConfig)
        containerId = creation.id()
        LOG.debug("Container id=$containerId")
        this@DockerStage.docker.startContainer(containerId)
        val exitCode = this@DockerStage.docker.waitContainer(containerId)
        LOG.debug("Exit code =$exitCode")
        outputChannel.send(exitCode.statusCode())
      } catch (e: Exception) {
        LOG.error("DockerStage failed", e)
      } finally {
        containerId?.let {
          this@DockerStage.docker.logs(it, DockerClient.LogsParam.stdout(), DockerClient.LogsParam.stderr()).use { logs ->
            println(logs.readFully())
          }
          this@DockerStage.docker.stopContainer(it, 0)
          this@DockerStage.docker.removeContainer(it)
        }
      }
    }
  }
}

class DockerStageArgs(parser: ArgParser) {
  val dockerImage by parser.storing("--docker-image", help = "Docker image to run").default { "" }
}

fun getDefaultDockerClient(): DefaultDockerClient {
  return DefaultDockerClient.fromEnv().build()
}

private val LOG = LoggerFactory.getLogger("base.docker")
