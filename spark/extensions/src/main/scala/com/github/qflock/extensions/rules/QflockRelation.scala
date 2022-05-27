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
package com.github.qflock.extensions.rules

import scala.collection.mutable.ListBuffer

import org.apache.spark.Partition
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession, SQLContext}
import org.apache.spark.sql.sources.{BaseRelation, TableScan}
import org.apache.spark.sql.types.StructType


case class QflockRelation(override val schema: StructType,
                          parts: Array[Partition],
                          options: Map[String, String])(@transient val sparkSession: SparkSession)
  extends BaseRelation with TableScan {

  override def sqlContext: SQLContext = sparkSession.sqlContext

  override def buildScan(): RDD[Row] = {
    val records = new ListBuffer[Row]
    sqlContext.sparkContext.makeRDD(records.toList)
  }

  override def toString: String = {
    // val partitioningInfo = if (parts.nonEmpty) s" [numPartitions=${parts.length}]" else ""
    // credentials should not be included in the plan output, table information is sufficient.
    val cols = schema.fields.map(_.name).mkString(",")
    s"QFlockRelation(${schema.fields.length} $cols)"
  }
}
