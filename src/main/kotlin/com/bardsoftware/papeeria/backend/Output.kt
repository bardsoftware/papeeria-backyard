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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseOutput {
  protected val stdout = PipedInputStream()
  protected val stderr = PipedInputStream()
  val stdoutPipe = PipedOutputStream(stdout)
  val stderrPipe = PipedOutputStream(stderr)

  abstract fun attach()
  open fun close() {}
  abstract fun isClosed(): Boolean
  abstract fun onClose(): Future<Unit>
}

open class JoinedOutput(private val out: PrintWriter) : BaseOutput() {
  private val counter = AtomicInteger(2)  // we want to close fileOut once both stdout and stderr are exhausted.
  private val onClose = CompletableFuture<Unit>()

  private fun copyStream(stream: PipedInputStream) {
    GlobalScope.launch(Dispatchers.IO) {
      Scanner(stream).use { scanner ->
        while (scanner.hasNextLine()) {
          scanner.nextLine().let {
            println(it)
            out.println(it)
          }
        }
      }
      if (counter.decrementAndGet() == 0) {
        close()
      }
    }
  }

  override fun attach() {
    copyStream(stdout)
    copyStream(stderr)
  }

  override fun close() {
    out.close()
    onClose.complete(null)
  }

  override fun isClosed(): Boolean {
    return this.counter.get() == 0
  }

  override fun onClose(): Future<Unit> {
    return this.onClose
  }
}

class ConsoleOutput : JoinedOutput(PrintWriter(System.out))
