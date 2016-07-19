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

package actors

import java.util.concurrent.TimeUnit

import akka.actor._
import connectors.DesConnector
import metrics.Metrics
import models.{ProcessReadyCalculationRequest, CalculationResponse, GmpBulkCalculationResponse, ValidCalculationRequest}
import play.api.Logger
import repositories.BulkCalculationRepository
import uk.gov.hmrc.play.http._
import play.api.http.Status

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

trait CalculationRequestActorComponent {
  val desConnector: DesConnector
  val repository: BulkCalculationRepository
  val metrics: Metrics
}

class CalculationRequestActor extends Actor with ActorUtils {

  self: CalculationRequestActorComponent =>

  override def receive: Receive = {
    case request: ProcessReadyCalculationRequest => {

      val origSender = sender
      val startTime = System.currentTimeMillis()
      val tryCallingDes = Try {
        desConnector.calculate(request.validCalculationRequest)
      }

      tryCallingDes match {
        case Success(successfulCall) => {
          successfulCall.map {
            case x: CalculationResponse => {
              repository.insertResponseByReference(request.bulkId, request.lineId, GmpBulkCalculationResponse.createFromCalculationResponse(x)).map {

                result => {
                  // $COVERAGE-OFF$
                  metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
                  Logger.debug(s"[CalculationRequestActor] InsertResponse : $result")
                  // $COVERAGE-ON$
                  origSender ! result
                }
              }
            }
          }.recover {

            case e: Upstream4xxResponse if e.reportAs == Status.BAD_REQUEST => {

              // Record the response as a failure, which will help out with cyclic processing of messages
              repository.insertResponseByReference(request.bulkId, request.lineId,
                GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true)).map { result =>

                origSender ! akka.actor.Status.Failure(e)

              }.recover {
                case e: Exception => {
                  // $COVERAGE-OFF$
                  Logger.debug(s"[CalculationRequestActor][Inserting Failure response failed with error : { exception : $e}]")
                  // $COVERAGE-ON$

                  origSender ! akka.actor.Status.Failure(e)
                }
              }
            }

            case e =>
              // $COVERAGE-OFF$
              Logger.debug(s"[CalculationRequestActor][Inserting Failure response failed with error : { exception : $e}]")
              origSender ! akka.actor.Status.Failure(e)
              // $COVERAGE-ON$
          }
        }

        case Failure(f) => {
          // $COVERAGE-OFF$
          Logger.debug(s"[CalculationRequestActor][Calling DES failed with error ${f.getMessage}]")
          // $COVERAGE-ON$
        }

      }
    }

    case STOP => {
      // $COVERAGE-OFF$
      Logger.debug(s"[CalculationRequestActor] stop message")
      Logger.debug("sender: " + sender.getClass)
      // $COVERAGE-ON$
      sender ! STOP
    }


    case e => {
      // $COVERAGE-OFF$
      Logger.debug(s"[CalculationRequestActor] Invalid Message : { message : $e}")
      Logger.debug("sender: " + sender.getClass)
      // $COVERAGE-ON$
      sender ! akka.actor.Status.Failure(new RuntimeException(s"invalid message: $e"))
    }

  }
}

class DefaultCalculationRequestActor extends CalculationRequestActor with DefaultCalculationRequestComponent

trait DefaultCalculationRequestComponent extends CalculationRequestActorComponent {
  // $COVERAGE-OFF$
  override val desConnector = DesConnector
  override val repository = BulkCalculationRepository()
  override val metrics = Metrics
  // $COVERAGE-ON$
}

object CalculationRequestActor {
  // $COVERAGE-OFF$
  def props = Props(classOf[DefaultCalculationRequestActor])

  // $COVERAGE-ON$
}
