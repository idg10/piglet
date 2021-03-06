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

import scala.Numeric.Implicits._
import scala.collection.mutable.ListBuffer

trait CommonPigFuncs {
  def average[T: Numeric](bag: Iterable[T]) : Double = {
    //sum(bag).toDouble / count(bag).toDouble
    val (sum, num) = bag.foldLeft((0.0, 0))((agg, newValue) => (agg._1 + newValue.toDouble(), agg._2 + 1))
    sum / num
  }

  def median[T: Numeric](bag: Iterable[T]): T = {
    val sorted = bag.toSeq.sorted
    val m = sorted.length / 2
    sorted(m)
  }

  def count(bag: Iterable[Any]): Int = bag.size

  def sum[T: Numeric](bag: Iterable[T]): T = bag.sum

  def min[T: Ordering](bag: Iterable[T]): T = bag.min

  def max[T: Ordering](bag: Iterable[T]): T = bag.max

  def isempty(bag: Iterable[Any]): Boolean = bag.isEmpty
  def nonempty(bag: Iterable[Any]): Boolean = bag.nonEmpty

  /*
   * String functions
   */
  def tokenize(s: String, delim: String = """[, "]""") = s.split(delim)
  
  def startswith(haystack: String, prefix: String) = haystack.startsWith(prefix)
  def endswith(haystack: String, suffix: String) = haystack.endsWith(suffix)

  def strNonEmpty(s: String) = s.nonEmpty
  def strIsEmpty(s: String) = s.isEmpty

  def strlen(s: String) = s.length()

  def uppercase(s: String) = s.toUpperCase
  def lowercase(s: String) = s.toLowerCase
  def concat(s1: String, s2: String) = s1 + s2
  def contains(s1: String, s2: String) = s1.contains(s2)
  def split(s: String, delim: String = ",") = s.split(delim)
  def trim(s: String) = s.trim
  def substring(s: String, start: Int): String = substring(s, start, s.length)
  def substring(s: String, start: Int, end: Int): String = s.substring(start, end)

  def toDouble(s: String) = s.toDouble
  def toInt(s: String) = s.toInt
  /*
   * Incremental versions of the aggregate functions - used for implementing ACCUMULATE.
   */
  def incrSUM(acc: Int, v: Int) = acc + v
  def incrSUM(acc: Double, v: Double) = acc + v
  def incrSUM(acc: Long, v: Long) = acc + v
  def incrCOUNT(acc: Int, v: Int) = acc + 1
  def incrCOUNT(acc: Long, v: Long) = acc + 1
  def incrCOUNT(acc: Double, v: Double) = acc + 1
  def incrMIN(acc: Int, v: Int) = math.min(acc, v)
  def incrMIN(acc: Long, v: Long) = math.min(acc, v)
  def incrMIN(acc: Double, v: Double) = math.min(acc, v)
  def incrMAX(acc: Int, v: Int) = math.max(acc, v)
  def incrMAX(acc: Long, v: Long) = math.max(acc, v)
  def incrMAX(acc: Double, v: Double) = math.max(acc, v)
}
