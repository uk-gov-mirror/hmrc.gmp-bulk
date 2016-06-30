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

package config

import play.api.Play._
import uk.gov.hmrc.play.config.ServicesConfig
import scala.concurrent.duration.{FiniteDuration, Duration}

trait ApplicationConfig {
  val bulkProcessingBatchSize: Int
  val bulkProcessingInterval: FiniteDuration
  val numberOfCallsToTriggerStateChange: Int
  val unavailablePeriodDuration: Int
  val unstablePeriodDuration: Int
  val bulkCompleteInterval: FiniteDuration
  val bulkProcessingTps: Int
}

object ApplicationConfig extends ApplicationConfig with ServicesConfig {

  override lazy val bulkProcessingBatchSize = configuration.getInt(s"bulk-batch-size").getOrElse(100)
  override lazy val bulkProcessingTps = configuration.getInt(s"bulk-processing-tps").getOrElse(10)
  override lazy val bulkProcessingInterval = Duration.apply(configuration.getString(s"bulk-processing-interval").getOrElse("10 seconds")).asInstanceOf[FiniteDuration]
  override val numberOfCallsToTriggerStateChange = configuration.getInt(s"circuit-breaker.number-of-calls-to-trigger-state-change").getOrElse(10)
  override val unavailablePeriodDuration: Int = configuration.getInt(s"circuit-breaker.unavailable-period-duration").getOrElse(300)
  override val unstablePeriodDuration: Int = configuration.getInt(s"circuit-breaker.unstable-period-duration").getOrElse(60)

  override lazy val bulkCompleteInterval = Duration.apply(configuration.getString(s"bulk-complete-interval").getOrElse("10 seconds")).asInstanceOf[FiniteDuration]

}
