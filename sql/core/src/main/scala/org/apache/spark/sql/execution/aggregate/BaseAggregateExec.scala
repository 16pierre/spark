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

package org.apache.spark.sql.execution.aggregate

import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Final, PartialMerge}
import org.apache.spark.sql.execution.{ExplainUtils, UnaryExecNode}

/**
 * Holds common logic for aggregate operators
 */
trait BaseAggregateExec extends UnaryExecNode {
  def groupingExpressions: Seq[NamedExpression]
  def aggregateExpressions: Seq[AggregateExpression]
  def aggregateAttributes: Seq[Attribute]
  def resultExpressions: Seq[NamedExpression]

  override def verboseStringWithOperatorId(): String = {
    val inputString = child.output.mkString("[", ", ", "]")
    val keyString = groupingExpressions.mkString("[", ", ", "]")
    val functionString = aggregateExpressions.mkString("[", ", ", "]")
    val aggregateAttributeString = aggregateAttributes.mkString("[", ", ", "]")
    val resultString = resultExpressions.mkString("[", ", ", "]")
    s"""
       |(${ExplainUtils.getOpId(this)}) $nodeName ${ExplainUtils.getCodegenId(this)}
       |Input: $inputString
       |Keys: $keyString
       |Functions: $functionString
       |Aggregate Attributes: $aggregateAttributeString
       |Results: $resultString
     """.stripMargin
  }

  protected def inputAttributes: Seq[Attribute] = {
    val modes = aggregateExpressions.map(_.mode).distinct
    if (modes.contains(Final) || modes.contains(PartialMerge)) {
      // SPARK-31620: when planning aggregates, the partial aggregate uses aggregate function's
      // `inputAggBufferAttributes` as its output. And Final and PartialMerge aggregate rely on the
      // output to bind references for `DeclarativeAggregate.mergeExpressions`. But if we copy the
      // aggregate function somehow after aggregate planning, like `PlanSubqueries`, the
      // `DeclarativeAggregate` will be replaced by a new instance with new
      // `inputAggBufferAttributes` and `mergeExpressions`. Then Final and PartialMerge aggregate
      // can't bind the `mergeExpressions` with the output of the partial aggregate, as they use
      // the `inputAggBufferAttributes` of the original `DeclarativeAggregate` before copy. Instead,
      // we shall use `inputAggBufferAttributes` after copy to match the new `mergeExpressions`.
      val aggAttrs = aggregateExpressions
        // there're exactly four cases needs `inputAggBufferAttributes` from child according to the
        // agg planning in `AggUtils`: Partial -> Final, PartialMerge -> Final,
        // Partial -> PartialMerge, PartialMerge -> PartialMerge.
        .filter(a => a.mode == Final || a.mode == PartialMerge).map(_.aggregateFunction)
        .flatMap(_.inputAggBufferAttributes)
      child.output.dropRight(aggAttrs.length) ++ aggAttrs
    } else {
      child.output
    }
  }
}
