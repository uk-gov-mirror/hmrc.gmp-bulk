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

package actors

import config.ApplicationConfiguration
import connectors.{DesConnector, DesGetHiddenRecordResponse, IFConnector}
import metrics.ApplicationMetrics
import models.{CalculationResponse, GmpBulkCalculationResponse, ProcessReadyCalculationRequest}
import org.apache.pekko.actor._
import play.api.Logging
import play.api.http.Status
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.http.UpstreamErrorResponse

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

trait CalculationRequestActorComponent {
  val desConnector: DesConnector
  val ifConnector: IFConnector
  val repository: BulkCalculationMongoRepository
  val metrics: ApplicationMetrics
  val applicationConfig: ApplicationConfiguration
}

class CalculationRequestActor extends Actor with ActorUtils with Logging {

  self: CalculationRequestActorComponent =>

  override def receive: Receive = {

    case request: ProcessReadyCalculationRequest => {

      val origSender = sender()
      val startTime = System.currentTimeMillis()
      implicit val ec : ExecutionContext = context.dispatcher
      desConnector.getPersonDetails(request.validCalculationRequest.get.nino) map {
        case DesGetHiddenRecordResponse =>

          repository.insertResponseByReference(request.bulkId, request.lineId,
            GmpBulkCalculationResponse(List(), 423, None, None, None, containsErrors = true)).map { result =>

            origSender ! result

          }

        case _ => {

          val tryCallingDes = Try {
            if(applicationConfig.ifEnabled) {
              ifConnector.calculate(request.validCalculationRequest.get)
            } else {
              desConnector.calculate(request.validCalculationRequest.get)
            }
          }

          tryCallingDes match {
            case Success(successfulCall) => {
              successfulCall.map {
                case x: CalculationResponse => {
                  repository.insertResponseByReference(request.bulkId, request.lineId, GmpBulkCalculationResponse.createFromCalculationResponse(x)).map {

                    result => {
                      // $COVERAGE-OFF$
                      metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
                      logger.debug(s"[CalculationRequestActor] InsertResponse : $result")
                      // $COVERAGE-ON$
                      origSender ! result
                    }
                  }
                }
              }.recover {

                case e: UpstreamErrorResponse if e.reportAs == Status.BAD_REQUEST => {

                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] Inserting Failure response failed with error: $e")
                  // $COVERAGE-ON$

                  // Record the response as a failure, which will help out with cyclic processing of messages
                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true)).map { result =>

                    origSender ! result

                  }
                }

                case e =>
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] Inserting Failure response failed with error :$e")
                  origSender ! org.apache.pekko.actor.Status.Failure(e)
                // $COVERAGE-ON$
              }
            }

            case Failure(f) => {

              f match {

                case UpstreamErrorResponse(message, responseCode, _, _) if responseCode == 500 => {
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] Error : ${message} Exception: $f")
                  // $COVERAGE-ON$

                  // Record the response as a failure, which will help out with cyclic processing of messages
                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), responseCode, None, None, None, containsErrors = true)).map { _ =>

                    origSender ! org.apache.pekko.actor.Status.Failure(f)
                  }
                }

                case _ => {
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] Calling DES failed with error: ${ f.getMessage }")
                  // $COVERAGE-ON$
                  origSender ! org.apache.pekko.actor.Status.Failure(f)

                }
              }
            }

          }

        }
      } recover {
        case e =>
          // $COVERAGE-OFF$
          logger.error(s"[CalculationRequestActor] Calling getPersonDetails failed with error: ${ e.getMessage }")
        // $COVERAGE-ON$
      }


    }

    case STOP => {
      // $COVERAGE-OFF$
      logger.info(s"[CalculationRequestActor] stop message")
      logger.info("sender: " + sender().getClass)
      // $COVERAGE-ON$
      sender() ! STOP
    }


    case e => {
      // $COVERAGE-OFF$
      logger.info(s"[CalculationRequestActor] Invalid Message : { message : $e}")
      logger.info("sender: " + sender().getClass)
      // $COVERAGE-ON$
      sender() ! org.apache.pekko.actor.Status.Failure(new RuntimeException(s"invalid message: $e"))
    }
  }

}

class DefaultCalculationRequestActor (override val repository : BulkCalculationMongoRepository,
                                               override val desConnector : DesConnector,
                                               override val ifConnector: IFConnector,
                                               override val metrics : ApplicationMetrics,
                                               override val applicationConfig: ApplicationConfiguration
                                              ) extends CalculationRequestActor with CalculationRequestActorComponent {
}
