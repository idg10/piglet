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
package dbis.piglet.op

import java.net.URI

import dbis.piglet.Piglet.Lineage
import dbis.piglet.expr.{Ref, Value}
import dbis.piglet.schema.Schema
import dbis.piglet.tools.{CliParams, HDFSService}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
  * Load represents the LOAD operator of Pig.
  *
  * @param out the output pipe (relation).
  * @param file the name of the file to be loaded
  * @param loadSchema The schema of the underlying file content
  * @param loaderFunc The name of the loader function to use (PigStorage, ...)
  * @param loaderParams Parameters (delimiter, ...)
  */
case class Load(
    private val out: Pipe, 
    var file: String,
    private var loadSchema: Option[Schema] = None,
    loaderFunc: Option[String] = None,
    loaderParams: List[String] = null,
    linStr: Option[Lineage] = None) extends PigOperator(List(out), List(), loadSchema) {


  private lazy val lastModified: Option[Try[Long]] = CliParams.values.profiling.map(_ => Try(HDFSService.lastModified(file.toString)))

//  override def constructSchema: Option[Schema] = schema

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = linStr getOrElse {
    s"""LOAD%$file%${lastModified match {
      case None => -1
      case Some(Failure(_)) => -2
      case Some(Success(v)) => v
    }}%""" + super.lineageString
  }

  override def toString: String =
    s"""LOAD
       |  out = ${outputs.map(_.name).mkString(",")}
       |  file = ${file.toString}
       |  func = $loaderFunc
       |  outSchema = $schema""".stripMargin


  override def resolveReferences(mapping: mutable.Map[String, Ref]): Unit = {
    // we replace only the filename
    if (file.toString.startsWith("$") && mapping.contains(file.toString)) {
      mapping(file.toString) match {
        case Value(v) =>
          val s = v.toString
          if (s(0) == '"')
            file = s.substring(1, s.length-1)
//            file = new URI(s.substring(1, s.length-1))
        case _ =>
      }
    }
  }

}

