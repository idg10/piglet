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

package dbis.piglet

import scala.io.Source
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.{Map => MutableMap}
import scala.collection.JavaConverters._

import scopt.OptionParser

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.net.URI

import dbis.piglet.backends.BackendManager
import dbis.piglet.backends.BackendConf
import dbis.piglet.codegen.PigletCompiler
import dbis.piglet.mm.{DataflowProfiler, MaterializationPoint}
import dbis.piglet.op.PigOperator
import dbis.piglet.parser.PigParser
import dbis.piglet.plan.DataflowPlan
import dbis.piglet.plan.InvalidPlanException
import dbis.piglet.plan.MaterializationManager
import dbis.piglet.plan.PlanMerger
import dbis.piglet.plan.rewriting.Rewriter._
import dbis.piglet.plan.rewriting.Rules
import dbis.piglet.schema.SchemaException
import dbis.piglet.tools.FileTools
import dbis.piglet.tools.Conf
import dbis.piglet.tools.{DepthFirstTopDownWalker, BreadthFirstTopDownWalker}
import dbis.piglet.tools.logging.PigletLogging
import dbis.piglet.tools.logging.LogLevel
import dbis.piglet.tools.logging.LogLevel._
import dbis.piglet.tools.CliParams

import dbis.setm.SETM
import dbis.setm.SETM.timing


object Piglet extends PigletLogging {


  def main(args: Array[String]): Unit = {

    // Parse CLI parameters
    val c = CliParams.parse(args)

    
    // start statistics collector SETM if needed
    startCollectStats(c.showStats)

    // set the log level as defined in the parameters
		logger.setLevel(c.logLevel)


    /* Copy config file to the user's home directory
     * IMPORTANT: This must be the first call to Conf
     * Otherwise, the config file was already loaded before we could copy the new one
     */
    if (c.updateConfig) {
      // in case of --update we just copy the config file and exit
      Conf.copyConfigFile()
      println(s"Config file copied to ${Conf.programHome} - exitting now")
      sys.exit()
    }

    // set default backend if necessary now - we had to "wait" until after the update conf call
//    val tb = c.backend.getOrElse(Conf.defaultBackend)
    

    // get the input files
    val files = c.inputFiles.takeWhile { p => !p.getFileName.startsWith("-") }
    if (files.isEmpty) {
      // because the file argument was optional we have to check it here
      println("Error: Missing argument <file>...\nTry --help for more information.")
      sys.exit(-1)
    }

    val paramMap = MutableMap.empty[String, String]

    /*
     * If the parameter file is given, read each line, split it by = and add
     * the mapping to the parameters list
     */
    if(c.paramFile.isDefined) {
      val s = Files.readAllLines(c.paramFile.get).asScala
          .map { line => line.split("=", 2) } // 2 tells split to apply the regex 1 time (n-1) - the result array will be of size 2 (n)
          .map { arr => (arr(0) -> arr(1) )}

      paramMap ++= s
    }

    /* add the parameters supplied via CLI to the paramMap after we read the file
     * this way, we can override the values in the file via CLI
     */
    paramMap ++= c.params

    if(paramMap.nonEmpty)
    	logger.info(s"provided parameters: ${paramMap.map{ case (k,v) => s"$k -> $v"}.mkString("\n")}")


    /* if profiling is enabled, check the HTTP server
     * If it's unavailable print an error and stop
     */
    if(c.profiling.isDefined) {
      val reachable = FileTools.checkHttpServer(c.profiling.get)

      if(! reachable) {
        logger.error(s"Statistics management server is not reachable at ${c.profiling.get}. Aborting")
        return
      }
    }

    //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    run(c)  // this little call starts the whole processing!
    //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    
    // at the end, show the statistics
    if(c.showStats) {
      // collect and print runtime stats
      collectStats
    }

  } // main


  /**
    * Start compiling the Pig script into a the desired program
    */
  def run(c: CliParams): Unit = {

    try {

      // initialize backend
      BackendManager.init(c.backend)

      if (BackendManager.backend.raw) {
        if (c.compileOnly) {
          logger.error("Raw backends do not support compile-only mode! Aborting")
          return
        }

        if (c.profiling.isDefined) {
          logger.error("Raw backends do not support profiling yet! Aborting")
          return
        }

        // process each file separately - one after the other
        c.inputFiles.foreach { file => runRaw(file, c.master, c.backendArgs) }

      } else {
        // no raw backend, generate code and submit job
        runWithCodeGeneration(c)
      }

    } catch {
      // don't print full stack trace to error
      case e: Exception =>
        logger.error(s"An error occured: ${e.getMessage}")
        logger.debug(e.getMessage, e)
    }
  }

