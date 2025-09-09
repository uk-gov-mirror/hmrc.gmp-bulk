/*
 * Copyright 2024 HM Revenue & Customs
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

import actors.{ActorUtils, ProcessingSupervisor}
import org.apache.pekko.actor.{ActorSystem, Props}
import connectors.{DesConnector, IFConnector}

import javax.inject.{Inject, Singleton}
import metrics.ApplicationMetrics
import play.api.inject.DefaultApplicationLifecycle
import play.api.{Application, Environment}
import repositories.BulkCalculationMongoRepository
import services.BulkCompletionService
import scheduling._
import uk.gov.hmrc.mongo.lock.MongoLockRepository

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Scheduler @Inject()(override val applicationLifecycle: DefaultApplicationLifecycle,
                          actorSystem: ActorSystem,
                          env: Environment,
                          override val application: Application,
                          applicationConfiguration: ApplicationConfiguration,
                          bulkCalculationMongoRepository : BulkCalculationMongoRepository,
                          mongoApi : MongoLockRepository, bulkCompletionService : BulkCompletionService,
                          desConnector : DesConnector,
                          ifConnector: IFConnector,
                          metrics : ApplicationMetrics
                         )(implicit val ec : ExecutionContext) extends RunningOfScheduledJobs with ActorUtils {

lazy val scheduledJobs: Seq[ScheduledJob] = {
    Seq(new ExclusiveScheduledJob {
      lazy val processingSupervisor = actorSystem.actorOf(Props(
        classOf[ProcessingSupervisor],
        applicationConfiguration,
        bulkCalculationMongoRepository,
        mongoApi,
        desConnector,
        ifConnector,
        metrics
      ), "processing-supervisor")

      override def name: String = "BulkProcesssingService"

      override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
        if(!env.mode.equals("Test")) {
          processingSupervisor ! START
          Future.successful(Result("started"))
        }else {
          Future.successful(Result("not running scheduled jobs"))
        }
      }

      override def interval: FiniteDuration = applicationConfiguration.bulkProcessingInterval seconds

      override def initialDelay: FiniteDuration = 1 seconds
    },
      new ExclusiveScheduledJob {

        override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
          if(!env.equals("Test")) {
            bulkCompletionService.checkForComplete()
            Future.successful(Result("started"))
          }else {
            Future.successful(Result("not running scheduled jobs"))
          }
        }

        override def name: String = "BulkCompletionService"

        override def interval: FiniteDuration = 1 minute

        override def initialDelay: FiniteDuration = 1 seconds
      })
  }

}
