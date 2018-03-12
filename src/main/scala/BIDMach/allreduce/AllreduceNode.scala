package BIDMach.allreduce

import BIDMach.Learner
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Top-level root to all-reduce actor hierarchy, with children as dimension node actors, and grandchildren as line masters/round worker actors
  * The hierarchy has the following paths;
  * for round worker [user/Node/DimensionNode-dim={}/Worker-round={}],
  * and for line master [user/Node/DimensionNode-dim={}/LineMaster]
  *
  */
class AllreduceNode(nodeConfig: NodeConfig,
                    lineMasterConfig: LineMasterConfig,
                    workerConfig: WorkerConfig,
                    binder: AllreduceBinder
                   ) extends Actor with akka.actor.ActorLogging {

  val sink = binder.generateAverageModel(nodeConfig.elasticRate)
  val source = binder.generateDumpModel()
  val dimNum = nodeConfig.dimNum
  var dimensionNodeMap: Array[ActorRef] = Array.empty

  generateDimensionNodes()

  override def receive: Receive = {
    case _ => Unit
  }

  protected def dimensionNodeClassProvider(): Class[_] = {

    if (nodeConfig.reportStats) {
      classOf[AllreduceDimensionNodeWithStats]
    } else {
      classOf[AllreduceDimensionNode]
    }
  }

  def generateDimensionNodes(): Unit = {
    dimensionNodeMap = {
      val arr = new Array[ActorRef](dimNum)
      for (i <- 0 until dimNum) {
        val dimensionNode = context.actorOf(Props(
          dimensionNodeClassProvider(),
          DimensionNodeConfig(dim = i),
          lineMasterConfig,
          workerConfig,
          source,
          sink), s"DimensionNode-dim=${i}")
        println(s"-----Node: DimensionNode dim:$i created with ${dimensionNode}")
        arr(i) = dimensionNode
      }
      arr
    }
  }
}

object AllreduceNode {

  type DataSink = AllReduceOutput => Unit
  type DataSource = AllReduceInputRequest => AllReduceInput

