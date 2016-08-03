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

import helpers.RandomNino
import models._
import org.joda.time.{LocalDateTime, LocalDate}
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import uk.gov.hmrc.mongo.Awaiting

class CsvGeneratorSpec extends PlaySpec with OneServerPerSuite with Awaiting with MockitoSugar{

  object TestCsvGenerator extends CsvGenerator

  val nino = RandomNino.generate
  val date = LocalDate.now().toString("dd/MM/yyyy")

  val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

  val singlePeriodGmpBulkCalculationResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)
  val multiplePeriodGmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
    CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
    CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
    CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)
  val calculationResponseWithError = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 56010, None, None, None)
  val calculationResponseWithNpsError = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 56018, None, None, None, None, None)), 0, None, None, None)

  val calculationRequestNoResponse = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, None))
  val calculationRequestsSingle = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(singlePeriodGmpBulkCalculationResponse)))
  val calculationRequestsMultiple = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(multiplePeriodGmpBulkCalculationResponse)),
                                         CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(singlePeriodGmpBulkCalculationResponse)))
  val calculationRequestWithError = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(calculationResponseWithError)))
  val calculationRequestWithNpsError = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(calculationResponseWithNpsError)))


  val bulkCalculationRequestNoReponse = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestNoResponse, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))
  val bulkCalculationRequestSingle = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestsSingle, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))
  val bulkCalculationRequestMultiple = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestsMultiple, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))
  val bulkCalculationRequestWithError = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestWithError, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))
  val bulkCalculationRequestWithNpsError = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestWithNpsError, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

  "CsvGenerator" must {

    "return correct number of trailing commas for single period line when successful calculations requested" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestSingle,Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for multiple period line when successful calculations requested" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,9.36,3.69,,,${date},${date},3.12,1.23,,,,${date},${date},3.12,1.23,,,,${date},${date},3.12,1.23,,," +
                    "\n" + s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,,,,,,,,,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestMultiple,Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for single period line when all calculation requested" in {

      val expectedResult = s"Success,S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestSingle,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for multiple period line when all calculations requested" in {

      val expectedResult = s"Success,S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,9.36,3.69,,,${date},${date},3.12,1.23,,,,,,${date},${date},3.12,1.23,,,,,,${date},${date},3.12,1.23,,,,,,," +
        "\n" + s"Success,S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,,,,,,,,,,,,,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestMultiple,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "add no commas when no calculation response" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,,,No,0,0,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestNoReponse,Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)

    }

    "add 8 commas when calculation error exists" in {

      val expectedResult = s"Error,S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,Member must confirm their date of birth,Contact HMRC through Shared Workspace"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestWithError,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "add 6 commas when nps error exists" in {

      val expectedResult = s"Error,S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,Member has a Married Woman's Reduced Rate Election for contracted out period,Nothing - there is no GMP liability,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestWithNpsError,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "show no reval rate for calctype 1 and member still in scheme" in {
      val expectedResult = s"Success,S2730000B,${nino},John,Smith,ref1,GMP specific date,${date},${date},,No,3.12,1.23,,,07/03/1983,${date},3.12,1.23,,,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B",s"${nino}","Smith","John",Some("ref1"),Some(1),Some(LocalDate.now().toString()),None,Some(0),Some(LocalDate.now().toString()),Some(true))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), LocalDate.now(), "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(CalculationRequest(None, 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "show no reval rate for calctype 1 and member not still in scheme but first period still open" in {
      val expectedResult = s"Success,S2730000B,${nino},John,Smith,ref1,GMP specific date,${date},${date},,No,3.12,1.23,,,07/03/1983,${date},3.12,1.23,,,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B",s"${nino}","Smith","John",Some("ref1"),Some(1),Some(LocalDate.now.toString()),None,Some(0),Some(LocalDate.now().toString()),Some(false))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), LocalDate.now, "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(CalculationRequest(None, 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "show correct reval rate for calctype 1 and member not still in scheme and period closed" in {
      val yesterdaysDate = LocalDate.now.minusDays(1)
      val expectedResult = s"Success,S2730000B,${nino},John,Smith,ref1,GMP specific date,${date},${yesterdaysDate.toString("dd/MM/yyyy")},,No,3.12,1.23,,,07/03/1983,${yesterdaysDate.toString("dd/MM/yyyy")},3.12,1.23,,,s148,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B",s"${nino}","Smith","John",Some("ref1"),Some(1),Some(yesterdaysDate.toString()),None,Some(0),Some(LocalDate.now().toString()),Some(false))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), yesterdaysDate, "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(CalculationRequest(None, 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest,Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }
  }

}
