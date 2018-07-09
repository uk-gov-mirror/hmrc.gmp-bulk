/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.play.graphite.MicroserviceMetrics
//import com.kenshoo.play.metrics.MetricsRegistry
import repositories.BulkCalculationRepository
import scala.concurrent.{duration, Await}
import scala.concurrent.duration._
import play.api.Logger
import uk.gov.hmrc.play.graphite.MicroserviceMetrics

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

  def registerSuccessfulRequest()

  def registerFailedRequest()

  def registerStatusCode(code: String)

  def desConnectionTime(delta: Long, timeUnit: TimeUnit)

  def mciConnectionTimer(diff: Long, unit: TimeUnit): Unit

  def mciLockResult(): Unit

  def mciErrorResult(): Unit
}

trait MetricsGauge extends Gauge[Int] {
  def name: String
}

object Metrics extends Metrics with MicroserviceMetrics {
  val registry = metrics.defaultRegistry

  private val timer = (name: String) => registry.timer(name)
  private val counter = (name: String) => registry.counter(name)

  Logger.info("[Metrics][constructor] Preloading metrics keys")

  Seq(
    ("processRequest-timer", timer),
    ("des-connector-requests-successful", counter),
    ("des-connector-requests-failed", counter),
    ("des-connector-httpstatus-200", counter),
    ("des-connector-httpstatus-400", counter),
    ("des-connector-httpstatus-422", counter),
    ("des-connector-httpstatus-500", counter),
    ("des-connector-httpstatus-504", counter),
    ("des-connector-timer", timer),
    ("mongo-insertResponseByReference-timer", timer),
    ("mongo-findRequestsToProcess-timer", timer),
    ("mongo-findCountRemaining-timer", timer),
    ("mongo-findByUserId-timer", timer),
    ("mongo-insertBulkDocument-timer", timer),
    ("mongo-findAndComplete-timer", timer),
    ("mongo-findSummaryByReference-timer", timer),
    ("mongo-findByReference-timer", timer),
    ("mongo-findAndCompleteChildren-timer", timer),
    ("mongo-findAndCompleteParent-timer", timer),
    ("mongo-findAndCompleteAllChildren-timer", timer),
    ("mci-connection-timer", timer),
    ("mci-lock-result-count", counter),
    ("mci-error-result-count", counter)
  ) foreach { t => t._2(t._1) }

//  val bulkGauge = MetricsRegistry.defaultRegistry.register("bulk-remaining",
//    new Gauge[Int] {
//      val repository = BulkCalculationRepository()
//
//      override def getValue: Int = {
//        val x = Await.result(repository.findCountRemaining, 3 seconds).getOrElse(0)
//        Logger.info(s"[Metrics][bulkGauge]: $x")
//        x
//      }
//    })

  override def processRequest(diff: Long, unit: duration.TimeUnit): Unit = registry.timer("processRequest-timer").update(diff, unit)

  override def registerSuccessfulRequest() = registry.counter("des-connector-requests-successful").inc()

  override def registerFailedRequest() = registry.counter("des-connector-requests-failed").inc()

  override def registerStatusCode(code: String) = registry.counter(s"des-connector-httpstatus-$code").inc()

  override def desConnectionTime(diff: Long, timeUnit: TimeUnit) = registry.timer("des-connector-timer").update(diff, timeUnit)

  override def insertResponseByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-insertResponseByReference-timer").update(diff, unit)

  override def findRequestsToProcessTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findRequestsToProcess-timer").update(diff, unit)

  override def findCountRemainingTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findCountRemaining-timer").update(diff, unit)

  override def findByUserIdTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findByUserId-timer").update(diff, unit)

  override def insertBulkDocumentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-insertBulkDocument-timer").update(diff, unit)

  override def findAndCompleteTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndComplete-timer").update(diff, unit)

  override def findSummaryByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findSummaryByReference-timer").update(diff, unit)

  override def findByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findByReference-timer").update(diff, unit)

  override def findAndCompleteChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndCompleteChildren-timer").update(diff, unit)

  override def findAndCompleteParentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndCompleteParent-timer").update(diff, unit)

  override def findAndCompleteAllChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndCompleteAllChildren-timer").update(diff, unit)

  override def mciConnectionTimer(diff: Long, unit: TimeUnit) =
    registry.timer("mci-connection-timer").update(diff, unit)

  override def mciLockResult() =
    registry.counter("mci-lock-result-count").inc()

  override def mciErrorResult() = registry.counter("mci-error-result-count").inc()

}
