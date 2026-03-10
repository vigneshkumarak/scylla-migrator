package com.scylladb.migrator.scylla

import com.scylladb.migrator.config.{ MigratorConfig, SourceSettings, TargetSettings }
import com.scylladb.migrator.readers
import com.scylladb.migrator.readers.MySQL
import com.scylladb.migrator.validation.RowComparisonFailure
import com.scylladb.migrator.writers
import org.apache.log4j.LogManager
import org.apache.spark.sql.{ DataFrame, SparkSession }
import org.apache.spark.sql.functions._

object MySQLToScyllaValidator {

  private val log = LogManager.getLogger("com.scylladb.migrator.scylla.MySQLToScyllaValidator")

  def runValidation(
    sourceSettings: SourceSettings.MySQL,
    targetSettings: TargetSettings.Scylla,
    config: MigratorConfig
  )(implicit spark: SparkSession): List[RowComparisonFailure] = {

    val validationConfig =
      config.validation.getOrElse(
        sys.error("Missing required property 'validation' in the configuration file.")
      )

    val primaryKey = sourceSettings.primaryKey.getOrElse(
      sys.error(
        "Missing required property 'primaryKey' in MySQL source configuration. " +
          "The validator needs to know which columns form the primary key for joining rows."
      )
    )

    if (primaryKey.isEmpty)
      sys.error("'primaryKey' must contain at least one column name.")

    val hashColumns = validationConfig.hashColumns.filter(_.nonEmpty)

    log.info(s"Starting MySQL-to-ScyllaDB validation")
    log.info(s"Primary key columns: ${primaryKey.mkString(", ")}")
    hashColumns.foreach(cols =>
      log.info(s"Hash-based comparison for columns: ${cols.mkString(", ")}")
    )

    val renamesMap = config.renamesMap
    val renamedPK = primaryKey.map(renamesMap)

    val (sourceDF, comparableColumns) = hashColumns match {
      case Some(cols) =>
        val df = readers.MySQL.readDataframeWithHash(spark, sourceSettings, cols)
        val renamedDF = renamesMap.foldLeft(df) {
          case (d, (from, to)) if from != to && df.columns.contains(from) =>
            d.withColumnRenamed(from, to)
          case (d, _) => d
        }
        val nonPKCols = renamedDF.columns
          .filter(c => !renamedPK.contains(c))
          .filter(_ != MySQL.ContentHashColumn)
        (renamedDF, nonPKCols :+ MySQL.ContentHashColumn)

      case None =>
        val df = readers.MySQL.readDataframe(spark, sourceSettings).dataFrame
        val renamedDF = renamesMap.foldLeft(df) {
          case (d, (from, to)) if from != to && df.columns.contains(from) =>
            d.withColumnRenamed(from, to)
          case (d, _) => d
        }
        val nonPKCols = renamedDF.columns.filter(c => !renamedPK.contains(c))
        (renamedDF, nonPKCols)
    }

    val targetConnectionOptions = buildTargetConnectionOptions(targetSettings)

    log.info(s"Connecting to ScyllaDB target at ${targetSettings.host}:${targetSettings.port}")

    val targetDF = hashColumns match {
      case Some(cols) =>
        val rawTarget = spark.read
          .format("org.apache.spark.sql.cassandra")
          .options(targetConnectionOptions)
          .load()
        addScyllaContentHash(rawTarget, cols.map(renamesMap), renamedPK)

      case None =>
        spark.read
          .format("org.apache.spark.sql.cassandra")
          .options(targetConnectionOptions)
          .load()
    }

    val targetColumns = targetDF.columns.toSet
    val droppedColumns = comparableColumns.filterNot(targetColumns.contains)
    if (droppedColumns.nonEmpty)
      log.warn(
        s"Columns present in source but missing in target (skipped): ${droppedColumns.mkString(", ")}"
      )
    val finalComparableColumns = comparableColumns.filter(targetColumns.contains)
    log.info(s"Comparable columns: ${finalComparableColumns.mkString(", ")}")

    val sourcePrefixed = prefixColumns(sourceDF, "src_")
    val targetPrefixed = prefixColumns(targetDF, "tgt_")

    val joinCondition = renamedPK
      .map(pk => sourcePrefixed(s"src_$pk") === targetPrefixed(s"tgt_$pk"))
      .reduce(_ && _)

    val joined = sourcePrefixed.join(targetPrefixed, joinCondition, "left_outer")

    val tgtPKFirst = s"tgt_${renamedPK.head}"
    val floatTol = validationConfig.floatingPointTolerance
    val tsTol = validationConfig.timestampMsTolerance

    val failuresRdd = joined.rdd
      .flatMap { joinedRow =>
        val tgtNull = joinedRow.isNullAt(joinedRow.fieldIndex(tgtPKFirst))

        if (tgtNull) {
          val srcRepr =
            renamedPK.map(pk => s"$pk=${joinedRow.getAs[Any](s"src_$pk")}").mkString(", ")
          Some(
            RowComparisonFailure(srcRepr, None, List(RowComparisonFailure.Item.MissingTargetRow))
          )
        } else {
          val differingFields = finalComparableColumns.flatMap { colName =>
            val srcIdx = joinedRow.fieldIndex(s"src_$colName")
            val tgtIdx = joinedRow.fieldIndex(s"tgt_$colName")
            val srcVal = if (joinedRow.isNullAt(srcIdx)) None else Some(joinedRow.get(srcIdx))
            val tgtVal = if (joinedRow.isNullAt(tgtIdx)) None else Some(joinedRow.get(tgtIdx))
            if (valuesAreDifferent(srcVal, tgtVal, floatTol, tsTol))
              Some(colName)
            else
              None
          }
          if (differingFields.isEmpty) None
          else {
            val srcRepr =
              renamedPK.map(pk => s"$pk=${joinedRow.getAs[Any](s"src_$pk")}").mkString(", ")
            val tgtRepr =
              renamedPK.map(pk => s"$pk=${joinedRow.getAs[Any](s"tgt_$pk")}").mkString(", ")
            Some(
              RowComparisonFailure(
                srcRepr,
                Some(tgtRepr),
                List(RowComparisonFailure.Item.DifferingFieldValues(differingFields.toList))
              )
            )
          }
        }
      }
      .cache()

    val totalFailures = failuresRdd.count()
    log.info(s"Validation complete. Total mismatched rows: $totalFailures")

    if (validationConfig.autoRepair && totalFailures > 0) {
      log.info(s"Auto-repair enabled. Repairing all $totalFailures mismatched row(s)...")
      repairFailedRows(
        joined,
        renamedPK,
        primaryKey,
        tgtPKFirst,
        finalComparableColumns,
        floatTol,
        tsTol,
        sourceSettings,
        targetSettings,
        config
      )
    }

    val failures = failuresRdd.take(validationConfig.failuresToFetch).toList
    failuresRdd.unpersist()

    failures
  }

