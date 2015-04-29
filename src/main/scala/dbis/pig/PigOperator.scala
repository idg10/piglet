package dbis.pig

/**
 * Created by kai on 31.03.15.
 */

import java.security.MessageDigest

/**
 * PigOperator is the base class for all Pig operators. An operator contains
 * pipes representing the input and output connections to other operators in the
 * dataflow.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeNames the list of names of input pipes.
 */
sealed abstract class PigOperator (val outPipeName: String, val inPipeNames: List[String], var schema: Option[Schema]) {
  var inputs: List[Pipe] = List[Pipe]()
  var output: Option[Pipe] = None

  def this(out: String, in: List[String]) = this(out, in, None)

  def this(out: String) = this(out, List(), None)

  def this(out: String, in: String) = this(out, List(in), None)

  /**
   * Constructs the output schema of this operator based on the input + the semantics of the operator.
   * The default implementation is to simply take over the schema of the input operator.
   *
   * @return the output schema
   */
  def constructSchema: Option[Schema] = {
    if (inputs.nonEmpty)
      schema = inputs.head.producer.schema
    schema
  }

  /**
   * Returns a string representation of the output schema of the operator.
   *
   * @return a string describing the schema
   */
  def schemaToString: String = {
    /*
     * schemaToString is mainly called from DESCRIBE. Thus, we can take inPipeNames.head as relation name.
     */
    schema match {
      case Some(s) => s"${inPipeNames.head}: ${s.element.descriptionString}"
      case None => s"Schema for ${inPipeNames.head} unknown."
    }
  }

  /**
   * A helper function for traversing expression trees:
   *
   * Checks the (named) fields referenced in the expression (if any) if they conform to
   * the schema. Should be overridden in operators changing the schema by invoking
   * traverse with one of the traverser function.
   *
   * @return true if valid field references, otherwise false
   */
  def checkSchemaConformance: Boolean = {
    true
  }

  /**
   * Returns a MD5 hash string representing the sub-plan producing the input for this operator.
   *
   * @return the MD5 hash string
   */
  def lineageSignature: String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.digest(lineageString.getBytes).map("%02x".format(_)).mkString
  }

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  def lineageString: String = {
    inputs.map(p => p.producer.lineageString).mkString("%")
  }
}

/**
 * Load represents the LOAD operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param file the name of the file to be loaded
 */
case class Load(override val outPipeName: String, file: String,
                var loadSchema: Option[Schema] = None,
                loaderFunc: String = "", loaderParams: List[String] = null) extends PigOperator(outPipeName, List(), loadSchema) {
  override def constructSchema: Option[Schema] = {
    /*
     * Either the schema was defined or it is None.
     */
    schema
  }

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""LOAD%${file}%""" + super.lineageString
  }
}

/**
 * Dump represents the DUMP operator of Pig.
 *
 * @param inPipeName the name of the input pipe
 */
case class Dump(inPipeName: String) extends PigOperator("", inPipeName) {

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""DUMP%""" + super.lineageString
  }
}

/**
 * Store represents the STORE operator of Pig.
 *
 * @param inPipeName the name of the input pipe
 * @param file the name of the output file
 */
case class Store(inPipeName: String, file: String) extends PigOperator("", inPipeName) {

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""STORE%${file}%""" + super.lineageString
  }
}

/**
 * Describe represents the DESCRIBE operator of Pig.
 *
 * @param inPipeName the name of the input pipe
 */
case class Describe(inPipeName: String) extends PigOperator("", inPipeName) {

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""DESCRIBE%""" + super.lineageString
  }

}

case class GeneratorExpr(expr: ArithmeticExpr, alias: Option[Field] = None)

/**
 * Foreach represents the FOREACH operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeName the name of the input pipe
 * @param expr the generator expression
 */
