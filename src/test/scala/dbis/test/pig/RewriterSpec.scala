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
package dbis.test.pig

import dbis.pig.op._
import dbis.pig.plan.DataflowPlan
import dbis.pig.plan.rewriting.Rewriter._
import org.scalatest.{FlatSpec, Matchers}

class RewriterSpec extends FlatSpec with Matchers{
  "The rewriter" should "merge two Filter operations" in {
    val op1 = Load("a", "file.csv")
    val predicate1 = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))
    val predicate2 = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))
    val op2 = Filter("b", "a", predicate1)
    val op3 = Filter("c", "b", predicate2)
    val op4 = Dump("c")
    val opMerged = Filter("c", "a", And(predicate1, predicate2))

    val planUnmerged = new DataflowPlan(List(op1, op2, op3, op4))
    val planMerged = new DataflowPlan(List(op1, opMerged, op4))
    val sink = planUnmerged.sinkNodes.head
    val sinkMerged = planMerged.sinkNodes.head

    val rewrittenSink = processSink(sink)
    rewrittenSink should be (sinkMerged)
  }
}