  /** Add a `_content_hash` column to the ScyllaDB DataFrame by computing MD5(concat_ws('|',
    * coalesce(col1,''), coalesce(col2,''), ...)) in Spark. This mirrors the MySQL-side hash
    * computation so the values can be compared directly.
    *
    * After adding the hash, the original hashed columns are dropped from the DataFrame to reduce
    * shuffle data volume during the join.
    */
  private def addScyllaContentHash(
    df: DataFrame,
    hashCols: List[String],
    pkCols: List[String]
  ): DataFrame = {
    val existingHashCols = hashCols.filter(df.columns.contains)
    if (existingHashCols.isEmpty) {
      log.warn("No hash columns found in ScyllaDB table. Skipping hash computation.")
      return df
    }

    log.info(
      s"Computing content hash on ScyllaDB side for columns: ${existingHashCols.mkString(", ")}"
    )

    val coalesced = existingHashCols.map(c => coalesce(col(c).cast("string"), lit("")))
    val hashCol = md5(concat_ws("|", coalesced: _*))
    val withHash = df.withColumn(MySQL.ContentHashColumn, hashCol)

    val colsToDrop = existingHashCols.filterNot(pkCols.contains)
    colsToDrop.foldLeft(withHash) { (d, c) =>
      d.drop(c)
    }
  }

  private def prefixColumns(df: DataFrame, prefix: String): DataFrame =
    df.columns.foldLeft(df) { (d, c) =>
      d.withColumnRenamed(c, s"$prefix$c")
    }

