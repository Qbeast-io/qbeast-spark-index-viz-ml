/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.delta.writer

import io.qbeast.core.model.{CubeId, TableChanges, Weight}
import io.qbeast.spark.index.QbeastColumns
import io.qbeast.spark.utils.{State, TagUtils}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapred.{JobConf, TaskAttemptContextImpl, TaskAttemptID}
import org.apache.hadoop.mapreduce.TaskType
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.execution.datasources.{
  OutputWriter,
  OutputWriterFactory,
  WriteJobStatsTracker,
  WriteTaskStatsTracker
}
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.SerializableConfiguration

import java.util.UUID

/**
 * BlockWriter is in charge of writing the qbeast data into files
 *
 * @param dataPath       path of the table
 * @param schema         schema of the original data
 * @param schemaIndex    schema with qbeast metadata columns
 * @param factory        output writer factory
 * @param serConf        configuration to serialize the data
 * @param qbeastColumns  qbeast metadata columns
 * @param tableChanges     the revision of the data to write
 */
case class BlockWriter(
    dataPath: String,
    schema: StructType,
    schemaIndex: StructType,
    factory: OutputWriterFactory,
    serConf: SerializableConfiguration,
    statsTrackers: Seq[WriteJobStatsTracker],
    qbeastColumns: QbeastColumns,
    tableChanges: TableChanges)
    extends Serializable {

  /**
   * Writes rows in corresponding files
   *
   * @param iter iterator of rows
   * @return the sequence of files added
   */
  def writeRow(iter: Iterator[InternalRow]): Iterator[(AddFile, TaskStats)] = {
    if (!iter.hasNext) {
      return Iterator.empty
    }
    val revision = tableChanges.updatedRevision
    iter
      .foldLeft[Map[CubeId, BlockContext]](Map()) { case (blocks, row) =>
        val cubeId = revision.createCubeId(row.getBinary(qbeastColumns.cubeColumnIndex))
        // TODO make sure this does not compromise the structure of the index
        // It could happen than estimated weights
        // doesn't include all the cubes present in the final indexed dataframe
        // we save those newly added leaves with the max weight possible

        val state = tableChanges.cubeState(cubeId).getOrElse(State.FLOODED)
        val maxWeight = tableChanges.cubeWeights(cubeId).getOrElse(Weight.MaxValue)
        val blockCtx = blocks.getOrElse(cubeId, buildWriter(cubeId, state, maxWeight))

        // The row with only the original columns
        val cleanRow = Seq.newBuilder[Any]
        cleanRow.sizeHint(row.numFields)
        for (i <- 0 until row.numFields) {
          if (!qbeastColumns.contains(i)) {
            cleanRow += row.get(i, schemaIndex(i).dataType)
          }
        }

        // Get the weight of the row to compute the minimumWeight per block
        val rowWeight = Weight(row.getInt(qbeastColumns.weightColumnIndex))

        // Writing the data in a single file.
        val internalRow = InternalRow.fromSeq(cleanRow.result())
        blockCtx.writer.write(internalRow)
        blockCtx.blockStatsTracker.foreach(
          _.newRow(blockCtx.path.toString, internalRow)
        ) // Update statsTrackers
        blocks.updated(cubeId, blockCtx.update(rowWeight))

      }
      .values
      .flatMap {
        case BlockContext(blockStats, _, _, _) if blockStats.elementCount == 0 =>
          Iterator.empty // Do nothing, this  is a empty partition
        case BlockContext(
              BlockStats(cube, maxWeight, minWeight, state, rowCount),
              writer,
              path,
              blockStatsTracker) =>
          val tags = Map(
            TagUtils.cube -> cube,
            TagUtils.minWeight -> minWeight.value.toString,
            TagUtils.maxWeight -> maxWeight.value.toString,
            TagUtils.state -> state,
            TagUtils.revision -> revision.revisionID.toString,
            TagUtils.elementCount -> rowCount.toString)

          writer.close()

          // Process final stats
          blockStatsTracker.foreach(_.closeFile(path.toString))
          val endTime = System.currentTimeMillis()
          val finalStats = blockStatsTracker.map(_.getFinalStats(endTime))
          val taskStats = TaskStats(finalStats, endTime)

          // Process file status
          val fileStatus = path
            .getFileSystem(serConf.value)
            .getFileStatus(path)

          val addFile = AddFile(
            path = path.getName(),
            partitionValues = Map(),
            size = fileStatus.getLen,
            modificationTime = fileStatus.getModificationTime,
            dataChange = true,
            stats = "",
            tags = tags)

          Iterator((addFile, taskStats))

      }
  }.toIterator

  /*
   * Creates the context to write a new cube in a new file and collect stats
   * @param cubeId a cube identifier
   * @param state the status of cube
   * @return
   */
  private def buildWriter(cubeId: CubeId, state: String, maxWeight: Weight): BlockContext = {
    val blockStatsTracker = statsTrackers.map(_.newTaskInstance())
    val writtenPath = new Path(dataPath, s"${UUID.randomUUID()}.parquet")
    val writer: OutputWriter = factory.newInstance(
      writtenPath.toString,
      schema,
      new TaskAttemptContextImpl(
        new JobConf(serConf.value),
        new TaskAttemptID("", 0, TaskType.REDUCE, 0, 0)))
    blockStatsTracker.foreach(_.newFile(writtenPath.toString)) // Update stats trackers
    BlockContext(
      BlockStats(cubeId.string, state, maxWeight),
      writer,
      writtenPath,
      blockStatsTracker)
  }

  /*
   * Container class that keeps all the mutable information we need to update a
   * block when iterating over a partition.
   * @param stats the current version of the block's stats
   * @param writer an instance of the file writer
   * @param path the path of the written file
   */
  private case class BlockContext(
      stats: BlockStats,
      writer: OutputWriter,
      path: Path,
      blockStatsTracker: Seq[WriteTaskStatsTracker])
      extends Serializable {

    def update(minWeight: Weight): BlockContext =
      this.copy(stats = stats.update(minWeight))

  }

}
