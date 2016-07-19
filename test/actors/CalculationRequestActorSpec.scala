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

import akka.actor.{ActorSystem, Props}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import connectors.DesConnector
import helpers.RandomNino
import metrics.Metrics
import models._
import org.mockito.Matchers
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.mock.MockitoSugar
import repositories.BulkCalculationRepository
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future
import scala.concurrent.duration._

class CalculationRequestActorMock(val desConnector: DesConnector, val repository: BulkCalculationRepository,val metrics: Metrics) extends CalculationRequestActor
  with CalculationRequestActorComponent


class CalculationRequestActorSpec extends TestKit(ActorSystem("TestCalculationActorSystem")) with UnitSpec with MockitoSugar
  with BeforeAndAfterAll with DefaultTimeout with ImplicitSender with ActorUtils with BeforeAndAfter {

  val mockDesConnector = mock[DesConnector]
  val mockRepository = mock[BulkCalculationRepository]
  val mockMetrics = mock[Metrics]

  object CalculationRequestActorMock {
    def props(desConnector: DesConnector, repository: BulkCalculationRepository, metrics: Metrics) = Props(classOf[CalculationRequestActorMock], desConnector, repository,metrics)
  }

  before {
    reset(mockDesConnector)
    reset(mockRepository)
    reset(mockMetrics)
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "Calculation Request Actor" must {

    val response = CalculationResponse("",0,None,None,None,Scon("",0,""),Nil)


    "successfully save" in {

      when(mockDesConnector.calculate(Matchers.any())).thenReturn(Future.successful(response))
      when(mockRepository.insertResponseByReference(Matchers.any(),Matchers.any(),Matchers.any())).thenReturn(Future.successful(true))

      val actorRef = system.actorOf(CalculationRequestActorMock.props(mockDesConnector, mockRepository, mockMetrics))

      within(5 seconds) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None))
        expectMsg(true)
      }

    }

    "get failure when fails to send to DES" in {

      when(mockDesConnector.calculate(Matchers.any())).thenThrow(new RuntimeException("The calculation failed"))
      when(mockRepository.insertResponseByReference(Matchers.any(),Matchers.any(),Matchers.any())).thenReturn(Future.successful(true))

      val actorRef = system.actorOf(CalculationRequestActorMock.props(mockDesConnector, mockRepository, mockMetrics))

      within(5 seconds) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None))
        expectMsgClass(classOf[akka.actor.Status.Failure])

        verify(mockRepository).insertResponseByReference("test", 1,
          GmpBulkCalculationResponse(List(), 48160, None, None, None, containsErrors = true))
      }

    }

    "get failure when message wrong type" in {

      val actorRef = system.actorOf(CalculationRequestActorMock.props(mockDesConnector, mockRepository, mockMetrics))

      within(5 seconds) {

        actorRef ! "purple rain"
        expectMsgClass(classOf[akka.actor.Status.Failure])
      }
    }

    "send STOP message to sender when receive the STOP message" in {

      val actorRef = system.actorOf(CalculationRequestActorMock.props(mockDesConnector, mockRepository, mockMetrics))

      within(5 seconds) {

        actorRef ! STOP
        expectMsg(STOP)
      }
    }

  }

}
