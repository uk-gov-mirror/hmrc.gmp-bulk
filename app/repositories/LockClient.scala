/*
 * Copyright 2022 HM Revenue & Customs
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

package repositories

import uk.gov.hmrc.mongo.lock.LockRepository

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration


trait LockClient {

  val lockRepository: LockRepository
  val lockId: String
  val ttl: Duration

  val ownerId = UUID.randomUUID().toString


  def tryLock(body: => Future[Unit])(implicit ec: ExecutionContext): Future[Option[Unit]] =
    (for {
      acquired <- lockRepository.takeLock(lockId, ownerId, ttl)
      result   <- if (acquired)
        body.flatMap(value => lockRepository.releaseLock(lockId, ownerId).map(_ => Some(value)))
      else
        Future.successful(None)
    } yield {
      result
    }
      ).recoverWith {
      case ex => lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }
}

object LockClient {
  def apply(lockRepo: LockRepository, lId: String, ttl1: Duration): LockClient = {
    new LockClient {
      override val lockRepository: LockRepository = lockRepo
      override val lockId        : String         = lId
      override val ttl           : Duration       = ttl1
    }
  }
}
