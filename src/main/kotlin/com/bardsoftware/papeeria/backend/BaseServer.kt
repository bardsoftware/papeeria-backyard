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

import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.ServiceOptions
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.Subscriber
import com.google.common.base.Preconditions
import com.google.common.collect.Queues
import com.google.protobuf.MessageLite
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.ProjectTopicName
import com.google.pubsub.v1.PubsubMessage
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.internal.GrpcUtil
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.*
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("base.server")

private val SHUTDOWN_EXECUTOR = Executors.newSingleThreadExecutor()
val DEFAULT_EXECUTOR = ThreadPoolExecutor(
    2, 10,
    60L, TimeUnit.SECONDS,
    Queues.newArrayBlockingQueue(400),
    GrpcUtil.getThreadFactory("grpc-thread-%d", true))

private val PROJECT_ID = ServiceOptions.getDefaultProjectId()

open class BaseServer(
    arg: BaseServerArgs,
    grpcPort: Int,
    service: BindableService? = null,
    val executor: ExecutorService = DEFAULT_EXECUTOR,
    sslCert: File? = null,
    sslKey: File? = null) {

  private val server: Server
  private val publisher: Publisher?
  private val publisherContext = newFixedThreadPoolContext(5, "PublisherThread")

  init {
    var builder = NettyServerBuilder.forPort(grpcPort)
    service?.let { builder.addService(it) }
    if (sslCert != null && sslKey != null) {
      Preconditions.checkState(sslCert.exists(), "SSL certificate file doesn't exists: %s", sslCert)
      Preconditions.checkState(sslKey.exists(), "SSL key file doesn't exists: %s", sslKey)
      builder = builder.useTransportSecurity(sslCert, sslKey)
    }
    builder = builder.executor(this.executor)
    this.server = builder.build()
    if (arg.pub?.isEmpty() == false) {
      val serviceTopicName = ProjectTopicName.of(PROJECT_ID, arg.pub)
      this.publisher = Publisher.newBuilder(serviceTopicName).build()
    } else {
      this.publisher = null
    }
  }

  fun start() {
    this.server.start()
    Runtime.getRuntime().addShutdownHook(object : Thread() {
      override fun run() {
        this@BaseServer.stop()
      }
    })
  }

  fun subscribe(subscription: String, receiver: MessageReceiver) {
    val subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, subscription)
    val subscriber = Subscriber.newBuilder(subscriptionName, receiver).build()
    subscriber.startAsync().awaitRunning()
  }

  fun publish(msg: MessageLite, requestId: String) {
    this.publisher?.let {pubsub ->
      val pubsubMessage = PubsubMessage.newBuilder()
          .setData(msg.toByteString())
          .putAttributes("requestId", requestId)
          .build()
      val future = pubsub.publish(pubsubMessage)
      ApiFutures.addCallback(future, object : ApiFutureCallback<String> {
        override fun onFailure(throwable: Throwable) {
          LOG.error("Failure when publishing response to request $requestId", throwable)
        }

        override fun onSuccess(messageId: String) {
          LOG.debug("Published $messageId in response to request $requestId")
        }
      }, this.executor)
    }

  }

  private fun stop() {
    this.executor.shutdown()
    this.server.shutdown()
  }

  fun blockUntilShutDown() {
    this.server.awaitTermination()
  }

  fun consumeBackendResponses(responseChannel: Channel<BackendServiceResponse>) {
    GlobalScope.launch(publisherContext) {
      responseChannel.consumeEach { resp ->
        publish(resp.message, resp.requestId)
      }
    }
  }
}


data class BackendServiceResponse(
    val message: MessageLite,
    val requestId: String
)

data class BackendService(
    val grpc: BindableService?,
    val pubsub: MessageReceiver?,
    var responseChannel: Channel<BackendServiceResponse>?
)

fun start(arg: BaseServerArgs, service: BackendService, serverName: String) = mainBody {

  val onShutdown = CompletableFuture<Any>()
  val baseServer = service.grpc?.let {
    val server =
        if (arg.certChain != null && arg.privateKey != null) {
          LOG.info("Starting $serverName in SECURE mode")
          BaseServer(
              arg = arg,
              grpcPort = arg.port,
              service = it,
              sslCert = File(arg.certChain),
              sslKey = File(arg.privateKey)
          )
        } else {
          LOG.info("Starting $serverName in INSECURE mode")
          BaseServer(arg = arg, grpcPort = arg.port, service = it)
        }
    LOG.info("Listening on port ${arg.port}")
    server.start()
    SHUTDOWN_EXECUTOR.submit {
      server.blockUntilShutDown()
      onShutdown.complete(null)
    }
    server
  }
  service.pubsub?.let {
    if (arg.sub == null) {
      System.err.println("Missing subscription name.")
      exitProcess(1)
    }
    LOG.info("Listening to PubSub subscription ${arg.sub!!}")
    val server = baseServer ?: BaseServer(arg = arg, grpcPort = arg.port).also {
      Runtime.getRuntime().addShutdownHook(Thread(Runnable {
        onShutdown.complete(null)
      }))
    }
    server.subscribe(arg.sub!!, it)
    service.responseChannel?.let { channel ->
      server.consumeBackendResponses(channel)
    }
  }
  onShutdown.get()
}

open class BaseServerArgs(parser: ArgParser) {
  val port: Int by parser.storing("--grpc-port",
      help = "port to listen on (default 9800)") { toInt() }.default { 9800 }
  val certChain: String? by parser.storing("--cert",
      help = "path to SSL cert").default { null }
  val privateKey: String? by parser.storing("--key",
      help = "path to SSL key").default { null }
  val sub: String? by parser.storing("--sub", help = "PubSub subscription to listen on").default { null }
  val pub: String? by parser.storing("--pub", help = "PubSub topic to publish to").default { null }
}
