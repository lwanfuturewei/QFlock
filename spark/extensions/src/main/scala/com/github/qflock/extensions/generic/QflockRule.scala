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
package com.github.qflock.rules

import java.io.FileWriter
import java.util
import java.util.HashMap

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversions.`map AsScala`
import scala.collection.mutable
import scala.sys.process._
import scala.util.{Either, Left => EitherLeft, Right => EitherRight}

import com.github.qflock.extensions.common.{PushdownJson, PushdownJsonStatus, PushdownSQL, PushdownSqlStatus}
import com.github.qflock.extensions.generic.GenericPushdownScan
import com.github.qflock.extensions.rules.{QflockFilter, QflockLogicalRelation, QflockLogicalRelationWithStats, QflockRelation}
import org.apache.hadoop.hive.ql.metadata.Hive
import org.apache.hadoop.hive.ql.session.SessionState
import org.json._
import org.slf4j.LoggerFactory

import org.apache.spark.{Partition, SparkFiles}
import org.apache.spark.sql.{SparkSession, SparkSessionExtensions}
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.planning.ScanOperation
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter => LogicalFilter, LogicalPlan, Project, Statistics}
import org.apache.spark.sql.catalyst.plans.logical.statsEstimation.BasicStatsPlanVisitor
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, LogicalRelation}
import org.apache.spark.sql.execution.datasources.v2.{DataSourceV2Relation, DataSourceV2ScanRelation}
import org.apache.spark.sql.execution.datasources.v2.parquet.ParquetScan
import org.apache.spark.sql.hive.client.HiveClientImpl
import org.apache.spark.sql.hive.extension.ExtHiveUtils
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types._
import org.apache.spark.sql.util._



