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

import com.bardsoftware.papeeria.backend.BackendService
import com.bardsoftware.papeeria.backend.BaseServerArgs
import com.bardsoftware.papeeria.backend.start
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.pubsub.v1.PubsubMessage
import com.xenomachina.argparser.ArgParser
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.Channel

class EchoServer(val prefix: String) : EchoGrpc.EchoImplBase() {
  override fun send(request: EchoProto.Ping, responseObserver: StreamObserver<EchoProto.Pong>) {
    println(request)
    responseObserver.onNext(EchoProto.Pong.newBuilder().setMessage(prefix + request.message).build())
  }
}

class EchoMessageReceiver : MessageReceiver {
  override fun receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer) {
    val request = EchoProto.Ping.parseFrom(message.data)
    println(request)
    consumer.ack()
  }
}

class EchoArgs(parser: ArgParser) : BaseServerArgs(parser) {
  val pongPrefix by parser.storing("--prefix", help="")
}

fun main(args: Array<String>) {
  val argParser = ArgParser(args)
  val echoArgs = EchoArgs(argParser)
  start(echoArgs,
      service = BackendService(
          grpc = EchoServer(echoArgs.pongPrefix),
          pubsub = EchoMessageReceiver(),
          responseChannel = Channel(0)
      ),
      serverName = "Echo"
  )
}
