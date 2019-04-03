package com.bardsoftware.papeeria.backend.demo

import com.bardsoftware.papeeria.backend.*
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.common.hash.Hashing
import com.google.protobuf.ByteString
import com.google.protobuf.util.JsonFormat
import com.google.pubsub.v1.PubsubMessage
import com.xenomachina.argparser.ArgParser
import io.grpc.stub.StreamObserver
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * @author dbarashev@bardsoftware.com
 */
class HashCalculatorPubSub(private val server: HashCalculatorServer) : MessageReceiver {
  private val responseObserver = object : StreamObserver<HashProto.HashResponse> {
    override fun onError(t: Throwable?) {
      TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onCompleted() {
    }

    override fun onNext(value: HashProto.HashResponse) {
      println("Hash is: ${value.hash}")
    }

  }
  override fun receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer) {
    //val request = HashProto.HashRequest.parseFrom(message.data)
    val builder = HashProto.HashRequest.newBuilder()
    JsonFormat.parser().merge(message.data.toStringUtf8(), builder)
    val request = builder.build()
    println(request)
    consumer.ack()
    server.calculate(request, responseObserver)
  }
}

class HashCalculatorServer(fileProcessingArgs: FileProcessingBackendArgs) : HashCalculatorGrpc.HashCalculatorImplBase() {

  private val fileBackend: FileProcessingBackend = FileProcessingBackend(fileProcessingArgs,
      PostgresContentStorage(fileProcessingArgs),
      PlainProcrustes())

  override fun calculate(request: HashProto.HashRequest, responseObserver: StreamObserver<HashProto.HashResponse>) {
    val path = fileBackend.process(request.taskId, request.fileRequest)
    val hash = Hashing.farmHashFingerprint64().newHasher()
    val visitor = object : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        val bytes = file.toFile().readBytes()
        hash.putBytes(bytes)
        return FileVisitResult.CONTINUE
      }
    }
    Files.walkFileTree(path, visitor)
    val response = HashProto.HashResponse.newBuilder().setHash(hash.hash().toString()).build()
    responseObserver.onNext(response)
  }
}

class Args(parser: ArgParser) {
  val fileArgs = FileProcessingBackendArgs(parser)
  val serverArgs = BaseServerArgs(parser)
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

  start(parsedArgs.serverArgs,
      service = BackendService(
          grpc = null,
          pubsub = HashCalculatorPubSub(HashCalculatorServer(parsedArgs.fileArgs))
      ),
      serverName = "HashCalculator"
  )
}

