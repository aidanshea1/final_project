package final_project

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.graphx._
import org.apache.spark.storage.StorageLevel
import org.apache.log4j.{Level, Logger}

object main{
  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
  Logger.getLogger("org.spark-project").setLevel(Level.WARN)


  def LubyMM(g_in: Graph[Int, Int]): Graph[(Double), (Int, Double)] = {
    var remaining_vertices = g_in.numVertices
    println("Initial Remaining Edges: " + remaining_vertices)
    var iterations = 0
    var random = scala.util.Random

    // add the double parameter for each edge in the graph
    // 0 = active; -1 = deactivated; 1 = added to MM
    var final_graph = g_in.mapEdges((i) => (0, 0.0)).mapVertices((id, i) => (0.0))

    while (remaining_vertices >= 1) {
      // give each edge a random double
      final_graph = final_graph.mapEdges((i) => (i.attr._1, random.nextDouble))

      // send edge values to vertices
      // the vertices will accept the largest value on an edge of which they are connected
      var edge_to_vertices = final_graph.aggregateMessages[(Double)](
        edge => {
          if (edge.attr._1 == 0) {
            edge.sendToSrc(edge.attr._2)
            edge.sendToDst(edge.attr._2)
          } else {
            edge.sendToSrc(edge.attr._1)
            edge.sendToDst(edge.attr._1)
          }
        },
        (a,b) => a.max(b)
      )
      // graph temp_graph has vertices which contain the highest edge values
      var temp_graph = Graph(edge_to_vertices, final_graph.edges)

      // check values of vertices
      // if they are equal, add the edge to the MM
      var check = temp_graph.triplets.map(
        triplet => {
          if (triplet.attr._1 == 0) {
            if (triplet.srcAttr == 1 || triplet.dstAttr == 1) {
              Edge(triplet.srcId, triplet.dstId,(-1, triplet.attr._2))
            } else if (triplet.srcAttr == triplet.dstAttr) {
              Edge(triplet.srcId, triplet.dstId,(1, triplet.attr._2))
            } else {
              Edge(triplet.srcId, triplet.dstId,(triplet.attr._1, triplet.attr._2))
            }
          } else {
            Edge(triplet.srcId, triplet.dstId,(triplet.attr._1, triplet.attr._2))
          }
        })
      final_graph = Graph(temp_graph.vertices, check)
      remaining_vertices = final_graph.edges.filter({case (i) => (i.attr._1 == 0)} ).count() - 1
      println("Remaining vertices: " + remaining_vertices)
      iterations += 1
    }
    println("Iterations: " + iterations)
    return final_graph
  }

  def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("final_project")
    val sc = new SparkContext(conf)
    val spark = SparkSession.builder.config(conf).getOrCreate()
    /* You can either use sc or spark */

    if(args.length != 2) {
      println("Usage: final_project option = [path_to_graph_file] [path_to_output_file]")
      sys.exit(1)
    }

    val startTimeMillis = System.currentTimeMillis()
    val edges = sc.textFile(args(0)).map(line => {var x = line.split(","); Edge(x(0).toLong, x(1).toLong , 1)} )
    val g = Graph.fromEdges[Int, Int](edges, 0, edgeStorageLevel = StorageLevel.MEMORY_AND_DISK, vertexStorageLevel = StorageLevel.MEMORY_AND_DISK)
    g.cache()
    var g2 = LubyMM(g).mapEdges((i) => i.attr._1).edges.filter({case (id) => (id.attr == 1)})
    val endTimeMillis = System.currentTimeMillis()
    val durationSeconds = (endTimeMillis - startTimeMillis) / 1000
    println("==================================")
    println("Luby's Bidding Maximal Matching algorithm completed in " + durationSeconds + "s.")
    println("==================================")

    g2.cache()
    var g2df = spark.createDataFrame(g2)
    g2df.cache()
    g2df = g2df.drop(g2df.columns.last)
    g2df.coalesce(1).write.format("csv").mode("overwrite").save(args(1))
    sys.exit(1)
  }
}