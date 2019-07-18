/*
 * Copyright 2019 HM Revenue & Customs
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

import com.google.inject.Inject
import com.kenshoo.play.metrics.Metrics
import play.api.Logger

import scala.concurrent.duration

class ApplicationMetrics @Inject()(metrics: Metrics) {
  lazy val registry = metrics.defaultRegistry

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

  def processRequest(diff: Long, unit: duration.TimeUnit): Unit = registry.timer("processRequest-timer").update(diff, unit)

  def registerSuccessfulRequest() = registry.counter("des-connector-requests-successful").inc()

  def registerFailedRequest() = registry.counter("des-connector-requests-failed").inc()

  def registerStatusCode(code: String) = registry.counter(s"des-connector-httpstatus-$code").inc()

  def desConnectionTime(diff: Long, timeUnit: TimeUnit) = registry.timer("des-connector-timer").update(diff, timeUnit)

  def insertResponseByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-insertResponseByReference-timer").update(diff, unit)

  def findRequestsToProcessTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findRequestsToProcess-timer").update(diff, unit)

  def findCountRemainingTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findCountRemaining-timer").update(diff, unit)

  def findByUserIdTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findByUserId-timer").update(diff, unit)

  def insertBulkDocumentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-insertBulkDocument-timer").update(diff, unit)

  def findAndCompleteTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndComplete-timer").update(diff, unit)

  def findSummaryByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findSummaryByReference-timer").update(diff, unit)

  def findByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findByReference-timer").update(diff, unit)

  def findAndCompleteChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndCompleteChildren-timer").update(diff, unit)

  def findAndCompleteParentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndCompleteParent-timer").update(diff, unit)

  def findAndCompleteAllChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    registry.timer("mongo-findAndCompleteAllChildren-timer").update(diff, unit)

  def mciConnectionTimer(diff: Long, unit: TimeUnit) =
    registry.timer("mci-connection-timer").update(diff, unit)

  def mciLockResult() =
    registry.counter("mci-lock-result-count").inc()

  def mciErrorResult() = registry.counter("mci-error-result-count").inc()

}
