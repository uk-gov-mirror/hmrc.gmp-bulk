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

  val calculationRequestNoResponse = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, None))
  val calculationRequestsSingle = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(singlePeriodGmpBulkCalculationResponse)))
  val calculationRequestsMultiple = List(CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(multiplePeriodGmpBulkCalculationResponse)),
                                         CalculationRequest(None, 1, Some(validCalculationRequest), None, Some(singlePeriodGmpBulkCalculationResponse)))

  val bulkCalculationRequestNoReponse = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestNoResponse, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))
  val bulkCalculationRequestSingle = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestsSingle, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))
  val bulkCalculationRequestMultiple = BulkCalculationRequest(None,"abcd", "mail@mail.com", "reference1", calculationRequestsMultiple, "userId", LocalDateTime.now(), Some(true), Some(1), Some(0))

  "CsvGenerator" must {

    "return correct number of trailing commas for single period line when successful calculations requested" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestSingle,Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for multiple period line when successful calculations requested" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,9.36,3.69,,,${date},${date},3.12,1.23,,,,${date},${date},3.12,1.23,,,,${date},${date},3.12,1.23,,," +
                    "\n" + s"S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,,,,,,,,,,,,,,,"
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
        "\n" + s"Success,S2730000B,${nino},John,Smith,ref1,Date of leaving,,${date},,No,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,,,,,,,,,,,,,,,,,,,"
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
  }

}
