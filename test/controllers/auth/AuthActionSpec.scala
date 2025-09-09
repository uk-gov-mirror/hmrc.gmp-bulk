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

package controllers.auth

import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.Status.{OK, UNAUTHORIZED}
import play.api.mvc.{Action, AnyContent}
import play.api.mvc.{BaseController, ControllerComponents}
import play.api.test.FakeRequest
import play.api.test.Helpers.{status, stubMessagesControllerComponents}
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.auth.core.retrieve.Retrieval
import uk.gov.hmrc.auth.core.{AuthConnector, MissingBearerToken}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.concurrent.duration.*

class AuthActionSpec extends PlaySpec with GuiceOneAppPerSuite with MockitoSugar {

  class Harness(authAction: AuthAction) extends BaseController {
    def onPageLoad(): Action[AnyContent] = authAction { request => Ok }

    override def controllerComponents: ControllerComponents = stubMessagesControllerComponents()
  }

  implicit val timeout: Timeout = 5 seconds

  "Auth Action" when {
    "the user is not logged in" must {
      "must return unauthorised" in {

        val mockMicroserviceAuthConnector = mock[AuthConnector]

        when(
          mockMicroserviceAuthConnector.authorise[Unit](
            any[Predicate],
            any[Retrieval[Unit]]
          )(
            using any[HeaderCarrier],
            any[ExecutionContext]
          )
        ).thenReturn(Future.failed(new MissingBearerToken))

        val authAction = new AuthAction(mockMicroserviceAuthConnector, stubMessagesControllerComponents())
        val controller = new Harness(authAction)
        val result = controller.onPageLoad()(FakeRequest("", ""))
        status(result) mustBe UNAUTHORIZED

      }
    }

    "the user is logged in" must {
      "must return the request" in {
        val mockMicroserviceAuthConnector = mock[AuthConnector]

        when(
          mockMicroserviceAuthConnector.authorise[Unit](
            any[Predicate],
            any[Retrieval[Unit]]
          )(
            using any[HeaderCarrier],
            any[ExecutionContext]
          )
        ).thenReturn(Future.successful(()))

        when(
          mockMicroserviceAuthConnector.authorise[Unit](
            any[Predicate],
            any[Retrieval[Unit]]
          )(
            using any[HeaderCarrier],
            any[ExecutionContext]
          )
        ).thenReturn(Future.successful(()))

        val authAction = new AuthAction(mockMicroserviceAuthConnector, stubMessagesControllerComponents())
        val controller = new Harness(authAction)

        val result = controller.onPageLoad()(FakeRequest("", ""))
        status(result) mustBe OK

      }
    }
  }
}
