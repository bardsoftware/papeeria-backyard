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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter

/**
 * @author dbarashev@bardsoftware.com
 */
class ExecuteStageTest {
  @Test
  fun `command completes successfully`() {
    val tmpDir = createTempDir()
    val executeStage = ExecuteStage()
    val executeTask = ExecuteTask(tmpDir.toPath(), listOf("echo", "Hello World"))
    val channel = Channel<Long>()
    val outWriter = StringWriter()
    val out = JoinedOutput(PrintWriter(outWriter))
    GlobalScope.launch {
      executeStage.process(executeTask, channel, out)
    }
    runBlocking {
      assertEquals(0, channel.receive())
      // stdout and stderr are processed asynchronously, so we need to wait until they are done
      out.onClose().get()
      assertEquals("Hello World\n", outWriter.toString())
    }
  }

  @Test
  fun `command fails`() {
    val tmpDir = createTempDir()
    val executeStage = ExecuteStage()
    val executeTask = ExecuteTask(tmpDir.toPath(), listOf("false"))
    val channel = Channel<Long>()
    GlobalScope.launch {
      executeStage.process(executeTask, channel, ConsoleOutput())
    }
    runBlocking {
      assertNotEquals(0, channel.receive())
    }
  }

  @Test
  fun `read mixed output`() {
    val tmpDir = createTempDir()
    val executeStage = ExecuteStage()
    // This command prints one line to stdout and one line to stderr
    val executeTask = ExecuteTask(tmpDir.toPath(), listOf("bash", "-c", "echo 'Good World' && echo 'Bad World' 1>&2 && false"))
    val channel = Channel<Long>()
    val outWriter = StringWriter()
    val out = JoinedOutput(PrintWriter(outWriter))
    GlobalScope.launch {
      executeStage.process(executeTask, channel, out)
    }
    runBlocking {
      assertNotEquals(0, channel.receive())
      // stdout and stderr are processed asynchronously, so we need to wait until they are done
      out.onClose().get()
      // Moreover, their lines come not necessarily in the same order
      assertEquals(
          setOf("Good World", "Bad World"),
          outWriter.toString().split("\n").filter { it != "" }.map { it.trim() }.toSet()
      )
    }
  }
}
