/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.worker

import java.io._

import scala.collection.JavaConversions._
import scala.collection.Map

import akka.actor.ActorRef
import com.google.common.base.Charsets.UTF_8
import com.google.common.io.Files
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileUtil, Path}

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.deploy.{Command, DriverDescription, SparkHadoopUtil}
import org.apache.spark.deploy.DeployMessages.DriverStateChanged
import org.apache.spark.deploy.master.DriverState
import org.apache.spark.deploy.master.DriverState.DriverState

/**
 * Manages the execution of one driver, including automatically restarting the driver on failure.
 * This is currently only used in standalone cluster deploy mode.
 */
/**
 * 管理一个driver的执行, 包括失败时自动重启这个driver.
 * 这个当前只能用在独立集群部署模式.
 */
private[spark] class DriverRunner(
    val conf: SparkConf,
    val driverId: String,
    val workDir: File,
    val sparkHome: File,
    val driverDesc: DriverDescription,
    val worker: ActorRef,
    val workerUrl: String)
  extends Logging {

  @volatile var process: Option[Process] = None
  @volatile var killed = false

  // Populated once finished
  // 一旦完成了就被终结(三种不同的完成状态)
  var finalState: Option[DriverState] = None
  var finalException: Option[Exception] = None
  var finalExitCode: Option[Int] = None

  // Decoupled for testing
  private[deploy] def setClock(_clock: Clock) = clock = _clock
  private[deploy] def setSleeper(_sleeper: Sleeper) = sleeper = _sleeper
  private var clock = new Clock {
    def currentTimeMillis(): Long = System.currentTimeMillis()
  }
  private var sleeper = new Sleeper {
    def sleep(seconds: Int): Unit = (0 until seconds).takeWhile(f => {Thread.sleep(1000); !killed})
  }

  /** Starts a thread to run and manage the driver. */
  /** 开启一个线程来运行和管理这个driver. */
  def start() = {
    new Thread("DriverRunner for " + driverId) {
      override def run() {
        try {
          val driverDir = createWorkingDirectory()
          val localJarFilename = downloadUserJar(driverDir)

          // Make sure user application jar is on the classpath
          // 确保用户应用jar在classpath上
          // TODO: If we add ability to submit multiple jars they should also be added here
          // TODO: 如果我们增加功能来提交多个jars，实现代码也应该写在这儿
          val builder = CommandUtils.buildProcessBuilder(driverDesc.command, driverDesc.mem,
            sparkHome.getAbsolutePath, substituteVariables, Seq(localJarFilename))
          launchDriver(builder, driverDir, driverDesc.supervise)
        }
        catch {
          case e: Exception => finalException = Some(e)
        }

        val state =
          if (killed) {
            DriverState.KILLED
          } else if (finalException.isDefined) {
            DriverState.ERROR
          } else {
            finalExitCode match {
              case Some(0) => DriverState.FINISHED
              case _ => DriverState.FAILED
            }
          }

        finalState = Some(state)

        worker ! DriverStateChanged(driverId, state, finalException)
      }
    }.start()
  }

  /** Terminate this driver (or prevent it from ever starting if not yet started) */
  /** 终结这个driver (或者阻止它尚未启动的启动过程 */
  def kill() {
    synchronized {
      process.foreach(p => p.destroy())
      killed = true
    }
  }

  /** Replace variables in a command argument passed to us */
  /** 在传递给我们的一个命令参数中替换变量 */
  private def substituteVariables(argument: String): String = argument match {
    case "{{WORKER_URL}}" => workerUrl
    case other => other
  }

  /**
   * Creates the working directory for this driver.
   * Will throw an exception if there are errors preparing the directory.
   */
  /**
   * 为了这个driver创建这个工作目录.
   * 如果在准备这个目录的时候出现错误那么会抛出一个异常.
   */
  private def createWorkingDirectory(): File = {
    val driverDir = new File(workDir, driverId)
    if (!driverDir.exists() && !driverDir.mkdirs()) {
      throw new IOException("Failed to create directory " + driverDir)
    }
    driverDir
  }

  /**
   * Download the user jar into the supplied directory and return its local path.
   * Will throw an exception if there are errors downloading the jar.
   */
  /**
   * 将这个用户jar下载进提供的目录并且返回它的本地路径.
   * 如果在下载这个jar得过程中出现错误将会抛出一个异常.
   */
  private def downloadUserJar(driverDir: File): String = {

    val jarPath = new Path(driverDesc.jarUrl)

    val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
    val jarFileSystem = jarPath.getFileSystem(hadoopConf)

    val destPath = new File(driverDir.getAbsolutePath, jarPath.getName)
    val jarFileName = jarPath.getName
    val localJarFile = new File(driverDir, jarFileName)
    val localJarFilename = localJarFile.getAbsolutePath

    if (!localJarFile.exists()) { // May already exist if running multiple workers on one node
      // 如果运行多个workers在一个节点可能已经存在了
      logInfo(s"Copying user jar $jarPath to $destPath")
      FileUtil.copy(jarFileSystem, jarPath, destPath, false, hadoopConf)
    }

    if (!localJarFile.exists()) { // Verify copy succeeded
      // 核实是否拷贝成功了
      throw new Exception(s"Did not see expected jar $jarFileName in $driverDir")
    }

    localJarFilename
  }

  private def launchDriver(builder: ProcessBuilder, baseDir: File, supervise: Boolean) {
    builder.directory(baseDir)
    def initialize(process: Process) = {
      // Redirect stdout and stderr to files
      // 重定向stdout和stderr到文件中
      val stdout = new File(baseDir, "stdout")
      CommandUtils.redirectStream(process.getInputStream, stdout)

      val stderr = new File(baseDir, "stderr")
      val header = "Launch Command: %s\n%s\n\n".format(
        builder.command.mkString("\"", "\" \"", "\""), "=" * 40)
      Files.append(header, stderr, UTF_8)
      CommandUtils.redirectStream(process.getErrorStream, stderr)
    }
    runCommandWithRetry(ProcessBuilderLike(builder), initialize, supervise)
  }

  private[deploy] def runCommandWithRetry(command: ProcessBuilderLike, initialize: Process => Unit,
    supervise: Boolean) {
    // Time to wait between submission retries.
    // 在提交重试之间等待的时间.
    var waitSeconds = 1
    // A run of this many seconds resets the exponential back-off.
    // 许多秒的运行时间来重置这个指数补偿
    val successfulRunDuration = 5

    var keepTrying = !killed

    while (keepTrying) {
      logInfo("Launch Command: " + command.command.mkString("\"", "\" \"", "\""))

      synchronized {
        if (killed) { return }
        process = Some(command.start())
        initialize(process.get)
      }

      val processStart = clock.currentTimeMillis()
      val exitCode = process.get.waitFor()
      if (clock.currentTimeMillis() - processStart > successfulRunDuration * 1000) {
        waitSeconds = 1
      }

      if (supervise && exitCode != 0 && !killed) {
        logInfo(s"Command exited with status $exitCode, re-launching after $waitSeconds s.")
        sleeper.sleep(waitSeconds)
        waitSeconds = waitSeconds * 2 // exponential back-off
        // 指数补偿
      }

      keepTrying = supervise && exitCode != 0 && !killed
      finalExitCode = Some(exitCode)
    }
  }
}

private[deploy] trait Clock {
  def currentTimeMillis(): Long
}

private[deploy] trait Sleeper {
  def sleep(seconds: Int)
}

// Needed because ProcessBuilder is a final class and cannot be mocked
// 这个是必需的因为ProcessBuilder是最终类并且不能被模拟
private[deploy] trait ProcessBuilderLike {
  def start(): Process
  def command: Seq[String]
}

private[deploy] object ProcessBuilderLike {
  def apply(processBuilder: ProcessBuilder) = new ProcessBuilderLike {
    def start() = processBuilder.start()
    def command = processBuilder.command()
  }
}
