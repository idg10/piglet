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
package dbis.pig.plan.rewriting.internals

import com.twitter.util.Eval
import org.kiama.rewriting.Strategy

/** Provides methods for evaluating embedded code.
  *
  */
trait EmbedSupport {
  /** The imports that are automatically added to eval'd code
    *
    */
  private val imports = """
                          |import dbis.pig.op._
                          |import dbis.pig.plan.rewriting.Extractors._
                          |import dbis.pig.plan.rewriting.Rewriter._
                        """.stripMargin

  def addStrategy(s: Strategy): Unit

  /** Evals each String in ``ruleCode``
    */
  protected def evalExtraRuleCode(ruleCode: Seq[String]): Unit = {
    def addImports(code: String) = imports ++ code
    for (c <- ruleCode) {
      val evaldStrategies = (new Eval).apply[Seq[Strategy]](addImports(c))
      evaldStrategies.map(addStrategy)
    }
  }

}
