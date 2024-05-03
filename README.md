# Performance Hikes for Apache Spark

[![License](http://img.shields.io/:license-Apache%202-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)

This project contains a collection of code snippets designed for hands-on exploration of Apache Spark functionalities, with a strong focus on performance optimization.

Each snippet is pedagogic, self-contained, executable, and focuses on one performance problem providing at least one possible solution.

Thanks to this, the user learns about problems/features by:​
- exploring and reading the snippets​
- running them​
- following them on Spark UI​
- monitoring them​
- modifying them​
- ...​

A typical snippet has the folowing structure:

```scala
// Spark: <version of spark this snippet is intended to be used with>
// Local: <spark shell extra options when used locally, e.g. --master local[2] --driver-memory 1G >
// Databricks: <placeholder, unused for now>

// COMMAND ----------

/*
<brief description of problem and a possible solution high level>

# Symptom
<problem symptoms>

# Explanation
<explanation of the potential solution, the analysis or any other detail about how to address the problem>
*/

// COMMAND ----------

spark.sparkContext.setJobDescription("JOB")
val path = ???
val airports = spark.read.option("delimiter","^").option("header","true").csv(path)
/// ... more scala code ...
```

## Getting started

The following documentation has been tested on computers running a Linux like OS, 
where [Apache Spark 3.5.1](https://spark.apache.org/downloads.html), as well as Java are already installed.

### A. Initialize the environment

The very first time you use the project you need to install it.

0. Configure the Databricks cli on your machine.

   This step is optional, and you have to do it only if you want to run the snippets on Databricks.
   Be sure to:
   - install Databricks cli versions 0.205 and above (cf. https://docs.databricks.com/en/dev-tools/cli/tutorial.html)
   - define the profile correctly in your `~/.databrickscfg` file for authentication.

1. Add the following to your `~/.bashrc` ( ~/.zshrc for MacOS ) file: 

```bash
export PERF_HIKES_PATH=<this-path>
export PATH=$PATH:<spark-shell-directory>
source $PERF_HIKES_PATH/source.sh # to define the aliases
```

2. Then set up some sample datasets:

```
spark-init-datasets-local
spark-init-datasets-databricks <profile>
```

3. Make sure `spark-shell` is in your `PATH`

### B. Launch a snippet

#### B.1 Launch a snippet locally

```bash
spark-run-local <snippet.sc>
```

Some existing snippets:

- [Spill](spark3%2Fspill-increase-shuffle-partitions.sc)
- [Thread Contention](spark3%2Fthread-contention-adapt-closure.sc)
- [Partition Pruning](spark3%2Fscd-type-2-merge-partition-pruning.sc)
- [Delta table Z-Ordering and file pruning](spark3%2Fread-amplification-zorder-fp.sc)
- Dynamic File Pruning [in join with zorder](spark3%2Fread-amplification-join-dfp-zorder.sc) & [in merge with zorder and photon](spark3%2Fread-amplification-merge-dfp-zorder-photon.sc)
- Write amplification & [deletion vectors](spark3%2Fwrite-amplification-deletion-vector.sc)
- [Spark UI retention limits](spark3%2Fsparkui-incomplete-persist-events.sc)

Note: Dynamic File Pruning and File Pruning examples only work as intended on Databricks.

#### B.2 Launch a snippet _on Databricks_

First copy the snippet you want on your Databricks workspace:

```
spark-import-databricks <snippet.sc> <profile> <destination-folder> <extra-args: e.g., --overwrite>
```

### C. Write your own snippet

#### IDE setup

To write a snippet we encourage you to create a `build.sbt` file, and load the project as a scala project:

```scala
val sparkVersion = "3.4.1"
val deltaVersion = "2.4.0"
val deps = Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-avro" % sparkVersion,
  "io.delta" %% "delta-core" % deltaVersion
)
lazy val root = (project in file("."))
  .settings(
    name := "perf-hikes",
    scalaVersion := "2.12.13",
    libraryDependencies := deps
  )
```