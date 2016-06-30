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

package models

import org.joda.time.LocalDateTime
import play.api.i18n.Messages
import play.api.libs.json.{JsString, Writes, Reads, Json}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

case class ValidCalculationRequest(scon: String,
                                   nino: String,
                                   surname: String,
                                   firstForename: String,
                                   memberReference: Option[String],
                                   calctype: Option[Int],
                                   revaluationDate: Option[String] = None,
                                   revaluationRate: Option[Int] = None,
                                   dualCalc: Option[Int] = None,
                                   terminationDate: Option[String] = None)

object ValidCalculationRequest {
  implicit val formats = Json.format[ValidCalculationRequest]

}


case class CalculationRequest(bulkId: Option[String],
                              lineId: Int,
                              validCalculationRequest: Option[ValidCalculationRequest],
                              validationErrors: Option[Map[String,String]],
                              calculationResponse: Option[GmpBulkCalculationResponse]){

  def hasErrors = ((calculationResponse.isDefined && calculationResponse.get.globalErrorCode > 0)
    || (calculationResponse.isDefined &&
        calculationResponse.get.calculationPeriods.foldLeft(0) { _ + _.errorCode } > 0)
    || validationErrors.isDefined)

  def hasNPSErrors = calculationResponse.isDefined && (calculationResponse.get.globalErrorCode > 0 || calculationResponse.get.hasErrors)

  def getGlobalErrorMessageReason: Option[String] = {
    calculationResponse.isDefined match {
      case true if calculationResponse.get.globalErrorCode > 0 => Some(Messages(s"${calculationResponse.get.globalErrorCode}.reason"))
      case _ => None
    }
  }

  def getGlobalErrorMessageWhat: Option[String] = {
    calculationResponse.isDefined match {
      case true if calculationResponse.get.globalErrorCode > 0 => Some(Messages(s"${calculationResponse.get.globalErrorCode}.what"))
      case _ => None
    }
  }
}

object CalculationRequest {
  implicit val formats = Json.format[CalculationRequest]
}

case class BulkCalculationRequest(_id: Option[String],
                                  uploadReference: String,
                                  email: String,
                                  reference: String,
                                  calculationRequests: List[CalculationRequest],
                                  userId: String,
                                  timestamp: LocalDateTime,
                                  complete: Option[Boolean],
                                  total: Option[Int],
                                  failed: Option[Int],
                                  createdAt: Option[LocalDateTime]) {

  def failedRequestCount: Int = {
    calculationRequests.filter(x => x.validationErrors.isDefined || (x.calculationResponse.isDefined && x.calculationResponse.get.hasErrors)).size
  }
}

object BulkCalculationRequest {
  implicit val timestampReads = Reads[LocalDateTime](js =>
    js.validate[String].map[LocalDateTime](dtString =>
      LocalDateTime.parse(dtString)
    )
  )

  implicit val timestampWrites = new Writes[LocalDateTime]{
    def writes(localDateTime: LocalDateTime) = JsString(localDateTime.toString)
  }
  implicit val formats = Json.format[BulkCalculationRequest]
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val idFormat = ReactiveMongoFormats.objectIdFormats
}


case class ProcessReadyCalculationRequest(bulkId: String,
                                          lineId: Int,
                                          validCalculationRequest: ValidCalculationRequest)

object ProcessReadyCalculationRequest {
  // $COVERAGE-OFF$
  implicit val formats = Json.format[ProcessReadyCalculationRequest]
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val idFormat = ReactiveMongoFormats.objectIdFormats
  // $COVERAGE-ON$
}