case class QflockRule(spark: SparkSession) extends Rule[LogicalPlan] {
  protected val appId = spark.sparkContext.applicationId
  private def mapAttribute(origExpression: Any,
                           newProject: Seq[NamedExpression]) : Any = {
    origExpression match {
      case a @ Alias(child, name) =>
        new Alias(mapAttribute(child, newProject).asInstanceOf[Expression],
          name)(a.exprId, a.qualifier, a.explicitMetadata, a.nonInheritableMetadataKeys)
      case Cast(expression, dataType, timeZoneId, _) =>
        new Cast(mapAttribute(expression, newProject).asInstanceOf[NamedExpression],
          dataType, timeZoneId)
      case AttributeReference(name, dataType, nullable, meta) =>
        newProject.find(_.name == name).get
      case default => throw new Exception(s"Unknown: ${default}")
    }
  }
  private def getAttributeValues(origExpression: Any) : (String, String) = {
    origExpression match {
      case a @ Alias(child, name) =>
        getAttributeValues(child)
      case Cast(expression, dataType, timeZoneId, _) =>
        getAttributeValues(expression)
      case AttributeReference(name, dataType, nullable, meta) =>
        (name, dataType.toString)
      case default => throw new Exception(s"Unknown: ${default}")
    }
  }
  private def getAttribute(origExpression: Any) : Either[String, AttributeReference] = {
    origExpression match {
      case Alias(child, name) =>
        getAttribute(child)
      case Cast(expression, dataType, timeZoneId, _) =>
        getAttribute(expression)
      case attrib @ AttributeReference(name, dataType, nullable, meta) =>
        EitherRight(attrib)
      case default => EitherLeft("Unknown Attribute: " + default)
    }
  }
  private def getAttributeReferences(project: Seq[NamedExpression]):
  Either[String, Seq[AttributeReference]] = {
    var failed = false
    val attributes = project.flatMap {x =>
      getAttribute(x) match {
        case EitherLeft(l) => logger.info(l)
          failed = true
          Seq[AttributeReference]()
        case EitherRight(r) =>
          Seq(r)
      }
    }
    if (failed) {
      EitherLeft("Failed attribute references.")
    } else {
      EitherRight(attributes)
    }
  }
  private def getJsonSchema(params: String) : StructType = {
    var newSchema: StructType = new StructType()
    try {
      val jsonObject = new JSONObject(params)
      logger.info("Processor found: " + jsonObject.getString("processor"))
      val schemaJson = jsonObject.getJSONArray("schema")
      for (i <- 0 until schemaJson.length()) {
        val field = schemaJson.getJSONObject(i)
        val dataType = field.getString("type") match {
          case "StringType" => StringType
          case "IntegerType" => IntegerType
          case "DoubleType" => DoubleType
          case "LongType" => LongType
        }
        newSchema = newSchema.add(field.getString("name"), dataType, true)
      }
    } catch {
      case err: JSONException =>
        logger.error("Error " + err.toString())
    }
    newSchema
  }

  def getFilterAttributes(filters: Seq[Expression]): Either[String, Seq[AttributeReference]] = {
    var failed = false
    val attributes = filters.flatMap(f => {
      val attrSeq = getFilterExpressionAttributes(f)
      if (attrSeq.length == 0) {
        failed = true
      }
      attrSeq
    })
    if (failed) {
      EitherLeft("Failed getting filter expr attributes")
    } else {
      EitherRight(attributes)
    }
  }
  def getFilterExpressionAttributes(filter: Expression): Seq[AttributeReference] = {
    filter match {
      case attrib @ AttributeReference(name, dataType, nullable, meta) =>
        Seq(attrib)
      case Cast(expression, dataType, timeZoneId, _) =>
        getFilterExpressionAttributes(expression)
      case Or(left, right) => getFilterExpressionAttributes(left) ++
        getFilterExpressionAttributes(right)
      case And(left, right) => getFilterExpressionAttributes(left) ++
        getFilterExpressionAttributes(right)
      case Not(filter) => getFilterExpressionAttributes(filter)
      case In(attr, list) => getFilterExpressionAttributes(attr)
      case EqualTo(attr, value) => getFilterExpressionAttributes(attr)
      case LessThan(attr, value) => getFilterExpressionAttributes(attr)
      case GreaterThan(attr, value) => getFilterExpressionAttributes(attr)
      case LessThanOrEqual(attr, value) => getFilterExpressionAttributes(attr)
      case GreaterThanOrEqual(attr, value) => getFilterExpressionAttributes(attr)
      case IsNull(attr) => getFilterExpressionAttributes(attr)
      case IsNotNull(attr) => getFilterExpressionAttributes(attr)
      case StartsWith(left, right) => getFilterExpressionAttributes(left)
      case EndsWith(left, right) => getFilterExpressionAttributes(left)
      case Contains(left, right) => getFilterExpressionAttributes(left)
      case other@_ => logger.warn("unknown filter:" + other) ; Seq[AttributeReference]()
    }
  }
  private def needsRule(project: Seq[NamedExpression],
                        filters: Seq[Expression],
                        child: Any): Boolean = {
//    if (project.length > 0 || filters.length > 0) {
//      true
//    } else {
    child match {
      case DataSourceV2ScanRelation(relation, scan, output) =>
        (!scan.isInstanceOf[GenericPushdownScan])
      case qlr@QflockLogicalRelation(relation, output, table, _) =>
        relation match {
          // If we injected it just for size estimates, allow it to continue.
          case q@QflockRelation(_, _, _) if (qlr.isEstimate) => true
          case q@QflockRelation(_, _, _) => false
          case _ => true
        }
      case LogicalRelation(relation, output, table, _) => true
      case _ => false
    }
  }
  case class RelationArgs(relation: Any, scan: Any, output: Seq[AttributeReference],
                          dataSchema: StructType, readSchema: StructType,
                          options: CaseInsensitiveStringMap,
                          catalogTable: Option[CatalogTable])

  object RelationArgs {
    def apply(child: Any): Option[RelationArgs] = {
      val (relation, scan, output, catalogTable) = child match {
        case DataSourceV2ScanRelation(relation, scan, output) =>
          (relation, scan, output, None)
        case LogicalRelation(relation, output, table, streaming) =>
          (relation, relation, output, table)
      }
      val (dataSchema, readSchema, options) = scan match {
        case ParquetScan(_, _, _, dataSchema, readSchema, _, _, opts, _, _) =>
          (dataSchema, readSchema, opts)
        /* case GenericPushdownScan(schema, schema, opts, _) =>
          (schema, opts) */
        case HadoopFsRelation(location, partitionSchema, dataSchema, _, fileFormat, opts) =>
          (dataSchema, dataSchema, new CaseInsensitiveStringMap(opts.asJava))
        case QflockRelation(schema, parts, opts) =>
          (schema, schema, new CaseInsensitiveStringMap(opts.asJava))
      }
      Some(new RelationArgs(relation, scan, output, dataSchema, readSchema, options, catalogTable))
    }
  }
  def canHandlePlan(project: Seq[NamedExpression],
                    filters: Seq[Expression],
                    child: Any,
                    alwaysInject: Boolean = true): Boolean = {
    val relationArgsOpt = RelationArgs(child)
    if (relationArgsOpt == None) {
      return false
    }
    val relationArgs = relationArgsOpt.get
    //    if (relationArgs.dataSchema == relationArgs.readSchema) {
    //      logger.warn("Plan not modified. No Project Necessary. " +
    //        relationArgs.options.get("currenttest"))
    //      return false
    //    }
    val attrReferencesEither = getAttributeReferences(project)
    if (attrReferencesEither.isLeft) {
      logger.warn("Plan not modified due to project")
      alwaysInject
    } else {
      val filterReferencesEither = getFilterAttributes(filters)
      if (filterReferencesEither.isLeft) {
        logger.warn("Plan not modified due to filter")
        alwaysInject
      } else if (!filters.exists(x => !x.isInstanceOf[IsNotNull])) {
        // We only pushdown if there are some filters that are not (IsNotNull).
        logger.warn("Plan has no filters ")
        relationArgs.scan match {
            case QflockRelation(schema, parts, opts) => false
            case _ => alwaysInject
        }
      } else {
        true
      }
    }
  }
  private def getNdpRelationOld(path: String, processorId: String): Option[DataSourceV2Relation] = {
    val df = spark.read
      .format("genericPushdown")
      .option("format", "parquet")
      .option("outputFormat", "binary")
      .load(path)
    val logicalPlan = df.queryExecution.optimizedPlan
    logicalPlan match {
      case s@ScanOperation(project,
      filters,
      child: DataSourceV2ScanRelation) =>
        child match {
          case DataSourceV2ScanRelation(relation, scan, output) =>
            Some(relation)
          case _ => None
        }
      case _ => None
    }
  }
  private def getNdpRelation(path: String, processorId: String): Option[BaseRelation] = {
    val df = spark.read
      .format("qflock")
      .option("format", "parquet")
      .option("outputFormat", "binary")
      .load(path)
    val logicalPlan = df.queryExecution.optimizedPlan
    logicalPlan match {
      case s@ScanOperation(project,
      filters,
      child: LogicalRelation) =>
        child match {
          case LogicalRelation(relation, output, _, _) =>
            Some(relation)
          case _ => None
        }
      case _ => None
    }
  }
  private def transformProject(plan: LogicalPlan,
                               project: Seq[NamedExpression],
                               filters: Seq[Expression],
                               child: LogicalPlan)
  : LogicalPlan = {
    val relationArgs = RelationArgs(child).get
    val attrReferencesEither = getAttributeReferences(project)

    val attrReferences = attrReferencesEither match {
      case EitherRight(r) => r
      case EitherLeft(l) => Seq[AttributeReference]()
    }
    val filterReferencesEither = getFilterAttributes(filters)
    val filterReferences = filterReferencesEither match {
      case EitherRight(r) => r
      case EitherLeft(l) => Seq[AttributeReference]()
    }
    val opt = new util.HashMap[String, String](relationArgs.options)
    val path = opt.get("path") // .replaceFirst("hdfs://.*:9000/", "hdfs://dikehdfs:9860/")
    opt.put("path", path)
    opt.put("format", "parquet")
    opt.put("outputFormat", "binary")
    val filtersStatus = PushdownSqlStatus.FullyValid
//    {
//      if (relationArgs.options.containsKey("ndpdisablefilterpush")) {
//        PushdownSqlStatus.Invalid
//      } else PushdownSQL.validateFilters(filters)
//    }
    var references = {
      filtersStatus match {
        case PushdownSqlStatus.Invalid =>
          (attrReferences ++ filterReferences).distinct
        case PushdownSqlStatus.PartiallyValid =>
          (attrReferences ++ filterReferences).distinct
        case PushdownSqlStatus.FullyValid =>
          attrReferences.distinct
      }
    }
    var cols = references.toStructType.fields.map(x => s"" + s"${x.name}").mkString(",")

   /* The below allows us to log the available filters
    * for pushdown, even if we currently do not push these down.
    * These get logged to filters.txt, along with the
    * projects and the Spark view of the filters too.
    */
    if (false) {
      val filtersJson = PushdownJson.getFiltersJsonMaxDesired(filters, "")
      val fw = new FileWriter("/build/filters.txt", true)
      try {
        fw.write("Pushdown Filters " + filters.mkString(", ") + "\n")
        fw.write("Pushdown Projects " + cols + "\n")
        fw.write("Pushdown Filter Json " + filtersJson + "\n")
      }
      finally fw.close()
    }
    val allRefs = (attrReferences ++ filterReferences)
    val queryCols = allRefs.distinct.toStructType.fields.map(x => s"${x.name}")
    val sqlQuery: String = {
      if (filtersStatus != PushdownSqlStatus.Invalid) {
        val pushdownSql = PushdownSQL(references.toStructType, filters, queryCols)
        val query = pushdownSql.query
        logger.warn("Pushdown query " + query)
        query
      } else {
        logger.warn("No Pushdown " + filters.toString)
        ""
      }
    }
    logger.info(s"sqlQuery: ${sqlQuery}")
//    val configString = ProcessorRequestConfig(sqlQuery).configString
//    opt.put("ndpConfig", configString)
//    opt.put("ndpprojectcolumns", cols)
    // Once we know what the file is, we will replace the FILE_TAG
    // with the actual file (for all the files we need to process).
    // val projectJson = PushdownJson.getProjectJson(cols.split(","), test)
    logger.info(s"Stats ${plan.stats}")
    val (filterCondition: Option[Expression], scanRelation: QflockLogicalRelation) =
      getScanRelation(project, filters, child, relationArgs,
                      attrReferences, filterReferences, opt, references)
    val withFilter = {
      if (filtersStatus == PushdownSqlStatus.FullyValid) {
        /* Clip the filter from the DAG, since we are going to
         * push down the entire filter to NDP.
         */
        scanRelation
      } else {
        filterCondition.map(LogicalFilter(_, scanRelation)).getOrElse(scanRelation)
      }
    }
//    plan
    if (withFilter.output != project || filters.length == 0) {
      if (project != scanRelation.output) {
        Project(project, withFilter)
      } else {
        scanRelation
      }
    } else {
      withFilter
    }
  }

  private def getScanRelation(project: Seq[NamedExpression], filters: Seq[Expression],
                              child: LogicalPlan, relationArgs: RelationArgs,
                              attrReferences: Seq[AttributeReference],
                              filterReferences: Seq[AttributeReference],
                              opt: util.HashMap[String, String],
                              references: Seq[AttributeReference]) = {
    val scalaOpts = scala.collection.immutable.HashMap(opt.toSeq: _*)
    val needsPushdown = canHandlePlan(project, filters, child, alwaysInject = false)
    val qflockRelation = new QflockRelation(references.toStructType,
      Array.empty[Partition], scalaOpts)(spark)
    val filterCondition = filters.reduceLeftOption(And)
    val (planStats, filterPlan) = {
      val qLogRel = new QflockLogicalRelationWithStats(
        relationArgs.relation.asInstanceOf[BaseRelation],
        relationArgs.output,
        relationArgs.catalogTable, false)()
      val filterPlan = filterCondition.map(QflockFilter(_, qLogRel)).getOrElse(qLogRel)
      val fStats = BasicStatsPlanVisitor.visit(filterPlan)
      val projPlan = Project(project, filterPlan)
      (BasicStatsPlanVisitor.visit(projPlan), qLogRel)
    }
    val relationSizeInBytes = {
      // Just add the size required by the filter to the size required by the project.
      val filterRelationBytesNew = if (filterReferences.length > 0) {
        val filterRelation1 = new QflockLogicalRelationWithStats(
          qflockRelation.asInstanceOf[BaseRelation],
          filterReferences,
          relationArgs.catalogTable, false)()
        val relationRowCount = BasicStatsPlanVisitor.visit(filterRelation1).rowCount
        val filterRelation = new QflockLogicalRelation(
          qflockRelation.asInstanceOf[BaseRelation],
          filterReferences,
          relationArgs.catalogTable, false)(relationRowCount, 0)
        BigInt(filterRelation.sizeInBytes)
      } else {
        // No filter needed, let's just not consider the size of the filter.
        BigInt(0L)
      }
      val (filterRelationBytes, filterRelation) = if (filterReferences.length > 0) {
        // To determine the size needed to be scanned by the filter,
        // just create a relation that has simply the columns needed by the filter.
        val filterRelation = new QflockLogicalRelation(qflockRelation.asInstanceOf[BaseRelation],
          filterReferences,
          relationArgs.catalogTable,
          false)(child.stats.rowCount, 0)
        (filterRelation.stats.sizeInBytes, filterRelation)
      } else {
        // No filter needed, let's just not consider the size of the filter.
        (BigInt(0L), 0)
      }
      // This is the size required by the project,
      // but less any columns that are also needed by the filter since
      // those columns are already included in the filter.
      val projectRelationBytesNew = {
        val qLogRel = new QflockLogicalRelation(
          qflockRelation.asInstanceOf[BaseRelation],
          attrReferences.filter(x => !filterReferences.contains(x)),
          relationArgs.catalogTable, false)(planStats.rowCount, 0)
        // BasicStatsPlanVisitor.visit(qLogRel).sizeInBytes
        qLogRel.sizeInBytes
      }
      val projectRelationBytes = {
        val projectRelation =
          new QflockLogicalRelation(qflockRelation.asInstanceOf[BaseRelation],
            attrReferences.filter(x => !filterReferences.contains(x)),
            relationArgs.catalogTable,
            false)(planStats.rowCount, 0)
        projectRelation.stats.sizeInBytes
      }
      //      if ((projectRelationBytes != projectRelationBytesNew) ||
      //          (filterRelationBytes != filterRelationBytesNew)) {
      //        logger.error(s"project ${projectRelationBytes} != ${projectRelationBytesNew} " +
      //                     s"filter ${filterRelationBytes} != ${filterRelationBytesNew} " +
      //        plan.toString)
      //      }
      projectRelationBytesNew + filterRelationBytesNew
    }
//    val tbl = fetchTable("tpcds", "store_sales")
//    logger.info(tbl.toString)
    val scanRelation = new QflockLogicalRelation(qflockRelation.asInstanceOf[BaseRelation],
      references, relationArgs.catalogTable,
      false)(planStats.rowCount,
      relationSizeInBytes, Some(!needsPushdown))
    (filterCondition, scanRelation)
  }
  private def fetchTable(dbName: String, tableName: String) = {

    // logger.info(ExtHiveUtils.test)
    ExtHiveUtils.getTable(dbName, tableName)
  }
  private def pushFilterProject(plan: LogicalPlan): LogicalPlan = {
    val newPlan = plan.transform {
      case s@ScanOperation(project,
      filters,
      child: DataSourceV2ScanRelation) if (needsRule(project, filters, child) &&
        canHandlePlan(project, filters, child)) =>
        val modified = transformProject(s, project, filters, child)
        logger.info("before pushFilterProject: \n" + project + "\n" + s)
        logger.info("after pushFilterProject: \n" + modified)
        modified
      case s@ScanOperation(project,
        filters, child: LogicalRelation) if (needsRule(project, filters, child) &&
        canHandlePlan(project, filters, child)) =>
      val modified = transformProject(s, project, filters, child)
      logger.info("before pushFilterProject: \n" + project + "\n" + s)
      logger.info("after pushFilterProject: \n" + modified)
      modified
    }
    if (newPlan != plan) {
      // newPlan.invalidateStatsCache()
      logger.info(s"new stats: ${newPlan.stats}")
      logger.info("before: \n" + plan)
      logger.info("after: \n" + newPlan)
    }
    newPlan
  }
  private def transformAggregate(groupingExpressions: Seq[Expression],
                                 aggregateExpressions: Seq[NamedExpression],
                                 child: LogicalPlan)
  : LogicalPlan = {
    val relationArgs = RelationArgs(child).get
    val aggExprToOutputOrdinal = mutable.HashMap.empty[Expression, Int]
    var ordinal = 0
    val aggregates = aggregateExpressions.flatMap { expr =>
      expr.collect {
        // Do not push down duplicated aggregate expressions. For example,
        // `SELECT max(a) + 1, max(a) + 2 FROM ...`, we should only push down one
        // `max(a)` to the data source.
        case agg: AggregateExpression
          if !aggExprToOutputOrdinal.contains(agg.canonicalized) =>
          aggExprToOutputOrdinal(agg.canonicalized) = ordinal
          ordinal += 1
          agg
      }
    }
    val schema = PushdownJson.getAggregateSchema(aggregates, groupingExpressions)
    val newOutput = schema.map(f => AttributeReference(f.name, f.dataType,
      f.nullable, f.metadata)())
    // assert(newOutput.length == groupingExpressions.length + aggregates.length)
    val groupAttrs = groupingExpressions.zip(newOutput).map {
      case (a: Attribute, b: Attribute) => b.withExprId(a.exprId)
      case (_, b) => b
    }
    val output = groupAttrs ++ newOutput.drop(groupAttrs.length)

    /* logInfo(
      s"""
          |Pushing operators to ${sHolder.relation.name}
          |Pushed Aggregate Functions:
          | ${pushedAggregates.get.aggregateExpressions.mkString(", ")}
          |Pushed Group by:
          | ${pushedAggregates.get.groupByColumns.mkString(", ")}
          |Output: ${output.mkString(", ")}
          """.stripMargin) */
    val opt = new HashMap[String, String](relationArgs.options)
    val aggregateJson = PushdownJson.getAggregateJson(groupingExpressions,
      aggregates.asInstanceOf[Seq[AggregateExpression]],
      "")
    opt.put("ndpjsonaggregate", aggregateJson)
    val nodes = Array(opt.getOrDefault("ndpjsonfilters", ""),
      aggregateJson).filter(x => x != "")
    val hdfsScanObject = new GenericPushdownScan(output.toStructType, opt)
    val scanRelation = DataSourceV2ScanRelation(
      relationArgs.scan.asInstanceOf[DataSourceV2Relation],
      hdfsScanObject, output)
    val plan = Aggregate(
      output.take(groupingExpressions.length),
      aggregateExpressions, scanRelation)
    val aggOutput = output.drop(groupAttrs.length)
    plan.transformExpressions {
      case agg: AggregateExpression =>
        val ordinal = aggExprToOutputOrdinal(agg.canonicalized)
        val aggFunction: aggregate.AggregateFunction =
          agg.aggregateFunction match {
            case max: aggregate.Max => max.copy(child = aggOutput(ordinal))
            case min: aggregate.Min => min.copy(child = aggOutput(ordinal))
            case sum: aggregate.Sum => sum.copy(child = aggOutput(ordinal))
            case _: aggregate.Count => aggregate.Sum(aggOutput(ordinal))
            case other => other
          }
        agg.copy(aggregateFunction = aggFunction)
    }
    // plan
  }
  private def aggNeedsRule(plan: LogicalPlan): Boolean = {
    plan match {
      case s@ScanOperation(project,
      filters,
      child: DataSourceV2ScanRelation) =>
        val relationScan = child match {
          case DataSourceV2ScanRelation(relation, scan, output) =>
            scan
        }
        val scanOpts = relationScan match {
          case ParquetScan(_, _, _, dataSchema, _, _, _, opts, _, _) =>
            opts
          case GenericPushdownScan(schema, opts, stats) =>
            opts
        }
        (!scanOpts.containsKey("ndpjsonaggregate") &&
          !scanOpts.containsKey("ndpdisableaggregatepush"))
      case _ => false
    }
  }
  private def aggExpressionIsValid(groupingExpressions: Seq[Expression],
                                   aggregateExpressions: Seq[NamedExpression]): Boolean = {
    def aggValidateExpression(expr: Expression): Boolean = {
      expr match {
        case attrib @ AttributeReference(name, dataType, nullable, meta) =>
          true
        case Literal(value, dataType) =>
          true
        case s @ ScalaUDF(function, dataType, children, inputEncoders, outputEncoder,
        udfName, nullable, udfDeterministic) =>
          // logger.info(s"$function, $dataType, $children, " +
          //              "$inputEncoders, $outputEncoder, $udfName")
          logger.info("aggregate UDF not supported")
          false
        /* case If(predicate, trueValue, falseValue) =>
          // aggValidateExpression(trueValue) && aggValidateExpression(falseValue)
          logger.info("aggregate If not supported")
          false
        case Or(left, right) =>
          // aggValidateExpression(left) && aggValidateExpression(right)
          logger.info("aggregate OR not supported")
          false
        case And(left, right) =>
          // aggValidateExpression(left) && aggValidateExpression(right)
          logger.info("aggregate And not supported")
          false */
        case mult @ Multiply(left, right, failOnError) =>
          aggValidateExpression(left) && aggValidateExpression(right)
        case div @ Divide(left, right, failOnError) =>
          aggValidateExpression(left) && aggValidateExpression(right)
        case add @ Add(left, right, failOnError) =>
          aggValidateExpression(left) && aggValidateExpression(right)
        case subtract @ Subtract(left, right, failOnError) =>
          aggValidateExpression(left) && aggValidateExpression(right)
        case other @ _ =>
          logger.info(s"aggregate ${other.toString} not supported")
          false
      }
    }
    def validateAggFunction(aggregate: AggregateExpression): Boolean = {
      if (aggregate.filter.isEmpty) {
        aggregate.aggregateFunction match {
          case Min(child: Expression) => aggValidateExpression(child)
          case Max(child: Expression) => aggValidateExpression(child)
          case count: Count if count.children.length == 1 =>
            aggValidateExpression(count.children.head)
          case sum @ Sum(child: Expression, _) => aggValidateExpression(child)
          case _ => false
        }
      } else {
        false
      }
    }
    val aggregates = aggregateExpressions.flatMap { expr =>
      expr.collect {
        // Do not push down duplicated aggregate expressions. For example,
        // `SELECT max(a) + 1, max(a) + 2 FROM ...`, we should only push down one
        // `max(a)` to the data source.
        case agg: AggregateExpression => agg
      }
    }
    !aggregates.exists(x => !validateAggFunction(x))
  }

  /**
   * Transforms a plan with a project and filter over an aggregate
   * in order to push down the operations (project, filter and aggregate)
   * down to NDP.
   *
   * @param projectTop
   * @param filtersTop
   * @param agg
   * @return LogicalPlan with the aggregate, filter and project pushed.
   */
  private def transformProjFiltAggregate(projectTop: Seq[NamedExpression],
                                         filtersTop: Seq[Expression],
                                         agg: Aggregate): LogicalPlan = {

    val (groupingExpressions, aggregateExpressions, childAgg) = agg match {
      case aggNode@Aggregate(groupingExpressions, resultExpressions, childAgg) =>
        (groupingExpressions, resultExpressions, childAgg)
    }
    val aggExprToOutputOrdinal = mutable.HashMap.empty[Expression, Int]
    var ordinal = 0
    val aggregates = aggregateExpressions.flatMap { expr =>
      expr.collect {
        // Do not push down duplicated aggregate expressions. For example,
        // `SELECT max(a) + 1, max(a) + 2 FROM ...`, we should only push down one
        // `max(a)` to the data source.
        case agg: AggregateExpression
          if !aggExprToOutputOrdinal.contains(agg.canonicalized) =>
          aggExprToOutputOrdinal(agg.canonicalized) = ordinal
          ordinal += 1
          agg
      }
    }
    val attrReferencesEither = getAttributeReferences(projectTop)
    val attrReferences = attrReferencesEither match {
      case EitherRight(r) => r
      case EitherLeft(l) => Seq[AttributeReference]()
    }
    val aggScan = childAgg match {
      case DataSourceV2ScanRelation(relation, scan, output) => scan
    }
    val aggOpts = aggScan match {
      case ParquetScan(_, _, _, dataSchema, _, _, _, opts, _, _) => opts
      case GenericPushdownScan(schema, opts, stats) => opts
    }
    val filterReferencesEither = getFilterAttributes(filtersTop)
    val filterReferences = filterReferencesEither match {
      case EitherRight(r) => r
      case EitherLeft(l) => Seq[AttributeReference]()
    }
    val opt = new HashMap[String, String](aggOpts)

    // We need to redirect to NDP vs the standard HDFS port.
    val path = opt.get("path").replaceFirst("hdfs://.*:9000/", "ndphdfs://dikehdfs/")

    // @todo can we stash ndp relation in the options??
    //       this would allow us to only generate the relation once.
    val processorId = opt.get("processorid")
    val ndpRel = getNdpRelationOld(path, processorId)

    // If the filters are valid then they will be available for pushdown.
    val filtersStatus = PushdownJson.validateFilters(filtersTop)
    /* The below disables all filter pushdown */
    // filtersStatus = PushdownJsonStatus.Invalid
    var references = {
      filtersStatus match {
        case PushdownJsonStatus.Invalid =>
          (attrReferences ++ filterReferences).distinct
        case PushdownJsonStatus.PartiallyValid =>
          (attrReferences ++ filterReferences).distinct
        case PushdownJsonStatus.FullyValid =>
          attrReferences.distinct
      }
    }
    val projectTopCols = references.toStructType.fields.map(x => s"" + s"${x.name}")
    val projectTopJson = PushdownJson.getProjectJson(projectTopCols, "")
    opt.put("ndpprojectjson", projectTopJson)

    val filtersTopJson = {
      if (filtersStatus != PushdownJsonStatus.Invalid) {
        val json = PushdownJson.getFiltersJson(filtersTop, "")
        logger.warn("Pushdown " +
          " Filter Json " + json)
        opt.put("ndpjsonfilterstop", json)
        json
      } else {
        logger.warn("No Pushdown " + filtersTop.toString)
        ""
      }
    }
    // Once we know what the file is, we will replace the FILE_TAG
    // with the actual file (for all the files we need to process).
    val aggregateJson = PushdownJson.getAggregateJson(groupingExpressions,
      aggregates.asInstanceOf[Seq[AggregateExpression]],
      "")
    // Generate a top aggregate, which does an aggregation over the aggregation
    // This step is required by NDP in order to create a barrier between the
    // trees that get individual partitions and the aggregate across all
    // partitions.
    val topAggregateJson = PushdownJson.getAggregateJson(groupingExpressions,
      aggregates.asInstanceOf[Seq[AggregateExpression]],
      "",
      topAggregate = true)

    // We need to handle aliases in the filters
    // gather all the aliases found in the aggregates.
    val aliasesMap = aggregateExpressions.flatMap(x => x match {
      case a @ Alias(child, name) =>
        PushdownJson.getAggregateName(child.asInstanceOf[AggregateExpression],
          topAggregate = true) match {
          case Some(aggString) => Some(name, aggString)
          case _ => None
        }
      case _ => None
    }).toMap
    // Apply this set of aliases to our Json of the filter Top.
    val aliasedFiltersTop = aliasesMap.foldLeft(filtersTopJson)(
      (x, y) => x.replaceAllLiterally(y._1, y._2))

    // Apply this set of aliases to our Json of the project Top.
    val aliasedProjectTop = aliasesMap.foldLeft(projectTopJson)(
      (x, y) => x.replaceAllLiterally(y._1, y._2))
    val nodeArray = Array[String](opt.getOrDefault("ndpjsonfilters", ""),
      aggregateJson, topAggregateJson,
      aliasedFiltersTop, aliasedProjectTop).filter(x => x != "")
    // val dag = ProcessorRequestDag(nodes = nodeArray).dagString
    // opt.put("ndpdag", dag)
    val hdfsScanObject = new GenericPushdownScan(references.toStructType, opt)
    val scanRelation = DataSourceV2ScanRelation(ndpRel.get, hdfsScanObject, references)
    val filterCondition = filtersTop.reduceLeftOption(And)
    val withFilter = {
      if (filtersStatus == PushdownJsonStatus.FullyValid) {
        /* Clip the filter from the DAG, since we are going to
         * push down the entire filter to NDP.
         */
        scanRelation
      } else {
        filterCondition.map(LogicalFilter(_, scanRelation)).getOrElse(scanRelation)
      }
    }
    /* If there are no filters, or the project is required then
     * we will return a project object.
     */
    if (withFilter.output != projectTop || filtersTop.length == 0) {
      Project(projectTop, withFilter)
    } else {
      withFilter
    }
  }
  private def canHandleProjFilterOverAgg(projectTop: Seq[NamedExpression],
                                         filtersTop: Seq[Expression],
                                         agg: Aggregate): Boolean = {
    /* @todo validate project, filter.
     *
     */
    agg match {
      case aggNode@Aggregate(groupingExpressions, resultExpressions, childAgg)
        if (aggExpressionIsValid(groupingExpressions, resultExpressions) &&
          aggNeedsRule(childAgg)) =>
        childAgg match {
          case s@ScanOperation(project,
          filters,
          child: DataSourceV2ScanRelation)
            if filters.isEmpty =>
            childAgg match {
              case DataSourceV2ScanRelation(relation, scan, output) => true
              case _ => false
            }
          case other =>
            false
        }
      case _ => false
    }
  }
  private def pushAggregate(plan: LogicalPlan)
  : LogicalPlan = {
    val newPlan = plan.transform {
      case s@ScanOperation(project,
      filters,
      aggregate: Aggregate)
        if (false && canHandleProjFilterOverAgg(project, filters, aggregate)) =>
        transformProjFiltAggregate(project, filters, aggregate)
      case aggNode @ Aggregate(groupingExpressions, resultExpressions, childAgg)
        if (false && aggExpressionIsValid(groupingExpressions, resultExpressions) &&
          aggNeedsRule(childAgg)) =>
        childAgg match {
          case s@ScanOperation(project,
          filters,
          child: DataSourceV2ScanRelation)
            if filters.isEmpty =>
            transformAggregate(groupingExpressions,
              resultExpressions, child)
          case r: DataSourceV2ScanRelation =>
            aggNode
          case other =>
            aggNode
        }
    }
    if (newPlan != plan) {
      logger.info("before agg pushdown: \n" + plan)
      logger.info("after agg pushdown: \n" + newPlan)
    }
    newPlan
  }
  protected val logger = LoggerFactory.getLogger(getClass)
  def apply(inputPlan: LogicalPlan): LogicalPlan = {
    // val after = pushAggregate(pushFilterProject(inputPlan))
    val after = pushFilterProject(inputPlan)
    after
  }
  def getNdpPythonString(): String = {
    val rootPath = SparkFiles.getRootDirectory() + "/pydike_venv"
    val pythonPath = "/pyNdp:/build/spark-3.2.0/python"
    val pythonExec = s"${rootPath}/bin/python"
    val sparkDriverPath = "lib/python3.8/site-packages/pydike/client/spark_driver.py"
    val pythonCmd = s"$pythonExec ${rootPath}/${sparkDriverPath}"
    val stdout = new StringBuilder
    val stderr = new StringBuilder
    val status = Process(s"$pythonCmd", None,
      "PYTHONPATH" -> pythonPath) ! ProcessLogger(stdout append _, stderr append _)
    logger.info(s"python status: $status")
    logger.info(s"python stdout: ${stdout.toString}")
    logger.info(s"python stderr: ${stderr.toString}")
    stdout.toString
  }
}

case class QflockBasicRule(spark: SparkSession) extends Rule[LogicalPlan] {
  protected val logger = LoggerFactory.getLogger(getClass)
  override def apply(plan: LogicalPlan): LogicalPlan = {
    logger.info(s"QflockRule LogicalPlan $plan")
    val fw = new FileWriter("./rules.txt", false)
    try {
      fw.write(plan.toString() + "\n")
    }
    finally fw.close()
    plan
  }
}
class QflockExtensions extends (SparkSessionExtensions => Unit) {
  def apply(e: SparkSessionExtensions): Unit = {
    e.injectOptimizerRule(QflockRule)
  }
}