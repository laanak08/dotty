package dotty.tools
package dotc
package interactive

import java.util.function.Consumer

import java.io.{ File => JFile, InputStream, OutputStream, PrintWriter }
import java.net._
import java.nio.channels._

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j.launch._

object Main {
  def main(args: Array[String]): Unit = {
    args.toList match {
      case List("-stdio") =>
        startServer(System.in, System.out)
      case "-client_command" :: clientCommand =>
        val serverSocket = new ServerSocket(0)
        Runtime.getRuntime().addShutdownHook(new Thread(
          new Runnable {
            def run: Unit = {
              serverSocket.close()
            }
          }));

        Console.err.println("Starting client: " + clientCommand)
        val clientPB = new java.lang.ProcessBuilder(clientCommand: _*)
        clientPB.environment.put("DLS_DEV_MODE", "1")
        
        val pw = new PrintWriter("../.dotty-ide-dev-port")
        pw.write(serverSocket.getLocalPort.toString)
        pw.close()

        clientPB.inheritIO().start()

        val clientSocket = serverSocket.accept()

        startServer(clientSocket.getInputStream, clientSocket.getOutputStream)
      case _ =>
        Console.err.println("Invalid arguments: expected \"-stdio\" or \"-port NNNN\"")
        System.exit(1)
    }
  }

  def startServer(in: InputStream, out: OutputStream) = {
    val server = new ScalaLanguageServer

    System.setOut(System.err)
    scala.Console.withOut(scala.Console.err) {
      println("Starting server")
      // val launcher = LSPLauncher.createServerLauncher(server, in, out, false, new java.io.PrintWriter(System.err, true))
      val launcher = LSPLauncher.createServerLauncher(server, in, out)
      val client = launcher.getRemoteProxy()
      server.connect(client)
      launcher.startListening()
    }
  }
}