  /** Repairs failed rows by re-reading only the problematic rows from MySQL.
    *
    * Collects the failed primary key values to the driver (safe because the number of failures is
    * typically small), builds a targeted WHERE IN clause, and reads only those rows from MySQL.
    * This avoids re-reading the entire table (which could be millions of rows) just to fix a
    * handful of mismatches.
    */
  private def repairFailedRows(
    joined: DataFrame,
    renamedPK: List[String],
    sourcePK: List[String],
    tgtPKFirst: String,
    comparableColumns: Array[String],
    floatTol: Double,
    tsTol: Long,
    sourceSettings: SourceSettings.MySQL,
    targetSettings: TargetSettings.Scylla,
    config: MigratorConfig
  )(implicit spark: SparkSession): Unit = {

    val failedRowsRdd = joined.rdd.filter { joinedRow =>
      val tgtNull = joinedRow.isNullAt(joinedRow.fieldIndex(tgtPKFirst))
      if (tgtNull) true
      else {
        comparableColumns.exists { colName =>
          val srcIdx = joinedRow.fieldIndex(s"src_$colName")
          val tgtIdx = joinedRow.fieldIndex(s"tgt_$colName")
          val srcVal = if (joinedRow.isNullAt(srcIdx)) None else Some(joinedRow.get(srcIdx))
          val tgtVal = if (joinedRow.isNullAt(tgtIdx)) None else Some(joinedRow.get(tgtIdx))
          valuesAreDifferent(srcVal, tgtVal, floatTol, tsTol)
        }
      }
    }

    val failedPKsDF = spark.createDataFrame(failedRowsRdd, joined.schema)
    val failedPKRows =
      failedPKsDF.select(renamedPK.map(pk => col(s"src_$pk").as(pk)): _*).collect()
    val failedCount = failedPKRows.length
    log.info(s"Auto-repair: identified $failedCount row(s) to repair")

    if (failedCount == 0) return

    val pkFilter = buildWhereInClause(sourcePK, failedPKRows)
    val combinedWhere = sourceSettings.where match {
      case Some(existing) => s"($existing) AND $pkFilter"
      case None           => pkFilter
    }
    log.info(s"Auto-repair: reading $failedCount row(s) from MySQL with targeted query")

    val repairSettings = sourceSettings.copy(
      where           = Some(combinedWhere),
      partitionColumn = None,
      numPartitions   = None
    )
    val repairSourceDF = readers.MySQL.readDataframe(spark, repairSettings)
    val renamesMap = config.renamesMap

    val renamedRepairDF = renamesMap.foldLeft(repairSourceDF.dataFrame) {
      case (d, (from, to)) if from != to && repairSourceDF.dataFrame.columns.contains(from) =>
        d.withColumnRenamed(from, to)
      case (d, _) => d
    }

    val repairedCount = renamedRepairDF.count()
    log.info(s"Auto-repair: fetched $repairedCount row(s) from MySQL for repair")

    if (repairedCount > 0) {
      writers.Scylla.writeDataframe(
        targetSettings,
        config.getRenamesOrNil,
        renamedRepairDF,
        repairSourceDF.timestampColumns,
        None
      )
      log.info(s"Auto-repair: wrote $repairedCount row(s) to ScyllaDB")
    }
  }

  private def buildWhereInClause(
    pkColumns: List[String],
    rows: Array[org.apache.spark.sql.Row]
  ): String =
    if (pkColumns.size == 1) {
      val values = rows.map(r => formatSqlLiteral(r.get(0))).mkString(",")
      s"`${pkColumns.head}` IN ($values)"
    } else {
      val colList = pkColumns.map(c => s"`$c`").mkString(",")
      val tuples = rows
        .map { r =>
          val vals = pkColumns.indices.map(i => formatSqlLiteral(r.get(i))).mkString(",")
          s"($vals)"
        }
        .mkString(",")
      s"($colList) IN ($tuples)"
    }

  private def formatSqlLiteral(value: Any): String = value match {
    case null                => "NULL"
    case n: java.lang.Long   => n.toString
    case n: java.lang.Number => n.toString
    case s: String           => s"'${s.replace("'", "''")}'"
    case other               => s"'${other.toString.replace("'", "''")}'"
  }

  private[migrator] def buildTargetConnectionOptions(
    targetSettings: TargetSettings.Scylla
  ): Map[String, String] = {
    val base = Map(
      "keyspace"                        -> targetSettings.keyspace,
      "table"                           -> targetSettings.table,
      "spark.cassandra.connection.host" -> targetSettings.host,
      "spark.cassandra.connection.port" -> targetSettings.port.toString
    )
    val withCreds = targetSettings.credentials.fold(base) { creds =>
      base ++ Map(
        "spark.cassandra.auth.username" -> creds.username,
        "spark.cassandra.auth.password" -> creds.password
      )
    }
    targetSettings.localDC.fold(withCreds) { dc =>
      withCreds + ("spark.cassandra.connection.localDC" -> dc)
    }
  }

  private def valuesAreDifferent(
    leftValue: Option[Any],
    rightValue: Option[Any],
    floatingPointTolerance: Double,
    timestampMsTolerance: Long
  ): Boolean =
    (leftValue, rightValue) match {
      case (Some(l: java.sql.Timestamp), Some(r: java.sql.Timestamp)) if timestampMsTolerance > 0 =>
        Math.abs(l.getTime - r.getTime) > timestampMsTolerance
      case (Some(l: java.time.Instant), Some(r: java.time.Instant)) if timestampMsTolerance > 0 =>
        Math.abs(java.time.Duration.between(l, r).toMillis) > timestampMsTolerance
      case (Some(l: Float), Some(r: Float)) =>
        Math.abs(l - r) > floatingPointTolerance
      case (Some(l: Double), Some(r: Double)) =>
        Math.abs(l - r) > floatingPointTolerance
      case (Some(l: java.math.BigDecimal), Some(r: java.math.BigDecimal)) =>
        l.subtract(r).abs().compareTo(new java.math.BigDecimal(floatingPointTolerance)) > 0
      case (Some(l: Array[Byte]), Some(r: Array[Byte])) =>
        !java.util.Arrays.equals(l, r)
      case (Some(l: Array[_]), Some(r: Array[_])) =>
        !l.sameElements(r)
      case (Some(l), Some(r)) => l != r
      case (Some(_), None)    => true
      case (None, Some(_))    => true
      case (None, None)       => false
    }
}
