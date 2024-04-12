// Spark: 3.5.1
// Local: --executor-memory 1G --driver-memory 1G --executor-cores 1 --master local[2] --packages io.delta:delta-spark_2.12:3.1.0 --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog
// Databricks: ...

import java.util.UUID
import io.delta.tables.DeltaTable
import org.apache.spark.sql.SparkSession

val input = "/tmp/amadeus-spark-lab/datasets/optd_por_public_filtered.csv"
val tmpPath = "/tmp/amadeus-spark-lab/sandbox/" + UUID.randomUUID()
val inputDir = tmpPath + "/input"

val spark: SparkSession = SparkSession.active

spark.sparkContext.setJobDescription("Read input CSV")
val airports = spark.read.option("delimiter","^").option("header","true").csv(input)

spark.sparkContext.setJobDescription("Format input CSV into delta (multiple files)")
airports.repartition(5).write.format("delta").save(inputDir)

def showMaxMinStats(tablePath: String, colName: String, commit: Int): Unit = {
  // stats on parquet files added to the table
  import org.apache.spark.sql.functions._
  import org.apache.spark.sql.types._
  val statsSchema = new StructType()
      .add("numRecords", IntegerType, true)
      .add("minValues", MapType(StringType, StringType), true)
      .add("maxValues", MapType(StringType, StringType), true)
  val df = spark.read.json(s"$tablePath/_delta_log/*${commit}.json")
    .withColumn("commit_json_name", input_file_name())
    .withColumn("add_stats", from_json(col("add.stats"), statsSchema))
    .withColumn(s"add_stats_min_col_${colName}", col(s"add_stats.minValues.$colName"))
    .withColumn(s"add_stats_max_col_${colName}", col(s"add_stats.maxValues.$colName"))
    .withColumn("add_size", col("add.size"))
    .withColumn("add_path", col("add.path"))
    .where(col("add_path").isNotNull)
    .select("add_path", s"add_stats_min_col_${colName}", s"add_stats_max_col_${colName}")
    .orderBy(s"add_stats_min_col_${colName}", "add_path")
  spark.sparkContext.setJobDescription(s"Display max/min stats for files present in delta table (commit ${commit})")
  df.show(false)
}

// show that files have data that is not well organized (ranges max/min values overlap for different files)
showMaxMinStats(inputDir, "country_code", 0)

spark.conf.set("spark.databricks.delta.optimize.maxFileSize", 100 * 1024L)
DeltaTable.forPath(inputDir).optimize().executeZOrderBy("country_code") 

// show that files have data that is well organized now
showMaxMinStats(inputDir, "country_code", 1)
