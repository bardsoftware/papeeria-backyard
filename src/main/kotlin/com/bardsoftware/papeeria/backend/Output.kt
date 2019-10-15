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
import org.slf4j.Logger
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

typealias StreamReader = Pair<PipedInputStream, (String) -> Any>

abstract class BaseOutput protected constructor(
    private val stdoutReader: StreamReader? = null,
    private val stderrReader: StreamReader? = null) {
  private val streamCounter: AtomicInteger = AtomicInteger(0)
  protected val onClose = CompletableFuture<Unit>()
  val stdoutPipe: PipedOutputStream
  val stderrPipe: PipedOutputStream

  init {
    this.stdoutPipe = PipedOutputStream(stdoutReader?.let {
      this.streamCounter.incrementAndGet()
      it.first
    } ?: PipedInputStream())
    this.stderrPipe = PipedOutputStream(stderrReader?.let {
      this.streamCounter.incrementAndGet()
      it.first
    } ?: PipedInputStream())
  }

  fun attach() {
    this.stdoutReader?.let { (stream, out) -> copyStream(stream, out) }
    this.stderrReader?.let { (stream, out) -> copyStream(stream, out) }
  }

  open fun close() {}

  fun isClosed(): Boolean {
    return this.streamCounter.get() == 0
  }

  fun onClose(): Future<Unit> {
    return this.onClose
  }

  private fun copyStream(stream: PipedInputStream, linePrinter: (String) -> Any) {
    GlobalScope.launch(Dispatchers.IO) {
      Scanner(stream).use { scanner ->
        while (scanner.hasNextLine()) {
          scanner.nextLine().let(linePrinter)
        }
      }
      if (streamCounter.decrementAndGet() == 0) {
        close()
      }
    }
  }
}

open class JoinedOutput(private val out: PrintWriter) : BaseOutput(
    stdoutReader = PipedInputStream() to { it: String -> out.println(it) },
    stderrReader = PipedInputStream() to { it: String -> out.println(it) }) {

  override fun close() {
    out.close()
    onClose.complete(null)
  }
}

class ConsoleOutput : JoinedOutput(PrintWriter(System.out))

class LoggerOutput(logger: Logger) : BaseOutput(
    stdoutReader = PipedInputStream() to logger::info,
    stderrReader = PipedInputStream() to logger::warn)
