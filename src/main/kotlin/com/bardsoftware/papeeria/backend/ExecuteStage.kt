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

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ExecuteTask(val workspaceRoot: Path, val cmdLine: List<String>)

class ExecuteStage {
  suspend fun process(
      task: ExecuteTask, exitCodeChannel: Channel<Long>,
      out: BaseOutput) {
    GlobalScope.launch(EXECUTE_CONTEXT) {
      LOG.info("Running execute task {}", task)
      try {
        val proc = ProcessBuilder(task.cmdLine)
            .directory(task.workspaceRoot.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        out.attach()
        val copyStdout = async {
          proc.inputStream.copyTo(out.stdoutPipe)
        }
        val copyStderr = async {
          proc.errorStream.copyTo(out.stderrPipe)
        }
        proc.waitFor(1, TimeUnit.MINUTES)
        val exitCode = proc.exitValue()
        copyStdout.await()
        copyStderr.await()

        LOG.debug("Exit code = $exitCode")
        out.stdoutPipe.close()
        out.stderrPipe.close()
        exitCodeChannel.send(exitCode.toLong())
      } catch (e: InterruptedException) {
        LOG.error("Process has been interrupted", e)
        exitCodeChannel.send(1)
      } catch (e: Exception) {
        LOG.error("ExecuteStage failed", e)
        exitCodeChannel.send(1)
      }
    }

  }
}

private val EXECUTE_CONTEXT = newSingleThreadContext("ExecuteThread")
private val LOG = LoggerFactory.getLogger("base.execute")