case class Foreach(override val outPipeName: String, inPipeName: String, expr: List[GeneratorExpr])
  extends PigOperator(outPipeName, inPipeName) {

  override def constructSchema: Option[Schema] = {
    val inputSchema = inputs.head.producer.schema
    // we create a bag of tuples containing fields for each expression in expr
    val fields = expr.map(e => {
      e.alias match {
        // if we have an explicit schema (i.e. a field) then we use it
        case Some(f) => {
          if (f.fType == Types.ByteArrayType) {
            // if the type was only bytearray, we should check the expression if we have a more
            // specific type
            val res = e.expr.resultType(inputSchema)
            Field(f.name, res._2)
          }
          else
            f
        }
        // otherwise we take the field name from the expression and
        // the input schema
        case None => val res = e.expr.resultType(inputSchema); Field(res._1, res._2)
      }
    }).toArray
    schema = Some(new Schema(new BagType("", new TupleType("", fields))))
    schema
  }

  override def checkSchemaConformance: Boolean = {
    schema match {
      case Some(s) => {
        // if we know the schema we check all named fields
        expr.map(_.expr.traverse(s, Expr.checkExpressionConformance)).foldLeft(true)((b1: Boolean, b2: Boolean) => b1 && b2)
      }
      case None => {
        // if we don't have a schema all expressions should contain only positional fields
        expr.map(_.expr.traverse(null, Expr.containsNoNamedFields)).foldLeft(true)((b1: Boolean, b2: Boolean) => b1 && b2)
      }
    }
  }

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""FOREACH%${expr}%""" + super.lineageString
  }
}

/**
 * Filter represents the FILTER operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeName the name of the input pipe
 * @param pred the predicate used for filtering tuples from the input pipe
 */
case class Filter(override val outPipeName: String, inPipeName: String, pred: Predicate)
  extends PigOperator(outPipeName, inPipeName) {

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""FILTER%${pred}%""" + super.lineageString
  }

  override def checkSchemaConformance: Boolean = {
    schema match {
      case Some(s) => {
        // if we know the schema we check all named fields
        pred.traverse(s, Expr.checkExpressionConformance)
      }
      case None => {
        // if we don't have a schema all expressions should contain only positional fields
        pred.traverse(null, Expr.containsNoNamedFields)
      }
    }
  }


}

/**
 * Represents the grouping expression for the Grouping operator.
 *
 * @param keyList a list of keys used for grouping
 */
case class GroupingExpression(val keyList: List[Ref])

/**
 * Grouping represents the GROUP ALL / GROUP BY operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeName the name of the input pipe
 * @param groupExpr the expression (a key or a list of keys) used for grouping
 */
case class Grouping(override val outPipeName: String, inPipeName: String, groupExpr: GroupingExpression)
  extends PigOperator(outPipeName, inPipeName) {

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""GROUPBY%${groupExpr}%""" + super.lineageString
  }

  override def constructSchema: Option[Schema] = {
    val inputSchema = inputs.head.producer.schema
    // tuple(group: typeOfGroupingExpr, in:bag(inputSchema))
    val inputType = inputSchema match {
      case Some(s) => s.element.valueType
      case None => TupleType("", Array(Field("", Types.ByteArrayType)))
    }
    val groupingType = Types.IntType
    val fields = Array(Field("group", groupingType),
                      Field(inputs.head.name, BagType("", inputType)))
    schema = Some(new Schema(new BagType("", new TupleType("", fields))))
    schema
  }

  override def checkSchemaConformance: Boolean = {
    schema match {
      case Some(s) => {
        // if we know the schema we check all named fields
        groupExpr.keyList.filter(_.isInstanceOf[NamedField]).exists(f => s.indexOfField(f.asInstanceOf[NamedField].name) != -1)
      }
      case None => {
        // if we don't have a schema all expressions should contain only positional fields
        groupExpr.keyList.map(_.isInstanceOf[NamedField]).exists(b => b)
      }
    }
  }
}

/**
 * Distinct represents the DISTINCT operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeName the name of the input pipe.
 */
case class Distinct(override val outPipeName: String, inPipeName: String)
  extends PigOperator(outPipeName, inPipeName) {
  override def lineageString: String = {
    s"""DISTINCT%""" + super.lineageString
  }

}

/**
 * Union represents the UNION operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeName the name of the input pipe.
 */
case class Union(override val outPipeName: String, override val inPipeNames: List[String])
  extends PigOperator(outPipeName, inPipeNames) {
  override def lineageString: String = {
    s"""UNION%""" + super.lineageString
  }

}

/**
 * Limit represents the LIMIT operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeName the name of the input pipe.
 */
case class Limit(override val outPipeName: String, inPipeName: String, num: Int)
  extends PigOperator(outPipeName, inPipeName) {
  override def lineageString: String = {
    s"""LIMIT%${num}%""" + super.lineageString
  }

}

/**
 * Join represents the multiway JOIN operator of Pig.
 *
 * @param outPipeName the name of the output pipe (relation).
 * @param inPipeNames the list of names of input pipes.
 * @param fieldExprs  list of key expressions (list of keys) used as join expressions.
 */
case class Join(override val outPipeName: String, override val inPipeNames: List[String], val fieldExprs: List[List[Ref]])
  extends PigOperator(outPipeName, inPipeNames) {
  override def lineageString: String = {
    s"""JOIN%""" + super.lineageString
  }

}