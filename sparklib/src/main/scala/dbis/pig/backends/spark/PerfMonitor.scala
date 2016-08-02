package dbis.pig.backends.spark

import org.apache.spark.scheduler._
import scala.collection.mutable.{Map => MutableMap}
import scala.collection.mutable.ListBuffer

/**
 * A performance monitor to collect Spark job statistics. It extends {{SparkListener}}
 * which allows to register it at the SparkContext and get notified, when a Task or Stage
 * is submitted or finished. 
 * 
 * We use the provided information to collect statistics about runtimes and result sizes of a stage 
 */
class PerfMonitor(appName: String, url: String) extends SparkListener {
  
  private val progStartTime = System.currentTimeMillis()
  
  private val lineLineageMapping = MutableMap.empty[Int, String]
  
  private val stageSizeMapping = MutableMap.empty[Int, Long]  
  

  /**
   * Action when a task has completed. Here, we collect information about the result
   * size of the completed task
   * 
   * @param taskEnd Contains information about the completed task, such as the stage it belongs to, etc
   */
  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
    if(stageSizeMapping.contains(taskEnd.stageId))
        stageSizeMapping(taskEnd.stageId) += taskEnd.taskMetrics.resultSize
    else    
      stageSizeMapping += (taskEnd.stageId -> taskEnd.taskMetrics.resultSize) 
  }

  /**
   * Action when a stage has completed. We write the stage's statistics to our database.<br />
   * Note, this method is synchronized to make it thread safe
   * 
   * @param stageCompleted Contains information about the completed stage, such as id, submission time, etc
   * 
   */
  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = this.synchronized {
    
    val jsonString = s"""{
        "appname": "$appName",
        "stageid": ${stageCompleted.stageInfo.stageId},
        "stagename":"${stageCompleted.stageInfo.name}",
        "lineage": "${lineLineageMapping.getOrElse(stageCompleted.stageInfo.name.split(":")(1).toInt, "") }",
        "stageduration": ${stageCompleted.stageInfo.completionTime.get - stageCompleted.stageInfo.submissionTime.get},
        "progduration": ${stageCompleted.stageInfo.completionTime.get - progStartTime},
        "submissiontime": ${stageCompleted.stageInfo.submissionTime.get},
        "completiontime": ${stageCompleted.stageInfo.completionTime.get},
        "size": ${stageSizeMapping.getOrElse(stageCompleted.stageInfo.stageId, -1)}
      }"""
        
    val result = scalaj.http.Http(url).postData(jsonString).header("Content-Type", "application/json").header("Charset","UTF-8").asString
    
    if(result.isError)
      println(s"Failed to send execution statistics for stage ${stageCompleted.stageInfo.stageId}: ${result.statusLine}")
  }

  
  /**
   * Register the operator, represented by the given lineage, to appear at the given line.
   * 
   * @param line The line number in the Pig script at which the operator is written
   * @param lineage The lineage signature of the operator 
   */
  def register(line: Int, lineage: String) {
    lineLineageMapping += (line -> lineage)
  }
  
  
}