  /**
    *
    * @param file
    * @param master
    * @param backendConf
    * @param backendArgs
    */
  def runRaw(file: Path, master: String, backendArgs: Map[String, String]) = timing("execute raw") {
    logger.debug(s"executing in raw mode: $file with master $master for backend ${BackendManager.backend.name} with arguments ${backendArgs.mkString(" ")}")
    val runner = BackendManager.backend.runnerClass
    runner.executeRaw(file, master, backendArgs)
  }


  /**
   * Run with the provided set of files using the specified backend, that is _not_ a raw backend.
   *
   * @param c Parameter settings / configuration
   * 
   */
  def runWithCodeGeneration(c: CliParams): Unit = timing("run with generation") {


    logger.debug("start parsing input files")

    var schedule = ListBuffer.empty[(DataflowPlan,Path)]

    for(file <- c.inputFiles) {
      // foreach file, generate the data flow plan and store it in our schedule
      PigletCompiler.createDataflowPlan(file, c.params, c.backend) match {
        case Some(v) => schedule += ((v, file))
        case None => // in case of an error (no plan genrated for file) abort current execution
          throw InvalidPlanException(s"failed to create dataflow plan for $file - aborting")
      }
    }


    /*
     * if we have got more than one plan and we should not execute them
     * sequentially, then try to merge them into one plan
     */
    if(schedule.size > 1 && !c.sequential) {
      logger.debug("Start merging plans")

      // merge plans into one plan
      val mergedPlan = PlanMerger.mergePlans( schedule.map{case (plan, _) => plan } )

      // adjust the new schedule. It now contains only the merged plan, with a new generated file name
      schedule = ListBuffer((mergedPlan, Paths.get(s"merged_${System.currentTimeMillis()}.pig")))
    }

    val templateFile = BackendManager.backend.templateFile

		val profiler = c.profiling.map { u => new DataflowProfiler(Some(u)) }


		// begin global analysis phase

		// count occurrences of each operator in schedule
    profiler.foreach { p => p.createOpCounter(schedule) }

    logger.debug("start processing created dataflow plans")

    for ((plan, path) <- schedule) timing("execute plan") {

      // 3. now, we should apply optimizations
      var newPlan = plan

      // process explicit MATERIALIZE operators
      if (c.profiling.isDefined) {
        val mm = new MaterializationManager(Conf.materializationBaseDir, c.profiling.get)
        newPlan = processMaterializations(newPlan, mm)
      }


      // rewrite WINDOW operators for Flink streaming
      if (c.backend == "flinks")
        newPlan = processWindows(newPlan)

      Rules.registerBackendRules(c.backend)
      newPlan = processPlan(newPlan)

      // find materialization points
      profiler.foreach { p => p.addMaterializationPoints(newPlan) }

      logger.debug("finished optimizations")

      if (c.showPlan) {
        println("final plan = {")
        newPlan.printPlan()
        println("}")
      }

      try {
        // if this does _not_ throw an exception, the schema is ok
        // TODO: we should do this AFTER rewriting!
        newPlan.checkSchemaConformance
      } catch {
        case e: SchemaException => {
          logger.error(s"schema conformance error in ${e.getMessage} for plan")
          return
        }
      }

      val scriptName = path.getFileName.toString().replace(".pig", "")
      logger.debug(s"using script name: $scriptName")


      PigletCompiler.compilePlan(newPlan, scriptName, templateFile, c) match {
        // the file was created --> execute it
        case Some(jarFile) =>
          if (!c.compileOnly) {
            // 4. and finally deploy/submit
            val runner = BackendManager.backend.runnerClass
            logger.debug(s"using runner class ${runner.getClass.toString()}")

            logger.info( s"""starting job at "$jarFile" using backend "${c.backend}" """)
            timing("job execution") {
              runner.execute(c.master, scriptName, jarFile, c.backendArgs)
            }
          } else
            logger.info("successfully compiled program - exiting.")

        case None => logger.error(s"creating jar file failed for ${path}")
      }
    }
  }

  def startCollectStats(enable: Boolean) = if(enable) SETM.enable else SETM.disable
  def collectStats = SETM.collect()

}
