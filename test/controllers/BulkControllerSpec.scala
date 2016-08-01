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

package controllers

import connectors.{ReceivedUploadTemplate, EmailConnector}
import helpers.RandomNino
import models._
import org.joda.time.{LocalDateTime, LocalDate}
import org.mockito.{ArgumentCaptor, Matchers}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.i18n.Messages
import play.api.libs.json.{JsString, Json}
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.{FakeHeaders, FakeRequest}
import repositories.BulkCalculationRepository
import uk.gov.hmrc.mongo.Awaiting

import scala.concurrent.Future

class BulkControllerSpec extends PlaySpec with OneServerPerSuite with Awaiting with MockitoSugar {

  val mockRepo = mock[BulkCalculationRepository]
  val mockEmailConnector = mock[EmailConnector]
  val createdAt = Some(LocalDateTime.now)

  final val PERIOD_1_REVAL_COLUMN_INDEX = 21
  final val CSV_HEADER_ROWS = 2

  object TestBulkController extends BulkController {
    override val repository: BulkCalculationRepository = mockRepo
    override val emailConnector = mockEmailConnector
  }

  val nino = RandomNino.generate

  def firstCsvDataLine(result: Future[Result]): Array[String] = {
    val dataLine = contentAsString(result) split "\n" drop CSV_HEADER_ROWS
    dataLine(0).split(",", -1) map { _.trim }
  }

  "BulkController" must {

    val json =
      s"""
              {
                "uploadReference" : "UPLOAD1234",
                "email" : "test@test.com",
                "reference" : "REF1234",
                "calculationRequests" : [
                  {
                    "lineId": 1,

                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "memberReference": "MEMREF123",
                      "nino" : "$nino",
                      "surname" : "Richard-Smith",
                      "firstForename": "Cliff",
                      "calctype" : 1,
                      "revaluationDate": "2018-01-01",
                      "revaluationRate": 2,
                      "requestEarnings": 1,
                      "dualCalc" : 1,
                      "terminationDate" : "2016-07-07"
                    }
                  },
                  {
                    "lineId" : 2,
                    "validationError" : "No scon supplied"
                  },
                  {
                    "lineId" : 3,
                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "nino" : "XR106701A",
                      "surname" : "Richard-Smith",
                      "firstForename": "Cliff",
                      "calctype" : 0
                    }
                  },
                  {
                    "lineId" : 4,
                    "validationError" : "Invalid scon format"
                  }],
                "userId" : "123456",
                "timestamp" : "2016-04-26T14:53:18.308",
                "complete" : true
              }
      """

    "POST" must {

      "sending correct data" must {

        "return an accepted status code" in {
          when(mockRepo.insertBulkDocument(Matchers.any())).thenReturn(Future.successful(true))
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = Json.parse(json))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(OK)
        }

        "return bad request when inserting a duplicate" in {
          when(mockRepo.insertBulkDocument(Matchers.any())).thenReturn(Future.successful(false))
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = Json.parse(json))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(BAD_REQUEST)
        }

        "when unable to save into mongo" must {

          "return an internal server error" in {

            when(mockRepo.insertBulkDocument(Matchers.any())).thenReturn(Future.failed(new RuntimeException("Exception")))

            val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = Json.parse(json))
            val result = TestBulkController.post("USER_ID").apply(fakeRequest)
            intercept[RuntimeException] {
              status(result) must be(INTERNAL_SERVER_ERROR)
            }

          }
        }

