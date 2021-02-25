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

package org.apache.spark.deploy.yarn

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.google.common.io.Files
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.server.MiniYARNCluster
import org.scalatest.{BeforeAndAfterAll, Matchers}
import org.scalatest.concurrent.Eventually._

import org.apache.spark._
import org.apache.spark.deploy.yarn.config._
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.launcher._
import org.apache.spark.util.Utils

abstract class BaseYarnClusterSuite
  extends SparkFunSuite with BeforeAndAfterAll with Matchers with Logging {

  // log4j configuration for the YARN containers, so that their output is collected
  // by YARN instead of trying to overwrite unit-tests.log.
  protected val LOG4J_CONF = """
    |log4j.rootCategory=DEBUG, console
    |log4j.appender.console=org.apache.log4j.ConsoleAppender
    |log4j.appender.console.target=System.err
    |log4j.appender.console.layout=org.apache.log4j.PatternLayout
    |log4j.appender.console.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
    |log4j.logger.org.apache.hadoop=WARN
    |log4j.logger.org.eclipse.jetty=WARN
    |log4j.logger.org.mortbay=WARN
    |log4j.logger.org.sparkproject.jetty=WARN
    """.stripMargin

  private var yarnCluster: MiniYARNCluster = _
  protected var tempDir: File = _
  private var fakeSparkJar: File = _
  protected var hadoopConfDir: File = _
  private var logConfDir: File = _

  def newYarnConfig(): YarnConfiguration

  override def beforeAll(): Unit = {
    super.beforeAll()

    tempDir = Utils.createTempDir()
    logConfDir = new File(tempDir, "log4j")
    logConfDir.mkdir()

    val logConfFile = new File(logConfDir, "log4j.properties")
    Files.write(LOG4J_CONF, logConfFile, StandardCharsets.UTF_8)

    // Disable the disk utilization check to avoid the test hanging when people's disks are
    // getting full.
    val yarnConf = newYarnConfig()
    yarnConf.set("yarn.nodemanager.disk-health-checker.max-disk-utilization-per-disk-percentage",
      "100.0")

    yarnCluster = new MiniYARNCluster(getClass().getName(), 1, 1, 1)
    yarnCluster.init(yarnConf)
    yarnCluster.start()

    // There's a race in MiniYARNCluster in which start() may return before the RM has updated
    // its address in the configuration. You can see this in the logs by noticing that when
    // MiniYARNCluster prints the address, it still has port "0" assigned, although later the
    // test works sometimes:
    //
    //    INFO MiniYARNCluster: MiniYARN ResourceManager address: blah:0
    //
    // That log message prints the contents of the RM_ADDRESS config variable. If you check it
    // later on, it looks something like this:
    //
    //    INFO YarnClusterSuite: RM address in configuration is blah:42631
    //
    // This hack loops for a bit waiting for the port to change, and fails the test if it hasn't
    // done so in a timely manner (defined to be 10 seconds).
    val config = yarnCluster.getConfig()
    val startTimeNs = System.nanoTime()
    while (config.get(YarnConfiguration.RM_ADDRESS).split(":")(1) == "0") {
      if (System.nanoTime() - startTimeNs > TimeUnit.SECONDS.toNanos(10)) {
        throw new IllegalStateException("Timed out waiting for RM to come up.")
      }
      logDebug("RM address still not set in configuration, waiting...")
      TimeUnit.MILLISECONDS.sleep(100)
    }

    logInfo(s"RM address in configuration is ${config.get(YarnConfiguration.RM_ADDRESS)}")

    fakeSparkJar = File.createTempFile("sparkJar", null, tempDir)
    hadoopConfDir = new File(tempDir, Client.LOCALIZED_CONF_DIR)
    assert(hadoopConfDir.mkdir())
    File.createTempFile("token", ".txt", hadoopConfDir)
  }

  override def afterAll(): Unit = {
    try {
      yarnCluster.stop()
    } finally {
      super.afterAll()
    }
  }

  protected def runSpark(
      clientMode: Boolean,
      klass: String,
      appArgs: Seq[String] = Nil,
      sparkArgs: Seq[(String, String)] = Nil,
      extraClassPath: Seq[String] = Nil,
      extraJars: Seq[String] = Nil,
      extraConf: Map[String, String] = Map(),
      extraEnv: Map[String, String] = Map(),
      outFile: Option[File] = None): SparkAppHandle.State = {
    val deployMode = if (clientMode) "client" else "cluster"
    val propsFile = createConfFile(extraClassPath = extraClassPath, extraConf = extraConf)
    val env = Map("YARN_CONF_DIR" -> hadoopConfDir.getAbsolutePath()) ++ extraEnv

    val launcher = new SparkLauncher(env.asJava)
    if (klass.endsWith(".py")) {
      launcher.setAppResource(klass)
    } else {
      launcher.setMainClass(klass)
      launcher.setAppResource(fakeSparkJar.getAbsolutePath())
    }
    launcher.setSparkHome(sys.props("spark.test.home"))
      .setMaster("yarn")
      .setDeployMode(deployMode)
      .setConf(EXECUTOR_INSTANCES.key, "1")
      .setPropertiesFile(propsFile)
      .addAppArgs(appArgs.toArray: _*)

    sparkArgs.foreach { case (name, value) =>
      if (value != null) {
        launcher.addSparkArg(name, value)
      } else {
        launcher.addSparkArg(name)
      }
    }
    extraJars.foreach(launcher.addJar)

    if (outFile.isDefined) {
      launcher.redirectOutput(outFile.get)
      launcher.redirectError()
    }

    val handle = launcher.startApplication()
    try {
      // TODO(palantir): Needs a larger timeout because we're testing Conda setup
      eventually(timeout(5.minutes), interval(1.second)) {
        assert(handle.getState().isFinal())
      }
    } finally {
      handle.kill()
    }

    handle.getState()
  }

  /**
   * This is a workaround for an issue with yarn-cluster mode: the Client class will not provide
   * any sort of error when the job process finishes successfully, but the job itself fails. So
   * the tests enforce that something is written to a file after everything is ok to indicate
   * that the job succeeded.
   */
  protected def checkResult(
      finalState: SparkAppHandle.State,
      result: File,
      expected: String = "success",
      outFile: Option[File] = None): Unit = {
    // the context message is passed to assert as Any instead of a function. to lazily load the
    // output from the file, this passes an anonymous object that loads it in toString when building
    // an error message
    val output = new Object() {
      override def toString: String = outFile
          .map(Files.toString(_, StandardCharsets.UTF_8))
          .getOrElse("(stdout/stderr was not captured)")
    }
    assert(finalState === SparkAppHandle.State.FINISHED, output)
    val resultString = Files.toString(result, StandardCharsets.UTF_8)
    assert(resultString === expected, output)
  }

  protected def mainClassName(klass: Class[_]): String = {
    klass.getName().stripSuffix("$")
  }

  protected def createConfFile(
      extraClassPath: Seq[String] = Nil,
      extraConf: Map[String, String] = Map()): String = {
    val props = new Properties()
    props.put(SPARK_JARS.key, "local:" + fakeSparkJar.getAbsolutePath())

    val testClasspath = new TestClasspathBuilder()
      .buildClassPath(
        logConfDir.getAbsolutePath() +
        File.pathSeparator +
        extraClassPath.mkString(File.pathSeparator))
      .asScala
      .mkString(File.pathSeparator)

    props.put("spark.driver.extraClassPath", testClasspath)
    props.put("spark.executor.extraClassPath", testClasspath)

    // SPARK-4267: make sure java options are propagated correctly.
    props.setProperty("spark.driver.extraJavaOptions", "-Dfoo=\"one two three\"")
    props.setProperty("spark.executor.extraJavaOptions", "-Dfoo=\"one two three\"")

    // SPARK-24446: make sure special characters in the library path do not break containers.
    if (!Utils.isWindows) {
      val libPath = """/tmp/does not exist:$PWD/tmp:/tmp/quote":/tmp/ampersand&"""
      props.setProperty(AM_LIBRARY_PATH.key, libPath)
      props.setProperty(DRIVER_LIBRARY_PATH.key, libPath)
      props.setProperty(EXECUTOR_LIBRARY_PATH.key, libPath)
    }

    yarnCluster.getConfig().asScala.foreach { e =>
      props.setProperty("spark.hadoop." + e.getKey(), e.getValue())
    }
    sys.props.foreach { case (k, v) =>
      if (k.startsWith("spark.")) {
        props.setProperty(k, v)
      }
    }
    extraConf.foreach { case (k, v) => props.setProperty(k, v) }

    val propsFile = File.createTempFile("spark", ".properties", tempDir)
    val writer = new OutputStreamWriter(new FileOutputStream(propsFile), StandardCharsets.UTF_8)
    props.store(writer, "Spark properties.")
    writer.close()
    propsFile.getAbsolutePath()
  }

}
