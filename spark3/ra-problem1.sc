// Spark: 3.4.2
// Local: --executor-memory 1G --driver-memory 1G --executor-cores 1 --master local[8] --packages io.delta:delta-core_2.12:2.4.0,org.apache.spark:spark-avro_2.12:3.3.2 --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog
// Databricks: ...

// COMMAND ----------

/*
Reproduce the symptom

# Symptom
You are joining a small table with a big one and you are reading way more data that is actually needed 
to perform the join operation.
You know that you could read less data, because you know that only few records in the big table match
with the join keys present in the small table.

*/

// COMMAND ----------

import java.util.UUID
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.DataFrame
import io.delta.tables.DeltaTable

val input = "/tmp/amadeus-spark-lab/datasets/optd_por_public_all.csv"
val tmpPath = "/tmp/amadeus-spark-lab/sandbox/" + UUID.randomUUID()
val probeDir = tmpPath + "/input"
val buildDir = tmpPath + "/employee"

val spark: SparkSession = SparkSession.active
import spark.implicits._

// Be sure to have the conditions to trigger DFP: broadcast join + table/files thresholds
spark.conf.set("spark.sql.autoBroadcastJoinThreshold", 1000000000L)
spark.conf.set("spark.databricks.optimizer.deltaTableSizeThreshold", 1)
spark.conf.set("spark.databricks.optimizer.deltaTableFilesThreshold", 1)
spark.conf.set("spark.databricks.optimizer.dynamicFilePruning", "true")

// COMMAND ----------

// Probe side
spark.sparkContext.setJobDescription("Create probe table")
def inputCsv() = spark.read.option("delimiter","^").option("header","true").csv(input)
val dfs = (1 to 100).map(_ => inputCsv()).reduce(_ union _)
dfs.write.mode("overwrite").format("delta").save(probeDir)

// optimize + z-order
spark.sparkContext.setJobDescription("Optimize + Z-order")
spark.conf.set("spark.databricks.delta.optimize.maxFileSize", 1 * 1024 * 1024L)
DeltaTable.forPath(probeDir).optimize().executeZOrderBy("country_code")

// COMMAND ----------

// Note the total number of files after z-ordering
DeltaTable.forPath(probeDir).detail.select("numFiles").show

// COMMAND ----------

def getMaxMinStats(tablePath: String, colName: String, commit: Int): DataFrame = {
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
  df
}

// Note that only 3 files out of 17 contain the country_code 'FR'
spark.sparkContext.setJobDescription(s"Display max/min stats for files present in delta table after z-order")
getMaxMinStats(probeDir, "country_code", 0).show(false)

// COMMAND ----------

// Build side
case class Employee(name: String, role: String, residence: String)
val employeesData = Seq(
  Employee("Thierry", "Software Engineer", "FR"),
  Employee("Mohammed", "DevOps", "FR"),
  Employee("Gene", "Intern", "FR"),
  Employee("Mau", "Intern", "FR"),
  // Important note: if the join keys on the build side are such that they hit every files of the probe side,
  // even having z-oder and activating DFP, there will still be read amplification.
  // Uncomment the following line to see that 1 more file will be read just for one new key (ZA).
  // Employee("Lex", "Intern", "ZA"),
  Employee("Mathieu", "Software Engineer", "FR"),
  Employee("Thomas", "Intern", "FR")
)
spark.sparkContext.setJobDescription("Create build table")
employeesData.toDF.write.mode("overwrite").format("delta").save(buildDir)

// COMMAND ----------

val deltaTable = DeltaTable.forPath(probeDir)
val employeeTable = DeltaTable.forPath(buildDir)

// Join a 2 tables (probe and build), adding a filter in the smaller one (the build side of the join)
def joinTables(description: String): Unit = {
  val probe = DeltaTable.forPath(probeDir).toDF
  val build = DeltaTable.forPath(buildDir).toDF
  spark.sparkContext.setJobDescription(s"Join tables: $description")
  probe
  .join(build, build("residence") === probe("country_code"), "inner")
  .where(col("role") === "Intern")
  .count()
}

joinTables("Big Read Amplification")

// Go to the Databricks Spark UI, look for the SQL query corresponding to the Join,
// and see the number of files actually read in the 'Scan parquet' node