        "send a file received notification email to the user" in {
          val c = ArgumentCaptor.forClass(classOf[ReceivedUploadTemplate])
          when(mockEmailConnector.sendReceivedTemplatedEmail(Matchers.any())(Matchers.any())).thenReturn(Future.successful(true))
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = Json.parse(json))
          val result = TestBulkController.post("USER_ID")(fakeRequest)
          verify(mockEmailConnector).sendReceivedTemplatedEmail(c.capture())(Matchers.any())
          c.getValue.email must be ("test@test.com")
          c.getValue.uploadReference must be ("REF1234")
        }
      }

      "sending incorrect data" must {

        "return a bad request status code" in {
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = Json.toJson("""{ "random" : "json" }"""))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(BAD_REQUEST)
        }
      }

      "sending no data" must {

        "return a bad request status code" in {
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = Json.toJson(""""""))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(BAD_REQUEST)
        }
      }
    }

    "getPreviousRequests" must {

      val createdAt = 1464191829716L

      "retrieve list of previous bulk calculation requests" in {
        when(mockRepo.findByUserId(Matchers.any())).thenReturn(Future.successful(Option(List(BulkPreviousRequest("uploadRef", "ref", LocalDateTime.now, LocalDateTime.now)))))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getPreviousRequests("USER_ID").apply(fakeRequest)
        status(result) must be(OK)

        val uploadRefs = (contentAsJson(result).\\("uploadReference"))
        uploadRefs.head.as[JsString].value must be("uploadRef")
        val refs = (contentAsJson(result).\\("reference"))
        refs.head.as[JsString].value must be("ref")
      }

      "retrieve empty list of previous bulk calculation requests" in {
        when(mockRepo.findByUserId(Matchers.any())).thenReturn(Future.successful(Option(Nil)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getPreviousRequests("USER_ID").apply(fakeRequest)
        status(result) must be(OK)

        val uploadRefs = (contentAsJson(result).\\("uploadReference"))
        uploadRefs.size must be(0)
        val refs = (contentAsJson(result).\\("reference"))
        refs.size must be (0)
      }

    }

    "getResultsSummary" must {

      "retrieve a results summary" in {

        when(mockRepo.findSummaryByReference(Matchers.any())).thenReturn(Future.successful(Some(BulkResultsSummary("my ref",Some(10),Some(1), "USER_ID"))))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getResultsSummary("USER_ID","thing-ref")(fakeRequest)
        (contentAsJson(result).\("reference")).as[JsString].value must be("my ref")

      }

      "return 404 when not found" in {

        when(mockRepo.findSummaryByReference(Matchers.any())).thenReturn(Future.successful(None))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getResultsSummary("USER_ID","thing-ref")(fakeRequest)
        status(result) must be(NOT_FOUND)

      }

      "return 403 unauthorized when userid doesn't match" in {
        when(mockRepo.findSummaryByReference(Matchers.any())).thenReturn(Future.successful(Some(BulkResultsSummary("my ref",Some(10),Some(1), "USER_ID"))))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getResultsSummary("WRONG_USER_ID","thing-ref")(fakeRequest)
        status(result) must be(FORBIDDEN)
      }
    }

    "getCalculationsAsCsv" must {

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)
      val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))

      val bulkCalculationRequest = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

      "return bulk result as a csv string" in {

        val periodColumns = "Period 1 (start date),Period 1 (end date),Period 1 (total GMP),Period 1 (post 1988),Period 1 (post 1990 - true gender),"+
          "Period 1 (post 1990 - opposite gender),Period 1 (revaluation rate),Period 1 Error,Period 1 What to do"
        val csvRows = s"""Success,S2730000B,$nino,John,Smith,ref1,Date of leaving,,${LocalDate.now().toString("dd/MM/yyyy")},,No,3.12,1.23,,,"""
        val guidanceText = s"${Messages("gmp.bulk.csv.guidance")}"
        val columnHeaders = Messages("gmp.bulk.csv.headers") + "," + Messages("gmp.bulk.totals.headers") + "," + periodColumns

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "USER_ID", CsvFilter.All)(fakeRequest)

        contentAsString(result) must include(guidanceText)
        contentAsString(result) must include(columnHeaders)
        contentAsString(result) must include(csvRows)

      }

      "not include dual calc values if dual calcs are not requested" in {

        val validCalculationRequest = ValidCalculationRequest("S2730000B", "BH000007A", "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "0", "0", 1, 0, None, Some("983.5"), Some("374.45"), None, None)
        ), 0, None, None, None)

        val calcRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val request = BulkCalculationRequest(None, "ref-1", "test@test.com", "ref-1", calcRequests, "user1", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(request)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("user1", "ref-1", CsvFilter.All)(fakeRequest)

        contentAsString(result) mustNot include("983.5")
        contentAsString(result) mustNot include("092384")
      }

      "getCalculationsAsCsv when revaluation with different rates" must {

        val validCalculationRequest = ValidCalculationRequest("S2730000B", "BH000007A", "Smith", "John", Some("ref1"), Some(1), None, Some(0), None, None)
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
              CalculationPeriod(Some(LocalDate.parse("2040-8-21")), LocalDate.parse("2035-8-21"), "3.12", "1.23", 1, 0, None, None, None, None, None),
              CalculationPeriod(Some(LocalDate.parse("2000-8-21")), LocalDate.parse("2005-8-21"), "3.12", "1.23", 2, 0, None, None, None, None, None),
              CalculationPeriod(Some(LocalDate.parse("1999-8-21")), LocalDate.parse("2000-8-21"), "3.12", "1.23", 3, 0, None, None, None, None, None),
              CalculationPeriod(Some(LocalDate.parse("1999-8-21")), LocalDate.parse("2000-8-21"), "3.12", "1.23", 1, 0, None, None, None, None, None)
        ), 0, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        "return bulk result as a csv string" in {
          val periodColumns = "Period 1 (start date),Period 1 (end date),Period 1 (total GMP),Period 1 (post 1988),Period 1 (post 1990 - true gender),"+
          "Period 1 (post 1990 - opposite gender),Period 1 (revaluation rate),Period 1 Error,Period 1 What to do,Period 2 (start date),Period 2 (end date),"+
            "Period 2 (total GMP),Period 2 (post 1988),Period 2 (post 1990 - true gender),Period 2 (post 1990 - opposite gender),Period 2 (revaluation rate),"+
            "Period 2 Error,Period 2 What to do,Period 3 (start date),Period 3 (end date),Period 3 (total GMP),Period 3 (post 1988),"+
            "Period 3 (post 1990 - true gender),Period 3 (post 1990 - opposite gender),Period 3 (revaluation rate),Period 3 Error,Period 3 What to do"

          val csvRows = """Success,S2730000B,BH000007A,John,Smith,ref1,GMP specific date,,21/08/2035,HMRC,No,12.48,4.92,,,21/08/2040,21/08/2035,3.12,1.23,,,s148,,,21/08/2000,21/08/2005,3.12,1.23,,,Fixed,,,21/08/1999,21/08/2000,3.12,1.23,,,Limited,,,21/08/1999,21/08/2000,3.12,1.23,,,s148,,,,"""
          val guidanceText = s"${Messages("gmp.bulk.csv.guidance")},,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,"
          val columnHeaders = Messages("gmp.bulk.csv.headers") + "," + Messages("gmp.bulk.totals.headers") + "," + periodColumns

          when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
          val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
          val result = TestBulkController.getCalculationsAsCsv("userId", "USER_ID", CsvFilter.All)(fakeRequest)

          contentAsString(result) must include(guidanceText)
          contentAsString(result) must include(columnHeaders)
          contentAsString(result) must include(csvRows)

        }
      }

      "return not found with an invalid file reference" in {

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(None))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "USER_ID", CsvFilter.All)(fakeRequest)

        status(result) must be(NOT_FOUND)

      }


      "set the correct calculation type" in {

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest.copy(calctype = Some(1))), None, Some(gmpBulkCalculationResponse)),
          CalculationRequest(None, 1, Some(validCalculationRequest.copy(calctype = Some(2))), None, Some(gmpBulkCalculationResponse)),
          CalculationRequest(None, 1, Some(validCalculationRequest.copy(calctype = Some(3))), None, Some(gmpBulkCalculationResponse)),
          CalculationRequest(None, 1, Some(validCalculationRequest.copy(calctype = Some(4))), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "USER_ID", CsvFilter.All)(fakeRequest)

        contentAsString(result) must include(Messages("gmp.calc_type.specific_date"))
        contentAsString(result) must include(Messages("gmp.calc_type.survivor"))
        contentAsString(result) must include(Messages("gmp.calc_type.spa"))
        contentAsString(result) must include(Messages("gmp.calc_type.payable_age"))
      }

      "return no period headers when no periods exist in response" in {
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest.copy(calctype = Some(1))), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        val periodColumns = "Period 1 (start date),Period 1 (end date),Period 1 (total GMP),Period 1 (post 1988),Period 1 (post 1990 - true gender),"+
          "Period 1 (post 1990 - opposite gender),Period 1 (revaluation rate),Period 1 Error,Period 1 What to do"

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "USER_ID", CsvFilter.All)(fakeRequest)

        contentAsString(result) must not include (periodColumns)

      }

      "does not authenticate when user id is not valid" in {
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest.copy(calctype = Some(1))), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("wrongId", "USER_ID", CsvFilter.All)(fakeRequest)

        status(result) must be(FORBIDDEN)
      }

      "includes the error message" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)
        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), Some("2010-01-31"), None, None, Some("2010-01-31"))

        val calculationRequests = List(
          CalculationRequest(None,1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)),
          CalculationRequest(None,1, Some(validCalculationRequest), Some(Map(RequestFieldKey.SCON.toString -> "This row has an error")), None)
        )

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include("This row has an error")
        contentAsString(result) must include(Messages("gmp.status"))
        contentAsString(result) must include(Messages("gmp.success"))
        contentAsString(result) must include("31/01/2010")
      }

      "includes period data" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 0, None, None, None, None, None)), 0, None, None, None)
        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        contentAsString(result) must include(LocalDate.now().toString("dd/MM/yyyy"))
        contentAsString(result) must include("3.12")
        contentAsString(result) must include("4.12")
      }

      "report a failed calculation if there is a period error" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 2, None, None, None, None, None)), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        val line = contentAsString(result) split "\n" drop 2

        line(0) must startWith (Messages("gmp.error"))

      }

      "only include successful calculations when requested" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 1, None, None, None)
        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
        val periodsResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 56002, None, None, None, None, None)), 0, None, None, None, true)

        val calculationRequests = List(

          CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)),
          CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(periodsResponse)),
          CalculationRequest(None, 1, None, None, None)
        )

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Successful)(fakeRequest)

        contentAsString(result) must not include ("This row has an error")
        contentAsString(result) must not include(Messages("gmp.success"))
        contentAsString(result) must not include(Messages("gmp.status"))
        contentAsString(result) must not include(Messages("gmp.period.error"))
      }

      "only include failed calculations when requested" in {

        val partiallyFailedResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
          CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 56002, None, None, None, None, None)), 0, None, None, None, true)

        val validCalculationRequest = ValidCalculationRequest("S2730000B",nino,"Smith","John",Some("ref1"),Some(0),None,None,None,None)

        val calculationRequests = List(

          CalculationRequest(None, 1, Some(validCalculationRequest.copy(scon = "S2730001B")), None, Some(partiallyFailedResponse)),
          CalculationRequest(None, 2, Some(validCalculationRequest), Some(Map(RequestFieldKey.SCON.toString -> "This scon has an error",
                                               RequestFieldKey.NINO.toString -> "This nino has an error",
                                               RequestFieldKey.SURNAME.toString -> "This surname has an error",
                                               RequestFieldKey.FORENAME.toString -> "This forename has an error",
                                               RequestFieldKey.MEMBER_REFERENCE.toString -> "This mem_ref has an error",
                                               RequestFieldKey.CALC_TYPE.toString -> "This calc_type has an error",
                                               RequestFieldKey.DATE_OF_LEAVING.toString -> "This DOL has an error",
                                               RequestFieldKey.GMP_DATE.toString -> "This gmp_date has an error",
                                               RequestFieldKey.REVALUATION_RATE.toString -> "This rate has an error",
                                               RequestFieldKey.OPPOSITE_GENDER.toString -> "This dual_calc has an error")), None)
        )

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd","mail@mail.com","reference1",calculationRequests,"userId",LocalDateTime.now(),Some(true),Some(1),Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Failed)(fakeRequest)

        contentAsString(result) must include("This scon has an error")
        contentAsString(result) must include("This nino has an error")
        contentAsString(result) must include("This surname has an error")
        contentAsString(result) must include("This forename has an error")
        contentAsString(result) must include("This mem_ref has an error")
        contentAsString(result) must include("This calc_type has an error")
        contentAsString(result) must include("This DOL has an error")
        contentAsString(result) must include("This gmp_date has an error")
        contentAsString(result) must include("This rate has an error")
        contentAsString(result) must include("This dual_calc has an error")
        contentAsString(result) must not include(Messages("gmp.success"))
        contentAsString(result) must not include(Messages("gmp.status"))
        contentAsString(result) must include(Messages("56002.what"))
        contentAsString(result) must not include("Total GMP")

      }

      "contain the field validation errors when present" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty,0,None,None,None)
        val validCalculationRequest = ValidCalculationRequest("S2730000B",nino,"Smith","John",Some("ref1"),Some(0),None,None,None,None)

        val calculationRequests = List(CalculationRequest(None,1, Some(validCalculationRequest), Some(Map(RequestFieldKey.SURNAME.toString -> "Please enter a valid surname")), None))

        val bulkCalculationRequest = BulkCalculationRequest(None,"abcd","mail@mail.com","reference1",calculationRequests,"userId",LocalDateTime.now(),Some(true),Some(1),Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Failed)(fakeRequest)

        contentAsString(result) must include("Please enter a valid surname")
      }

      "contain the line error when present in the correct column with all results" in {

        val errors = Map(RequestFieldKey.LINE_ERROR_TOO_FEW.toString -> "The line has an error")
        val requests = List(CalculationRequest(None, 1, None, Some(errors), None))
        val bulkRequest = BulkCalculationRequest(None, "ref1", "mail@mail.com", "reference1", requests, "userId", LocalDateTime.now(), Some(true), Some(0), Some(1))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        contentAsString(result) must include("The line has an error,See the instruction file on the GMP checker dashboard. Resend the calculation request with the missing field(s)")

      }

      "contain the line error when present in the correct column with failed results" in {

        val errors = Map(RequestFieldKey.LINE_ERROR_TOO_MANY.toString -> "The line has an error")
        val requests = List(CalculationRequest(None, 1, None, Some(errors), None))
        val bulkRequest = BulkCalculationRequest(None, "ref1", "mail@mail.com", "reference1", requests, "userId", LocalDateTime.now(), Some(true), Some(0), Some(1))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Failed)(fakeRequest)

        contentAsString(result) must include("The line has an error,See the instruction file on the GMP checker dashboard. Resend the calculation request without the extra field(s)")
      }

      "contain the correct line error when there are no calculations" in {
        val errors = Map(RequestFieldKey.LINE_ERROR_EMPTY.toString -> "The line is empty")
        val requests = List(CalculationRequest(None, 1, None, Some(errors), None))
        val bulkRequest = BulkCalculationRequest(None, "ref1", "mail@mail.com", "ref1", requests, "userId", LocalDateTime.now(), Some(true), Some(0), Some(1))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())) thenReturn Future.successful(Some(bulkRequest))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Failed)(fakeRequest)
        val content = contentAsString(result)

        content must include("The line is empty")
      }

      "insert an empty reval rate when member in scheme, and calc type is Survivor" in {

        val bulkRequest = mock[BulkCalculationRequest]
        val calcRequest = mock[CalculationRequest]

        val response = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "4.12", "5.23", 1, 0, None, None, None, None, None)),
          0, None, None, None)

        val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(3), None, None, None, Some("2016-05-24"), Some(true))

        when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
        when(calcRequest.calculationResponse) thenReturn Some(response)
        when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
        when(bulkRequest.userId) thenReturn "userId"
        when(mockRepo.findByReference(Matchers.any(), Matchers.any())) thenReturn Future.successful(Some(bulkRequest))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""
      }

      "insert an empty reval rate when member in scheme, and calc type is SPA" in {

        val bulkRequest = mock[BulkCalculationRequest]
        val calcRequest = mock[CalculationRequest]

        val response = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "4.12", "5.23", 1, 0, None, None, None, None, None)),
          0, None, None, None)

        val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(4), None, None, None, Some("2016-05-24"), Some(true))

        when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
        when(calcRequest.calculationResponse) thenReturn Some(response)
        when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
        when(bulkRequest.userId) thenReturn "userId"
        when(mockRepo.findByReference(Matchers.any(), Matchers.any())) thenReturn Future.successful(Some(bulkRequest))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""
      }

      "insert an empty reval rate when member in scheme, and calc type is PA" in {

        val bulkRequest = mock[BulkCalculationRequest]
        val calcRequest = mock[CalculationRequest]

        val response = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "4.12", "5.23", 1, 0, None, None, None, None, None)),
          0, None, None, None)

        val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(2), None, None, None, Some("2016-05-24"), Some(true))

        when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
        when(calcRequest.calculationResponse) thenReturn Some(response)
        when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
        when(bulkRequest.userId) thenReturn "userId"
        when(mockRepo.findByReference(Matchers.any(), Matchers.any())) thenReturn Future.successful(Some(bulkRequest))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""
      }

      "insert an empty reval rate into the period when the rate is HMRC" in {

        val bulkRequest = mock[BulkCalculationRequest]
        val calcRequest = mock[CalculationRequest]

        val response = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "0", "0", 0, 2, None, None, None, None, None)),
          0, None, None, None)

        val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(3), Some("2018-05-10"), None, None, Some("2016-08-24"), None)

        when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
        when(calcRequest.calculationResponse) thenReturn Some(response)
        when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
        when(bulkRequest.userId) thenReturn "userId"
        when(mockRepo.findByReference(Matchers.any(), Matchers.any())) thenReturn Future.successful(Some(bulkRequest))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.All)(fakeRequest)

        firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""

      }
    }

    "getContributionsAndEarningsAsCsv" must {

      "include the contributions data" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(new LocalDate(2001, 1, 1)), new LocalDate(2005,1,1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
                      ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)

        contentAsString(result) must include(Messages("gmp.bulk.csv.contributions.headers"))
        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include(nino)
        contentAsString(result) must include("Smith")
        contentAsString(result) must include("John")
        contentAsString(result) must include("01/01/2001 - 01/01/2005")
        contentAsString(result) must include("123.45")
      }

      "include member detail in contributions data rows" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(new LocalDate(2001, 1, 1)), new LocalDate(2005,1,1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)

        contentAsString(result) must include(Messages("gmp.bulk.csv.contributions.headers"))
        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include(nino)
        contentAsString(result) must include("Smith")
        contentAsString(result) must include("John")
        contentAsString(result) must include("01/01/2001 - 01/01/2005")
        contentAsString(result) must include("123.45")
      }

      "return Forbidden if no user is found" in {
        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(
          Future.successful(Some(BulkCalculationRequest(None,"Ref", "email", "ref2",
            List.empty[CalculationRequest], "USER-ID", LocalDateTime.now, None, None, None))))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("WRONG_USER_ID","thing-ref")(fakeRequest)
        status(result) must be(FORBIDDEN)
      }


      "return 404 when not found" in {

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(None))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> Seq("application/json"))), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("USER_ID","thing-ref")(fakeRequest)
        status(result) must be(NOT_FOUND)
      }

      "cope with multiple period contributions data" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(new LocalDate(2001, 1, 1)), new LocalDate(2005,1,1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45")))),
          CalculationPeriod(Some(new LocalDate(2001, 1, 1)), new LocalDate(2006,1,1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "200.12"))))
        ), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)

        contentAsString(result) must include(Messages("gmp.bulk.csv.contributions.headers"))
        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include(nino)
        contentAsString(result) must include("Smith")
        contentAsString(result) must include("John")
        contentAsString(result) must include("01/01/2001 - 01/01/2005")
        contentAsString(result) must include("01/01/2001 - 01/01/2006")
        contentAsString(result) must include("123.45")
        contentAsString(result) must include("200.12")
      }
    }


    "getResultsAsCsv" must {

      "return a file name" in {
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(new LocalDate(2001, 1, 1)), new LocalDate(2005,1,1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Successful)(fakeRequest)


        header("Content-Disposition",result).get must be("attachment; filename=\"reference1_total_GMP.csv\"")
        contentType(result).get must be("text/csv")

      }

      "return a contributions and earnings file name" in {
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(new LocalDate(2001, 1, 1)), new LocalDate(2005,1,1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = BulkCalculationRequest(None, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

        when(mockRepo.findByReference(Matchers.any(), Matchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)


        header("Content-Disposition",result).get must be("attachment; filename=\"reference1_contributions_and_earnings.csv\"")
        contentType(result).get must be("text/csv")

      }
    }
  }
}
