/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.table

import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.{Row, SparkSession}

/**
 * The Analyze Table command implementation
 * @param revisionTimestamp space to optimize
 * @param indexedTable indexed table to analyze
 */
case class AnalyzeTableCommand(revisionTimestamp: Long, indexedTable: IndexedTable)
    extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    indexedTable.analyze(revisionTimestamp).map(r => Row.fromSeq(Seq(r)))

  }

}