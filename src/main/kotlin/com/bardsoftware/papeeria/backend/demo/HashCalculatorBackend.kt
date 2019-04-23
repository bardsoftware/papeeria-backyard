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
package com.bardsoftware.papeeria.backend.demo

import com.bardsoftware.papeeria.backend.*
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.util.JsonFormat
import com.google.pubsub.v1.PubsubMessage
import com.xenomachina.argparser.ArgParser
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * @author dbarashev@bardsoftware.com
 */
class HashCalculatorPubSub(private val server: HashCalculatorServer, responseChannel: Channel<BackendServiceResponse>) : MessageReceiver {
  private val responseObserver = object : StreamObserver<HashProto.HashResponse> {
    override fun onError(t: Throwable?) {
      println("Error: ")
      t?.printStackTrace()
    }

    override fun onCompleted() {
    }

    override fun onNext(value: HashProto.HashResponse) {
      runBlocking {
        responseChannel.send(BackendServiceResponse(value, value.requestId))
      }
    }

  }
  override fun receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer) {
    val builder = HashProto.HashRequest.newBuilder().setRequestId(message.messageId)
    try {
      builder.mergeFrom(message.data)
      consumer.ack()
      val request = builder.build()
      server.calculate(request, responseObserver)
      return
    } catch (ex: InvalidProtocolBufferException) {
      // That's fine, let's try JSON now
    }
    try {
      JsonFormat.parser().merge(message.data.toStringUtf8(), builder)
      consumer.ack()
      val request = builder.build()
      server.calculate(request, responseObserver)
    } catch (ex: InvalidProtocolBufferException) {
      println("Failed to parse message ${message.messageId}, tried both binary and json formats")
    }
  }
}

class HashCalculatorServer(fileStageArgs: FileStageArgs,
                           dockerStageArgs: DockerStageArgs)
  : HashCalculatorGrpc.HashCalculatorImplBase() {

  private val fileStage: FileStage = FileStage(fileStageArgs,
      PostgresContentStorage(fileStageArgs),
      PlainProcrustes())
  private val dockerStage = DockerStage(dockerStageArgs)

  override fun calculate(request: HashProto.HashRequest,
                         responseObserver: StreamObserver<HashProto.HashResponse>) {
    val fetchChannel = Channel<Path>()
    GlobalScope.launch {
      fileStage.process(request.taskId, request.fileRequest, fetchChannel)
    }

    GlobalScope.launch {
      val dockerChannel = Channel<Long>()
      val path = fetchChannel.receive()
      dockerStage.process(
          DockerTask(path, listOf("bash", "-c", "find /workspace -type f -exec md5sum {} \\; | sort -k 2 | md5sum > /workspace/${request.taskId}.stdout")),
          dockerChannel)
      val exitCode = dockerChannel.receive()
      if (exitCode == 0L) {
        val stdout = path.resolve("${request.taskId}.stdout").toFile().readText()
        val response = HashProto.HashResponse.newBuilder()
            .setRequestId(request.requestId)
            .setHash(stdout)
            .build()
        responseObserver.onNext(response)
      } else {
        responseObserver.onError(Exception("Docker failed with exit code $exitCode"))
      }
    }
  }
}

class Args(parser: ArgParser) {
  val fileArgs = FileStageArgs(parser)
  val serverArgs = BaseServerArgs(parser)
  val dockerArgs = DockerStageArgs(parser)
}

fun main(args: Array<String>) {
  val parsedArgs = Args(ArgParser(args))

  val demoReq = HashProto.HashRequest.newBuilder().setTaskId("foo").setFileRequest(
      FileProcessingBackendProto.FileRequestDto.newBuilder()
          .addFile(
              FileProcessingBackendProto.FileDto.newBuilder()
                .setId("file1")
                .setName("file1")
                .setContents(ByteString.copyFrom("Lorem ipsum dolor sit amet".toByteArray()))
                .build()
          ).addFile(
              FileProcessingBackendProto.FileDto.newBuilder()
                .setId("file2")
                .setName("file2")
                .setContents(ByteString.copyFrom("Liberte Egalite Fraternite".toByteArray()))
                .build()
          )
          .build()
  )
  println("Try sending me this message:")
  println(JsonFormat.printer().print(demoReq))

  val responseChannel = Channel<BackendServiceResponse>()
  start(parsedArgs.serverArgs,
      service = BackendService(
          grpc = null,
          pubsub = HashCalculatorPubSub(
              HashCalculatorServer(parsedArgs.fileArgs, parsedArgs.dockerArgs),
              responseChannel
          ),
          responseChannel = responseChannel
      ),
      serverName = "HashCalculator"
  )
}

