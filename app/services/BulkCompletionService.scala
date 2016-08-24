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

package services

import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoPlugin
import repositories.BulkCalculationRepository
import uk.gov.hmrc.lock.{LockRepository, LockKeeper, LockMongoRepository}
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.{ExecutionContext, Future}

trait BulkCompletionService {

  val connection = {
    import play.api.Play.current
    ReactiveMongoPlugin.mongoConnector.db
  }

  val lockrepo = LockMongoRepository(connection)
  val lockKeeper = new LockKeeper {

    override def repo: LockRepository = lockrepo //The repo created before

    override def lockId: String = "bulkcompletion"

    override val forceLockReleaseAfter: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)

    // $COVERAGE-OFF$
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
      Logger.info("Trying to get completion lock")
      repo.lock(lockId, serverId, forceLockReleaseAfter)
        .flatMap { acquired =>
          if (acquired)
            body.map { x => Logger.info("Got completion lock"); Some(x) }.recover {
              case e => Logger.error("Exception getting lock: " + e.getMessage, e)
                throw e
            }
          else {
            Logger.info("Couldnt get lock");
            Future.successful(None)
          }
        }.recoverWith { case ex => {
        Logger.error("Exception getting lock: " + ex.getMessage, ex)
        repo.releaseLock(lockId, serverId).flatMap(_ => Future.failed(ex))
      }
      }
    }

    // $COVERAGE-ON$
  }

  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = BulkCalculationRepository()
  // $COVERAGE-ON$


  def checkForComplete() = {

    Logger.info("[BulkCompletionService] Starting..")

    lockKeeper.tryLock {
      Logger.info("[BulkCompletionService] Got lock")
      repository.findAndComplete().map {
        case true => Logger.info("[BulkCompletionService] Found and completed successfully"); lockrepo.releaseLock(lockKeeper.lockId, lockKeeper.serverId)
        case false => Logger.warn("[BulkCompletionService] Failed"); lockrepo.releaseLock(lockKeeper.lockId, lockKeeper.serverId)
      }
    }.map {
      case Some(thing) => Logger.info("[BulkCompletionService][receive] Obtained mongo lock")
      // $COVERAGE-OFF$
      case _ => Logger.info("[BulkCompletionService][receive] Failed to obtain mongo lock")
      // $COVERAGE-ON$
    }
  }
}

object BulkCompletionService extends BulkCompletionService
