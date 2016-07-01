/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package metrics

import java.util.concurrent.TimeUnit


import com.codahale.metrics.Gauge
import com.kenshoo.play.metrics.MetricsRegistry
import repositories.BulkCalculationRepository
import scala.concurrent.{duration, Await}
import scala.concurrent.duration._

trait Metrics {
  def findAndCompleteChildrenTimer(l: Long, MILLISECONDS: duration.TimeUnit)
  def findAndCompleteAllChildrenTimer(l: Long, MILLISECONDS: duration.TimeUnit)
  def findAndCompleteParentTimer(l: Long, MILLISECONDS: duration.TimeUnit)
  def insertResponseByReferenceTimer(diff: Long, unit: TimeUnit): Unit
  def findByReferenceTimer(diff: Long, unit: TimeUnit): Unit
  def findSummaryByReferenceTimer(diff: Long, unit: TimeUnit): Unit
  def findByUserIdTimer(diff: Long, unit: TimeUnit): Unit
  def findRequestsToProcessTimer(diff: Long, unit: TimeUnit): Unit
  def findCountRemainingTimer(diff: Long, unit: TimeUnit): Unit
  def findAndCompleteTimer(diff: Long, unit: TimeUnit): Unit
  def insertBulkDocumentTimer(diff: Long, unit: TimeUnit): Unit
  def processRequest(diff: Long, unit: TimeUnit): Unit
  def registerSuccessfulRequest
  def registerFailedRequest
  def registerStatusCode(code: String)
  def desConnectionTime(delta: Long, timeUnit: TimeUnit)
}

trait MetricsGauge extends Gauge[Int] {
  def name: String
}

object Metrics extends Metrics {

  val bulkGauge = MetricsRegistry.defaultRegistry.register("bulk-remaining",
    new Gauge[Int] {
      val repository = BulkCalculationRepository()
      override def getValue: Int = {
        Await.result(repository.findCountRemaining, 3 seconds).getOrElse(0)
      }
    })

  override def processRequest(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("processRequest-timer").update(diff, unit)

  override def registerSuccessfulRequest =
    MetricsRegistry.defaultRegistry.counter("des-connector-requests-successful").inc()

  override def registerFailedRequest =
    MetricsRegistry.defaultRegistry.counter("des-connector-requests-failed").inc()

  override def registerStatusCode(code: String) =
    MetricsRegistry.defaultRegistry.counter(s"des-connector-httpstatus-$code").inc()

  override def desConnectionTime(delta: Long, timeUnit: TimeUnit) =
    MetricsRegistry.defaultRegistry.timer("des-connector-timer").update(delta, timeUnit)

  override def insertResponseByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-insertResponseByReference-timer").update(diff, unit)

  override def findRequestsToProcessTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findRequestsToProcess-timer").update(diff, unit)

  override def findCountRemainingTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findCountRemaining-timer").update(diff, unit)

  override def findByUserIdTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findByUserId-timer").update(diff, unit)

  override def insertBulkDocumentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-insertBulkDocument-timer").update(diff, unit)

  override def findAndCompleteTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findAndComplete-timer").update(diff, unit)

  override def findSummaryByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findSummaryByReference-timer").update(diff, unit)

  override def findByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findByReference-timer").update(diff, unit)

  override def findAndCompleteChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findAndCompleteChildren-timer").update(diff, unit)

  override def findAndCompleteParentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findAndCompleteParent-timer").update(diff, unit)

  override def findAndCompleteAllChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    MetricsRegistry.defaultRegistry.timer("mongo-findAndCompleteAllChildren-timer").update(diff, unit)
}
