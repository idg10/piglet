
package dbis.pig.cep.spark

import org.apache.spark.SparkContext._
import org.apache.spark.streaming.dstream._
import scala.reflect.ClassTag
import dbis.pig.cep.ops.SelectionStrategy._
import dbis.pig.cep.ops.OutputStrategy._
import dbis.pig.cep.nfa.NFAController
import dbis.pig.backends.{SchemaClass => Event}

class CustomDStreamMatcher[T <: Event: ClassTag](dstream: DStream[T]) {

  def matchNFA(nfa: NFAController[T], sstr: SelectionStrategy = FirstMatch, out: OutputStrategy = Combined) = {
    println("create a new DStream matcher")
    new DStreamMatcher(dstream, nfa, sstr, out)
  }

}

object CustomDStreamMatcher {

  implicit def addDStreamMatcher[T <: Event: ClassTag](dstream: DStream[T]) = {
    println("add a custom DStream function")
    new CustomDStreamMatcher(dstream)
  }
}