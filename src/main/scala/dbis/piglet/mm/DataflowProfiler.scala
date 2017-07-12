package dbis.piglet.mm

import java.net.URI
import java.nio.file.{Files, Path, StandardOpenOption}

import dbis.piglet.Piglet.Lineage
import dbis.piglet.op.CacheMode.CacheMode
import dbis.piglet.op.{CacheMode, Load, TimingOp}
import dbis.piglet.plan.DataflowPlan
import dbis.piglet.tools.logging.PigletLogging
import dbis.piglet.tools.{BreadthFirstTopDownWalker, CliParams, Conf}
import dbis.setm.SETM.timing

import scala.collection.JavaConverters._
import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.duration._
import scala.io.Source


case class T private(sum: Long, cnt: Int, min: Long = Long.MaxValue, max: Long = Long.MinValue) {
  def avg(): Long = sum / cnt
}

object T {

  def apply(value: Long): T = T(value,1,value,value)

  def merge(t: T, value: Long): T = T(cnt = t.cnt +1,
                                      sum = t.sum + value,
                                      min = if(value < t.min) value else t.min,
                                      max = if(value > t.max) value else t.max)
}

case class Partition(lineage: Lineage, partitionId: Int)

object DataflowProfiler extends PigletLogging {

  protected[mm] var profilingGraph: Markov = _

  val parentPartitionInfo = MutableMap.empty[Lineage, MutableMap[Int, Seq[Seq[Int]]]]
  val currentTimes = MutableMap.empty[Partition, Long]

  // for json (de-)serialization
  implicit val formats = org.json4s.native.Serialization.formats(org.json4s.NoTypeHints)


  def load(base: Path) = {
    logger.debug("init Dataflow profiler")

    val profilingFile = base.resolve(Conf.profilingFile)
    if(Files.exists(profilingFile)) {
      val json = Source.fromFile(profilingFile.toFile).getLines().mkString("\n")
      profilingGraph = Markov.fromJson(json)
    } else
      profilingGraph = Markov.empty

    logger.debug(s"loaded markov model with size: ${profilingGraph.size}")
    logger.debug(s"total runs in markov is: ${profilingGraph.totalRuns}")
  }

  def reset() = {
    currentTimes.clear()
  }

  /**
    * Analyze the current plan.
    *
    * This will count the operator counts and number of transitions from Op A to Op B etc
    * @param plan The plan to analyze
    * @return Returns the updated model (Markov chain) that contains operator statistics from
    *         previous runs as well as the updated counts
    */
  def analyze(plan: DataflowPlan): Markov = timing("analyze plan") {

    // reset old values for parents and execution time
    reset()

    profilingGraph.incrRuns()

    /* walk over the plan and
     *   - count operator occurrences
     *   - save the parent information
     */

    BreadthFirstTopDownWalker.walk(plan){
      case _: TimingOp => // ignore timing operators
      case op =>
        val lineage = op.lineageSignature

        if(op.isInstanceOf[Load])
          profilingGraph.add("start",lineage)

        op.outputs.flatMap(_.consumer).foreach{ c =>
          logger.debug(s"add to model ${op.name} -> ${c.name}")
          profilingGraph.add(lineage, c.lineageSignature)
        }
    }

    profilingGraph
  }


  def collect() = timing("process runtime stats") {

    currentTimes//.keySet
                //.map(_.lineage)
                .filterNot{ case (Partition(lineage,_),_) => lineage == "start" || lineage == "end" || lineage == "progstart" }
                .foreach{ case (partition,time) =>

      val lineage = partition.lineage
      val partitionId = partition.partitionId

      // parent operators
      val parentLineages = profilingGraph.parents(lineage).getOrElse(List(Markov.startNode.lineage)) //parentsOf(lineage)
      // list of parent partitions per parent
      val parentPartitionIds = parentPartitionInfo(lineage)

      /* for each parent operator, get the times for the respective parent partitions.
       * and at the end take the min (first processed parent partition) or max (last processed parent partition) value
       */
      val parentTimes = parentPartitionIds(partitionId).zipWithIndex.flatMap{ case (list, idx) =>
          val parentLineage = parentLineages(idx)

          list.map{ pId =>
            val p = if(parentLineage == Markov.startNode.lineage)
                Partition(parentLineage, -1) // for "start" we only have one value with partition id -1
//            else if(parentLineage == "progstart")
//              Partition(parentLineage, -1)
              else
                Partition(parentLineage,pId)

            if(currentTimes.contains(p))
              currentTimes(p)
            else {
              logger.error("currentTimes: ")
              logger.error(currentTimes.mkString("\n"))
              throw ProfilingException(s"no $p in list of current execution times")
            }
          }
        }

      val earliestParentTime = if(parentTimes.nonEmpty) {
        parentTimes.max
      } else {
        throw ProfilingException(s"no parent time for $lineage on partition $partitionId")
      }

      val duration = time - earliestParentTime

      profilingGraph.updateCost(lineage, duration)

    }

    // manually add execution time for creating spark context
    val progStart = currentTimes(Partition("progstart", -1))
    val start = currentTimes(Partition("start", -1))

    profilingGraph.add("sparkcontext","start")
    profilingGraph.updateCost("sparkcontext", start - progStart)

  }



