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

package connectors

import org.joda.time.LocalDate
import org.mockito.ArgumentCaptor
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneAppPerSuite
import play.api.Play
import play.api.test.FakeApplication
import uk.gov.hmrc.play.http.{HttpResponse, HeaderCarrier, HttpPost}
import scala.concurrent.duration._
import scala.concurrent.{Future, Await}
import org.mockito.Mockito._
import org.mockito.Matchers._

class EmailConnectorSpec extends FunSpec with OneAppPerSuite with MockitoSugar with MustMatchers with BeforeAndAfter {

  Play.start(new FakeApplication())

  val mockHttp = mock[HttpPost]

  object TestEmailConnector extends EmailConnector {
    override val http: HttpPost = mockHttp
  }

  implicit val hc = HeaderCarrier()


  describe("The email connector") {

    describe("The send upload received templated email method") {

      val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
      val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

      reset(mockHttp)

      when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(202)))

      val result = Await.result(TestEmailConnector.sendReceivedTemplatedEmail(template), 5 seconds)

      it("must return a true result") {
        result must be(true)
      }

      it("must send the user's email address") {
        requestCaptor.getValue.to must contain("joe@bloggs.com")
      }

      it("must send the user's file upload reference") {
        requestCaptor.getValue.parameters must contain("fileUploadReference" -> "upload-ref")
      }

      it("must send the correct template id") {
        requestCaptor.getValue.templateId must be("gmp_bulk_upload_received")
      }
    }

    describe("A failed send upload received templated email method") {

      val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")

      reset(mockHttp)

      when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, any[SendTemplatedEmailRequest], any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(400)))

      val result = Await.result(TestEmailConnector.sendReceivedTemplatedEmail(template), 5 seconds)

      it("must return a false result") {
        result must be(false)
      }
    }

    describe("The send upload processed templated email method") {

      val date = LocalDate.now
      val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")
      val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

      reset(mockHttp)

      when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(202)))

      val result = Await.result(TestEmailConnector.sendProcessedTemplatedEmail(template), 5 seconds)

      it("must return a true result") {
        result must be(true)
      }

      it("must send the user's email address") {
        requestCaptor.getValue.to must contain("joe@bloggs.com")
      }

      it("must send the user's uppload reference") {
        requestCaptor.getValue.parameters must contain("fileUploadReference" -> "upload-ref")
      }

      it("must send the user's upload date") {
        requestCaptor.getValue.parameters must contain("uploadDate" -> date.toString("dd MMMM yyyy"))
      }

      it("must send the user's user id") {
        requestCaptor.getValue.parameters must contain("userId" -> "*****567")
      }
    }

    describe("A failed send upload processed templated email method") {

      val date = LocalDate.now
      val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

      reset(mockHttp)

      when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, any[SendTemplatedEmailRequest], any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(400)))

      val result = Await.result(TestEmailConnector.sendProcessedTemplatedEmail(template), 5 seconds)

      it("must return a false result") {
        result must be(false)
      }
    }
  }
}