  def startUp(port: String, nodeConfig: NodeConfig, lineMasterConfig: LineMasterConfig, workerConfig: WorkerConfig,
              learner: Learner) = {

    val config = ConfigFactory.parseString(s"\nakka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [Node]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)

    /*
    def getSourceSink(dim: Int=0): (DataSource, DataSink) = if (assertCorrectness) {
      getCorrectnessTestSourceSink(workerConfig.metaData.dataSize, checkpoint)
    } else {
      getDummySourceSink(workerConfig.metaData.dataSize, checkpoint, dim)
    }

    val (sourceList, sinkList) = {
      val dimSources: Array[List[DataSource]] = new Array(nodeConfig.dimNum)
      val dimSinks: Array[List[DataSink]] = new Array(nodeConfig.dimNum)
      for (i <- 0 until nodeConfig.dimNum) {
        val (source, sink) = getSourceSink(i)
        val roundSources: Array[DataSource] = Array.fill(lineMasterConfig.roundWorkerPerDimNum)(source)
        val roundSinks: Array[DataSink] = Array.fill(lineMasterConfig.roundWorkerPerDimNum)(sink)
        dimSources(i) = roundSources.toList
        dimSinks(i) = roundSinks.toList
      }
      (dimSources.toList, dimSinks.toList)
    }
    */

    system.actorOf(Props(classOf[AllreduceNode],
      nodeConfig,
      lineMasterConfig,
      workerConfig,
      learner
    ), name = "Node")

  }


  private def getDummySourceSink(sourceDataSize: Int, checkpoint: Int, dim: Int): (DataSource, DataSink) = {

    lazy val floats = Array.range(0, sourceDataSize).map(_.toFloat)
    val source: DataSource = _ => AllReduceInput(floats)
    val sink: DataSink = r => {
      if (r.iteration % checkpoint == 0 && r.iteration != 0) {
        println(s"----Dim$dim: Data output at #${r.iteration}")
      }
    }

    (source, sink)
  }

  /**
    * Get source and sink which assert correctness of the reduced data
    *
    * @param sourceDataSize total data size
    * @param checkpoint     round frequency at which the data should be checked
    */
  private def getCorrectnessTestSourceSink(sourceDataSize: Int, checkpoint: Int) = {

    val random = new scala.util.Random(100)
    val totalInputSample = 8

    lazy val randomFloats = {
      val nestedArray = new Array[Array[Float]](totalInputSample)
      for (i <- 0 until totalInputSample) {
        nestedArray(i) = Array.range(0, sourceDataSize).toList.map(_ => random.nextFloat()).toArray
      }
      nestedArray
    }

    def ~=(x: Double, y: Double, precision: Double = 1e-5) = {
      if ((x - y).abs < precision) true else false
    }

    // Specify data source
    val inputSet = mutable.HashSet[Int]()
    val source: DataSource = r => {
      assert(!inputSet.contains(r.iteration), s"Same data ${r.iteration} is being requested more than once")
      inputSet.add(r.iteration)
      AllReduceInput(randomFloats(r.iteration % totalInputSample))
    }

    // Specify data sink
    val outputSet = mutable.HashSet[Int]()
    val sink: DataSink = r => {
      assert(!outputSet.contains(r.iteration), s"Output data ${r.iteration} is being flushed more than once")
      outputSet.add(r.iteration)

      if (r.iteration % checkpoint == 0) {
        val inputUsed = randomFloats(r.iteration % totalInputSample)
        println(s"\n----Asserting #${r.iteration} output...")
        var zeroCountNum = 0
        var totalCount = 0
        for (i <- 0 until sourceDataSize) {
          val count = r.count(i)
          val meanActual = r.data(i) / count
          totalCount += count
          if (count == 0) {
            zeroCountNum += 1
          } else {
            val expected = inputUsed(i)
            assert(~=(expected, meanActual), s"Expected [$expected], but actual [$meanActual] at pos $i for iteraton #${r.iteration}")
          }
        }
        val nonZeroCountElementNum = sourceDataSize - zeroCountNum
        println("OK: Mean of non-zero elements match the expected input!")
        println(f"Element with non-zero counts: ${nonZeroCountElementNum / sourceDataSize.toFloat}%.2f ($nonZeroCountElementNum/$sourceDataSize)")
        println(f"Average count value: ${totalCount / nonZeroCountElementNum.toFloat}%2.2f ($totalCount/$nonZeroCountElementNum)")
      }
    }

    (source, sink)
  }

  def main(args: Array[String]): Unit = {


    val learner = AllreduceTrainer.leNetModel()
    //val learner = new AllreduceDummyLearner()
    learner.launchTrain

    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=0").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [Node]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)


    val dimNum = 2
    val maxChunkSize = 4
    val roundWorkerPerDimNum = 4
    val maxRound = 1000

    val threshold = ThresholdConfig(thAllreduce = 1f, thReduce = 1f, thComplete = 1f)
    val metaData = MetaDataConfig(maxChunkSize = maxChunkSize)

    val nodeConfig = NodeConfig(dimNum = dimNum, reportStats = true, elasticRate = 0.1)

    val workerConfig = WorkerConfig(
      statsReportingRoundFrequency = 5,
      threshold = threshold,
      metaData = metaData)

    val lineMasterConfig = LineMasterConfig(
      roundWorkerPerDimNum = roundWorkerPerDimNum,
      dim = -1,
      maxRound = maxRound,
      workerResolutionTimeout = 5.seconds,
      threshold = threshold,
      metaData = metaData)

    val allReduceLayer = new AllreduceLayer(system,
      threshold, metaData, nodeConfig, workerConfig, lineMasterConfig)

    val allReduceNode: Future[ActorRef] = allReduceLayer.startAfterIter(learner, iter = 0)

  }



}

