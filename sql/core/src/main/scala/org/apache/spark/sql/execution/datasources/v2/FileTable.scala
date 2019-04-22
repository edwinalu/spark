/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._

import org.apache.hadoop.fs.FileStatus

import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.sources.v2.{SupportsRead, SupportsWrite, Table, TableCapability}
import org.apache.spark.sql.sources.v2.TableCapability._
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.sql.util.SchemaUtils

abstract class FileTable(
    sparkSession: SparkSession,
    options: CaseInsensitiveStringMap,
    paths: Seq[String],
    userSpecifiedSchema: Option[StructType])
  extends Table with SupportsRead with SupportsWrite {

  lazy val fileIndex: PartitioningAwareFileIndex = {
    val caseSensitiveMap = options.asCaseSensitiveMap.asScala.toMap
    // Hadoop Configurations are case sensitive.
    val hadoopConf = sparkSession.sessionState.newHadoopConfWithOptions(caseSensitiveMap)
    val rootPathsSpecified = DataSource.checkAndGlobPathIfNecessary(paths, hadoopConf,
      checkEmptyGlobPath = true, checkFilesExist = true)
    val fileStatusCache = FileStatusCache.getOrCreate(sparkSession)
    new InMemoryFileIndex(
      sparkSession, rootPathsSpecified, caseSensitiveMap, userSpecifiedSchema, fileStatusCache)
  }

  lazy val dataSchema: StructType = userSpecifiedSchema.map { schema =>
    val partitionSchema = fileIndex.partitionSchema
    val resolver = sparkSession.sessionState.conf.resolver
    StructType(schema.filterNot(f => partitionSchema.exists(p => resolver(p.name, f.name))))
  }.orElse {
    inferSchema(fileIndex.allFiles())
  }.getOrElse {
    throw new AnalysisException(
      s"Unable to infer schema for $formatName. It must be specified manually.")
  }.asNullable

  override lazy val schema: StructType = {
    val caseSensitive = sparkSession.sessionState.conf.caseSensitiveAnalysis
    SchemaUtils.checkColumnNameDuplication(dataSchema.fieldNames,
      "in the data schema", caseSensitive)
    dataSchema.foreach { field =>
      if (!supportsDataType(field.dataType)) {
        throw new AnalysisException(
          s"$formatName data source does not support ${field.dataType.catalogString} data type.")
      }
    }
    val partitionSchema = fileIndex.partitionSchema
    SchemaUtils.checkColumnNameDuplication(partitionSchema.fieldNames,
      "in the partition schema", caseSensitive)
    val partitionNameSet: Set[String] =
      partitionSchema.fields.map(PartitioningUtils.getColName(_, caseSensitive)).toSet

    // When data and partition schemas have overlapping columns,
    // tableSchema = dataSchema - overlapSchema + partitionSchema
    val fields = dataSchema.fields.filterNot { field =>
      val colName = PartitioningUtils.getColName(field, caseSensitive)
      partitionNameSet.contains(colName)
    } ++ partitionSchema.fields
    StructType(fields)
  }

  override def capabilities(): java.util.Set[TableCapability] = FileTable.CAPABILITIES

  /**
   * When possible, this method should return the schema of the given `files`.  When the format
   * does not support inference, or no valid files are given should return None.  In these cases
   * Spark will require that user specify the schema manually.
   */
  def inferSchema(files: Seq[FileStatus]): Option[StructType]

  /**
   * Returns whether this format supports the given [[DataType]] in read/write path.
   * By default all data types are supported.
   */
  def supportsDataType(dataType: DataType): Boolean = true

  /**
   * The string that represents the format that this data source provider uses. This is
   * overridden by children to provide a nice alias for the data source. For example:
   *
   * {{{
   *   override def formatName(): String = "ORC"
   * }}}
   */
  def formatName: String

  /**
   * Returns a V1 [[FileFormat]] class of the same file data source.
   * This is a solution for the following cases:
   * 1. File datasource V2 implementations cause regression. Users can disable the problematic data
   *    source via SQL configuration and fall back to FileFormat.
   * 2. Catalog support is required, which is still under development for data source V2.
   */
  def fallbackFileFormat: Class[_ <: FileFormat]
}

object FileTable {
  private val CAPABILITIES = Set(BATCH_READ, BATCH_WRITE, TRUNCATE).asJava
}
