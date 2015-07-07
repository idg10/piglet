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

package dbis.pig.op

import dbis.pig.schema.Schema

/**
 *
 * @param initialOutPipeName the name of the initial output pipe (relation) which is needed to construct the plan, but
 *                           can be changed later.
 * @param initialInPipeName
 * @param opName
 * @param params
 * @param loadSchema
 */
case class StreamOp(override val initialOutPipeName: String, initialInPipeName: String, opName: String, params: Option[List[Ref]] = None,
                    var loadSchema: Option[Schema] = None)
  extends PigOperator(initialOutPipeName, List(initialInPipeName), loadSchema) {
  override def lineageString: String = s"""STREAM%""" + super.lineageString

  override def checkSchemaConformance: Boolean = {
    // TODO
    true
  }

  override def constructSchema: Option[Schema] = {
    // TODO
    schema
  }
}
