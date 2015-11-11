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
package dbis.test.flink

import dbis.pig.Piglet
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.prop.TableDrivenPropertyChecks._

import scalax.file.Path
import scala.io.Source

class FlinksCompileIt extends FlatSpec with Matchers {
  val scripts = Table(
    ("script",            "result",        "truth",               "inOrder"), // header of the table
    ("load.pig",          "result1.out",   "truth/result1.data",   false),
    ("load2.pig",         "result2.out",   "truth/result2.data",   false),
    ("foreach1.pig",      "distances.out", "truth/distances.data", false),
    ("filter.pig",        "filtered.out",  "truth/filtered.data",  false),
    ("construct.pig",     "result3.out", "truth/construct.data",   false),
    ("union.pig",         "united.out",    "truth/united.data",    false),
    ("aggregate.pig",     "aggregate.out", "truth/aggregate2.data",false),
    ("splitInto.pig",     "splitX.out",    "truth/splitX.data",    false),
    ("windowJoin.pig",    "joinedW.out",   "truth/joined.data",    false),
    ("windowCross.pig",   "crossedW.out",  "truth/crossed.data",   false),
    ("windowGrouping.pig","grouping.out",  "truth/grouping.data",  false),
    ("windowNforeach.pig","nested.out",    "truth/nested.data",    false),
    ("windowFilter.pig",  "filtered.out",  "truth/filtered.data",  false),
    ("windowCount.pig",   "marycounts.out","truth/marycount.data", false),
    ("windowDistinct.pig","unique.out",    "truth/unique.data",    false),
    ("windowSort.pig",    "sorted.out",    "truth/sorted.data",    true)
    //TODO: Sample, Split, Stream-Through, Hybrid-Operators
  )

  def cleanupResult(dir: String): Unit = {

    val path: Path = Path.fromString(dir)
    try {
      path.deleteRecursively(continueOnFailure = false)
    }
    catch {
      case e: java.io.IOException => // some file could not be deleted
    }

  }

  "The Pig compiler" should "compile and execute the script" in {
    forAll(scripts) { (script: String, resultDir: String, truthFile: String, inOrder: Boolean) =>
      val resultPath = Path.fromString(new java.io.File(".").getCanonicalPath)./(resultDir)
      // 1. make sure the output directory is empty
      cleanupResult(resultPath.path)
      cleanupResult(script.replace(".pig",""))

      // 2. compile and execute Pig script
      val resourcePath = getClass.getResource("").getPath + "../../../"
      println("execute: " + script)
      //println("resultPath: " + resultPath.path)
      Piglet.main(Array("--backend", "flinks", "--params", s"inbase=$resourcePath,outfile=${resultPath.path}", "--outdir", resultPath.parent.get.path, resourcePath + "streaming/"+ script))

      // 3. load the output file[s] and the truth file
      var result = Iterator[String]()
      val resultFile = new java.io.File(resultPath.path)
      if(resultFile.isFile)
        result ++= Source.fromFile(resultFile).getLines
      else 
        for (file <- resultFile.listFiles) 
          result++=Source.fromFile(file).getLines
      val truth = Source.fromFile(resourcePath + truthFile).getLines
      // 4. compare both files
      if (inOrder)
        result.toSeq should contain theSameElementsInOrderAs (truth.toTraversable)
      else
        result.toSeq should contain theSameElementsAs (truth.toTraversable)

      // 5. delete the output directory
      cleanupResult(resultPath.path)
      cleanupResult(script.replace(".pig",""))
    }
  }
}