  def addExecTime(lineage: Lineage, partitionId: Int, parentPartitions: Seq[Seq[Int]], time: Long) = {

    val p = Partition(lineage, partitionId)
    if(currentTimes.contains(p)) {
      logger.warn(s"we already have a time for $p : oldTime: ${currentTimes(p)}  newTime: $time  (diff: ${currentTimes(p) - time}ms")
    }
    currentTimes +=  p -> time

    if(parentPartitionInfo.contains(lineage)) {

      val m = parentPartitionInfo(lineage)

      if(m.contains(partitionId)) {
        logger.warn(s"we already have that partition: $lineage  $partitionId . ")
      } else {
        m += partitionId -> parentPartitions
      }

    } else {
      parentPartitionInfo += lineage -> MutableMap(partitionId -> parentPartitions)
    }

  }

  def addSizes(m: Map[Lineage, Option[Long]]) = m.foreach{ case(lineage, size) =>
      profilingGraph.updateSize(lineage, size.map(_*10))
  }

  def getExectime(op: Lineage): Option[T] = profilingGraph.cost(op)

  def writeStatistics(c: CliParams): Unit = {

    val opCountFile = Conf.programHome.resolve(Conf.profilingFile)
    logger.debug(s"writing opcounts to $opCountFile with ${profilingGraph.size} entries")

    val opJson = profilingGraph.toJson
    Files.write(opCountFile,
      List(opJson).asJava,
      StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
  }

  def opInputSize(op: Lineage) = profilingGraph.inputSize(op)

}

case class ProfilingException(msg: String) extends Exception


object ProbStrategy extends Enumeration  {
  type ProbStrategy = Value
  val MIN, MAX, AVG, PRODUCT = Value

  def func(s: ProbStrategy): (Traversable[Double]) => Double = s match {
    case MIN => Markov.ProbMin
    case MAX => Markov.ProbMax
    case AVG => Markov.ProbAvg
    case PRODUCT => Markov.ProbProduct
  }
}

object CostStrategy extends Enumeration {
  type CostStrategy = Value
  val MIN, MAX = Value

  def func(s: CostStrategy): (Traversable[(Long, Double)]) => (Long, Double) = s match {
    case MIN => Markov.CostMin
    case MAX => Markov.CostMax
  }
}

import ProbStrategy.ProbStrategy
import CostStrategy.CostStrategy

case class ProfilerSettings(
                             minBenefit: Duration = Conf.mmDefaultMinBenefit,
                             probThreshold: Double = Conf.mmDefaultProbThreshold,
                             costStrategy: CostStrategy = Conf.mmDefaultCostStrategy,
                             probStrategy: ProbStrategy = Conf.mmDefaultProbStrategy,
                             cacheMode: CacheMode = Conf.mmDefaultCacheMode,
                             fraction: Int = Conf.mmDefaultFraction,
                             url: URI = ProfilerSettings.profilingUrl
                           )

object ProfilerSettings extends PigletLogging {
  def apply(m: Map[String, String]): ProfilerSettings = {
    var ps = ProfilerSettings()

    m.foreach { case (k,v) =>
      k match {
        case "prob" => ps = ps.copy(probThreshold = v.toDouble)
        case "benefit" => ps = ps.copy(minBenefit = v.toDouble.seconds)
        case "cost_strategy" => ps = ps.copy(costStrategy = CostStrategy.withName(v.toUpperCase))
        case "prob_strategy" => ps = ps.copy(probStrategy = ProbStrategy.withName(v.toUpperCase))
        case "cache_mode" => ps = ps.copy(cacheMode = CacheMode.withName(v.toUpperCase))
        case "fraction" => ps = ps.copy(fraction = v.toInt)
        case _ => logger warn s"unknown profiler settings key $k (value: $v) - ignoring"
      }
    }

    ps
  }

  private lazy val profilingUrl = if(Conf.statServerURL.isDefined) {
    Conf.statServerURL.get.toURI
  } else {
    val addr = java.net.InetAddress.getLocalHost.getHostAddress
    logger.debug(s"identified local address as $addr")
    val u = URI.create(s"http://$addr:${Conf.statServerPort}/")
    u
  }
}