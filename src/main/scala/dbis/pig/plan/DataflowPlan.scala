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
package dbis.pig.plan

import dbis.pig.op._
import dbis.pig.schema.SchemaException

import scala.collection.mutable.{ListBuffer, Map}
import scalax.collection.GraphEdge.DiEdge
import scalax.collection.mutable.Graph
import scalax.collection.GraphPredef._

/**
 * Created by kai on 01.04.15.
 */
case class Pipe (name: String, producer: PigOperator)

case class InvalidPlanException(msg: String) extends Exception(msg)

class DataflowPlan(var operators: List[PigOperator]) {
  // private var graph = Graph[PigOperator,DiEdge]()
  val additionalJars = ListBuffer[String]()

  constructPlan(operators)

  def constructPlan(ops: List[PigOperator]) : Unit = {
    def unquote(s: String): String = s.substring(1, s.length - 1)

    var pipes: Map[String, Pipe] = Map[String, Pipe]()

    /*
     * 0. we remove all Register operators: they are just pseudo-operators.
     *    Instead, we add their arguments to the additionalJars list
     */
    ops.filter(_.isInstanceOf[Register]).foreach(op => additionalJars += unquote(op.asInstanceOf[Register].jarFile))
    val planOps = ops.filterNot(_.isInstanceOf[Register])

    /*
     * 1. we create pipes for all outPipeNames and make sure they are unique
     */
    planOps.foreach(op => {
      if (op.initialOutPipeName != "") {
        if (pipes.contains(op.initialOutPipeName))
          throw new InvalidPlanException("duplicate pipe: " + op.initialOutPipeName)
        pipes(op.initialOutPipeName) = Pipe(op.initialOutPipeName, op)
      }
    })
    /*
     * 2. we assign the pipe objects to the input and output pipes of all operators
     *    based on their names
     */
    try {
      planOps.foreach(op => {
        // println("op: " + op)
        op.output = if (op.initialOutPipeName != "") pipes.get(op.initialOutPipeName) else None
        op.inputs = op.initialInPipeNames.map(p => pipes(p))
        op.preparePlan
        op.constructSchema
      })
    }
    catch {
      case e: java.util.NoSuchElementException => throw new InvalidPlanException("invalid pipe: " + e.getMessage)
    }
    operators = planOps
  }

  def sinkNodes: Set[PigOperator] = operators.filter((n: PigOperator) => n.output.isEmpty).toSet[PigOperator]
  
  def sourceNodes: Set[PigOperator] = operators.filter((n: PigOperator) => n.inputs.isEmpty).toSet[PigOperator]

  def checkConnectivity: Boolean = {
    // TODO: check connectivity of subplans in nested foreach
    // we simply construct a graph and check its connectivity
    var graph = Graph[PigOperator,DiEdge]()
    operators.foreach(op => op.inputs.foreach(p => graph += p.producer ~> op))
    graph.isConnected
  }

  def checkSchemaConformance: Unit = {
    val errors = operators.view.map{ op => (op, op.checkSchemaConformance) }
                    .filter{ t => t._2 == false }

    if(!errors.isEmpty) {
      val str = errors.map(_._1).mkString(" and ")
      throw SchemaException(str)
    }
    
//    operators.map(_.checkSchemaConformance).foldLeft(true){ (b1: Boolean, b2: Boolean) => b1 && b2 }
  }

  /**
   * Returns the operator that produces the relation with the given alias.
   *
   * @param s the alias name of the output relation
   * @return the operator producing this relation
   */
  def findOperatorForAlias(s: String): Option[PigOperator] = operators.find(o => o.outPipeName == s)

  def findOperator(pred: PigOperator => Boolean) : List[PigOperator] = operators.filter(n => pred(n))

  /**
   * Swaps the two operators in the dataflow plan. Both operators are unary operators and have to be already
   * part of the plan.
   *
   * @param n1 the first operator
   * @param n2 the second operator
   * @return the resulting dataflow plan
   */
  def swap(n1: PigOperator, n2: PigOperator) : DataflowPlan = {
    this
  }

  /**
   * Inserts the operator op after the given operator old in the dataflow plan. old has to be already part of the plan.
   *
   * @param old the operator after we insert
   * @param op the new operator to be inserted after old
   * @return the resulting dataflow plan
   */
  def insertAfter(old: PigOperator, op: PigOperator) : DataflowPlan =  {
    this
  }

  /**
   * Remove the given operator from the dataflow plan.
   *
   * @param n the operator to be removed from the plan
   * @return the resulting dataflow plan
   */
  def remove(n: PigOperator) : DataflowPlan = {
    this
  }

  /**
   * Replace the operator old by the new operator repl in the current dataflow plan.
   *
   * @param old the operator which has to be replaced
   * @param repl the new operator
   * @return the resulting dataflow plan
   */
  def replace(old: PigOperator, repl: PigOperator) : DataflowPlan =  {
    this
  }

}
