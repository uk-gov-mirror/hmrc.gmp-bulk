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

import java.io.{BufferedWriter, File, FileWriter}

import models._
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import play.api.i18n.Messages

import scala.collection.mutable.ListBuffer

trait CsvGenerator {

  val DATE_DEFAULT_FORMAT = "dd/MM/yyyy"

  case class Cell(text: String)

  case class Row(cells: Traversable[Cell]) {
    override def toString = {
      cells map {
        _.text
      } mkString ","
    }
  }

  class RowBuilder {

    val cells = new ListBuffer[Cell]()
    var errorCell = Cell("")
    var errorResolutionCell = Cell("")

    def addMsgCell(text: String) = addCell(Messages(text))

    def addCell(text: Option[Any], default: String): RowBuilder = {
      addCell(text match {
        case Some(t) => t.toString
        case _ => default
      })
    }

    def addCell(text: String): RowBuilder = addCell(Cell(text))

    def addCell(cell: Cell) = {
      cells += cell
      this
    }

    def addCell(cells: Traversable[String]): RowBuilder = {
      cells foreach addCell
      this
    }

    def addBlankCell(): RowBuilder = addCell("")

    def addFilteredCell(f: PartialFunction[CsvFilter, String])(implicit filter: CsvFilter) = {
      if (f.isDefinedAt(filter))
        addCell(Cell(f(filter)))
      this
    }

    def addFilteredCells(f: PartialFunction[CsvFilter, Traversable[String]])(implicit filter: CsvFilter) = {
      if (f.isDefinedAt(filter))
        f(filter) foreach addCell
      this
    }

    def addRow(row: Row) = {
      row.cells map addCell
      this
    }

    def addRows(rows: Traversable[Row]) = {
      rows map addRow
      this
    }

    def setErrorCell(cell: Cell) = this.errorCell = cell

    def setErrorResolutionCell(cell: Cell) = errorResolutionCell = cell

    def build: Row = Row(cells)

    def getCells = cells.toList
  }

  class PeriodHeaderRowBuilder(periodIndex: Int) extends RowBuilder {
    override def addMsgCell(text: String) = {
      super.addCell(buildCellText(text))
    }

    def buildCellText(text: String): String = {
      s"${Messages("gmp.period")} $periodIndex ${Messages(text)}"
    }
  }

  class RequestRowBuilder(request: CalculationRequest)(implicit filter: CsvFilter) extends RowBuilder {

    request.validCalculationRequest match {
      case Some(x) => {

        if (filter == CsvFilter.All) {
          addCell(request.hasErrors match {
            case true => Messages("gmp.error")
            case false => Messages("gmp.success")
          })
        }

        addValidatedCell(x.scon, RequestFieldKey.SCON)
        addValidatedCell(x.nino, RequestFieldKey.NINO)
        addValidatedCell(x.firstForename, RequestFieldKey.FORENAME)
        addValidatedCell(x.surname, RequestFieldKey.SURNAME)
        addValidatedCell(x.memberReference.getOrElse(""), RequestFieldKey.MEMBER_REFERENCE)
        addValidatedCell(convertCalcType(x.calctype), RequestFieldKey.CALC_TYPE)
        addValidatedCell(convertDate(x.terminationDate), RequestFieldKey.DATE_OF_LEAVING)
        addValidatedCell(determineGmpAtDate(request), RequestFieldKey.GMP_DATE)
        addValidatedCell(convertRevalRate(x.revaluationRate), RequestFieldKey.REVALUATION_RATE)

        addValidatedCell(x.dualCalc match {
          case Some(1) => Messages("gmp.generic.yes")
          case _ => Messages("gmp.generic.no")
        }, RequestFieldKey.OPPOSITE_GENDER)

        request.validationErrors match {
          case None => {

            addCell(sumPeriod(request, {
              _.gmpTotal
            }).toString)

            addCell(sumPeriod(request, {
              _.post88GMPTotal
            }).toString)

            addCell(x.dualCalc match {
              case Some(d) if d == 1 => sumPeriod(request, {
                _.dualCalcPost90TrueTotal match {
                  case Some(trueCalc) => trueCalc
                  case _ => ""
                }
              }).toString
              case _ => ""
            })

            addCell(x.dualCalc match {
              case Some(d) if d == 1 => sumPeriod(request, {
                _.dualCalcPost90OppositeTotal match {
                  case Some(oppositeCalc) => oppositeCalc
                  case _ => "0"
                }
              }).toString
              case _ => ""
            })
          }
          case _ =>
            addBlankCell()
            addBlankCell()
            addBlankCell()
            addBlankCell()
        }

        request.calculationResponse match {
          case Some(response) => {
            response.calculationPeriods.zipWithIndex.map {
              case (period, index) => {
                val periodBuilder = new PeriodRowBuilder(period, index, x)
                addRow(periodBuilder.build)
              }
            }
          }
          case _ => Nil
        }

        request.getGlobalErrorMessageReason match {
          case Some(msg) => setErrorCell(Cell(msg))
          case _ => Nil
        }

        request.getGlobalErrorMessageWhat match {
          case Some(msg) => setErrorResolutionCell(Cell(msg))
          case _ => Nil
        }

      }
      case _ if request.validationErrors.isDefined => {
        Seq(
          (RequestFieldKey.LINE_ERROR_TOO_FEW.toString, Messages("gmp.error.line.too_few")),
          (RequestFieldKey.LINE_ERROR_TOO_MANY.toString, Messages("gmp.error.line.too_many")),
          (RequestFieldKey.LINE_ERROR_EMPTY.toString, "" /* Intentionally empty */ )
        ) collectFirst {
          case x if request.validationErrors.get.isDefinedAt(x._1) => x
        } match {
          case Some(err) =>
            setErrorCell(Cell(request.validationErrors.get(err._1)))
            setErrorResolutionCell(Cell(err._2))
          case _ => Nil
        }
      }
      case _ => Nil
    }

    //s"${"," * errorColumn}${validationError(err._1)},${err._2}"

    this

    def addValidatedCell(value: Any, validationColumn: Int): Cell = {

      val getValue = () => if (value == Nil) "" else value.toString

      val cell = Cell(request.validationErrors match {
        case Some(v) => if (v.isDefinedAt(validationColumn.toString)) v(validationColumn.toString) else getValue()
        case _ => getValue()
      })

      addCell(cell)
      cell
    }

    override def build = {
      filter match {
        case CsvFilter.Successful => Row(cells)
        case _ => Row(cells ++= Seq(errorCell, errorResolutionCell))
      }
    }

    private def sumPeriod(request: CalculationRequest, selector: (CalculationPeriod) => String) = {
      request.calculationResponse match {
        case Some(response) => response.calculationPeriods.foldLeft(BigDecimal(0)) { (sum, period) => sum + BigDecimal(selector(period)) }
        case _ => 0
      }
    }

    private def convertCalcType(calcType: Option[Int]): String = {
      calcType match {
        case Some(0) => Messages("gmp.calc_type.leaving")
        case Some(1) => Messages("gmp.calc_type.specific_date")
        case Some(2) => Messages("gmp.calc_type.payable_age")
        case Some(3) => Messages("gmp.calc_type.survivor")
        case _ => Messages("gmp.calc_type.spa")
      }
    }

    private def convertDate(date: Option[String]): String = {

      val inputDateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")

      date match {
        case Some(d) => LocalDate.parse(d, inputDateFormatter).toString(DATE_DEFAULT_FORMAT)
        case _ => ""
      }
    }

    private def determineGmpAtDate(request: CalculationRequest): String = {

      val inputDateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      request.validCalculationRequest.map {

        calculationRequest =>

          request.calculationResponse.map {
            calculationResponse =>

              calculationRequest.calctype match {

                case Some(2) => calculationResponse.payableAgeDate.map {
                  dod => dod.toString(DATE_DEFAULT_FORMAT)
                }.getOrElse("")


                case Some(3) => {
                  calculationRequest.revaluationDate.map {
                    d => LocalDate.parse(d, inputDateFormatter).toString(DATE_DEFAULT_FORMAT)
                  }.getOrElse(calculationResponse.dateOfDeath.map {
                    dod => dod.toString(DATE_DEFAULT_FORMAT)
                  }.getOrElse(""))
                }

                case Some(4) => calculationResponse.spaDate.map {
                  dod => dod.toString(DATE_DEFAULT_FORMAT)
                }.getOrElse("")

                case _ if calculationRequest.revaluationDate.isEmpty => calculationResponse.calculationPeriods.headOption.map {
                  period => period.endDate.toString(DATE_DEFAULT_FORMAT)
                }.getOrElse("")

                case _ => convertDate(calculationRequest.revaluationDate)
              }

          }.getOrElse("")
      }.getOrElse("")

    }

    private def convertRevalRate(revalRate: Option[Int]): String = {
      revalRate match {
        case Some(0) => RevaluationRate.HMRC
        case Some(1) => RevaluationRate.S148
        case Some(2) => RevaluationRate.FIXED
        case Some(3) => RevaluationRate.LIMITED
        case _ => ""
      }
    }
  }

  class PeriodRowBuilder(calculationPeriod: CalculationPeriod, index: Int, request: ValidCalculationRequest)(implicit filter: CsvFilter) extends RowBuilder {

    addCell(calculationPeriod.startDate match {
      case Some(date) => date.toString(DATE_DEFAULT_FORMAT)
      case _ => ""
    })

    addCell(calculationPeriod.endDate.toString(DATE_DEFAULT_FORMAT))
    addCell(calculationPeriod.gmpTotal)
    addCell(calculationPeriod.post88GMPTotal)

    request.dualCalc match {
      case Some(x) if x == 1 =>
        addCell(calculationPeriod.dualCalcPost90TrueTotal, "")
        addCell(calculationPeriod.dualCalcPost90OppositeTotal, "")
      case _ => {
        addBlankCell()
        addBlankCell()
      }
    }

    addCell(request.calctype match {
      case Some(x) if x > 0 => calculatePeriodRevalRate(calculationPeriod, index, request)
      case _ => ""
    })

    if (filter != CsvFilter.Successful) {
      addCell(calculationPeriod.getPeriodErrorMessageReason.getOrElse(""))
      addCell(calculationPeriod.getPeriodErrorMessageWhat.getOrElse(""))
    }

    //    (List(calculationPeriod.startDate match {
    //      case Some(date) => date.toString("dd/MM/yyyy")
    //      case _ => ""
    //    },
    //      calculationPeriod.endDate.toString("dd/MM/yyyy"),
    //      calculationPeriod.gmpTotal,
    //      calculationPeriod.post88GMPTotal,
    //      request.dualCalc match {
    //        case Some(x) if x == 1 => calculationPeriod.dualCalcPost90TrueTotal.getOrElse("")
    //        case _ => ""
    //      },
    //      request.dualCalc match {
    //        case Some(x) if x == 1 => calculationPeriod.dualCalcPost90OppositeTotal.getOrElse("")
    //        case _ => ""
    //      },
    //      request.calctype match {
    //        case Some(x) if x > 0 => calculatePeriodRevalRate(calculationPeriod, index)
    //        case _ => ""
    //      }) ::: (filter match {
    //      case CsvFilter.Successful => Nil
    //      case _ => List(
    //        calculationPeriod.getPeriodErrorMessageReason.getOrElse(""),
    //        calculationPeriod.getPeriodErrorMessageWhat.getOrElse("")
    //      )
    //    })).mkString(",")

    private def calculatePeriodRevalRate(period: CalculationPeriod, index: Int, request: ValidCalculationRequest): String = {
      if (period.revaluationRate == 0)
        ""
      else {
        request.memberIsInScheme match {
          case Some(true) if Set(2, 3, 4) contains request.calctype.get => ""
          case Some(true) if request.calctype.get == 1 && index == 0 => ""
          case Some(false) =>
            if (request.calctype.get == 1 && index == 0 && !period.endDate.isBefore(LocalDate.now))
              ""
            else
              convertRevalRate(Some(period.revaluationRate))
          case _ => convertRevalRate(Some(period.revaluationRate))
        }
      }
    }

  }

  class CsvBuilder(cellCount: Int) {

    val rows = new ListBuffer[Row]()

    def addRow(row: Row): CsvBuilder = {
      rows += row
      this
    }

    def addRow(text: String): CsvBuilder = addRow(Row(List(Cell(text))))

    def build: String = {
      rows map { row =>
        row.toString + ("," * (cellCount - row.cells.size))
      } mkString "\n"
    }
  }

  def generateCsv(result: BulkCalculationRequest, csvFilter: Option[CsvFilter]): String = {

    val guidanceText = Messages("gmp.bulk.csv.guidance")
    val headerBuilder = new RowBuilder

    implicit val filter = csvFilter.get

    val maxPeriods = result.calculationRequests.map {
      _.calculationResponse match {
        case Some(x: GmpBulkCalculationResponse) if x.calculationPeriods.nonEmpty => x.calculationPeriods.size
        case _ => 0
      }
    }.max

    val headerRow = headerBuilder.addFilteredCell({
      case CsvFilter.All => Messages("gmp.status") // Add status column only for all
    })
      .addCell(Messages("gmp.bulk.csv.headers") split ",") // headers for all
      .addFilteredCells({
      case CsvFilter.All | CsvFilter.Successful => Messages("gmp.bulk.totals.headers") split "," // totals for all
    })
      .addRows(generatePeriodHeaders(maxPeriods))
      .addFilteredCells({
        case CsvFilter.All | CsvFilter.Failed => Messages("gmp.bulk.csv.globalerror.headers") split "," // global errors for all and failed
      })
      .build

    val csvBuilder = new CsvBuilder(headerRow.cells.size)

    csvBuilder.addRow(guidanceText)
    csvBuilder.addRow(headerRow)

    result.calculationRequests foreach { request =>
      val requestBuilder = new RequestRowBuilder(request)
      csvBuilder.addRow(requestBuilder.build)
    }

    val csv = csvBuilder.build

    val file = new File("/Users/stevenhobbs/output.csv")
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(csv)
    bw.close()

    csv

    //    val periodColumns = generatePeriodHeaders(maxPeriods, csvFilter)

    //    val columnHeaders = csvFilter match {
    //      case Some(CsvFilter.All) => Messages("gmp.status") + "," + Messages("gmp.bulk.csv.headers") + "," + Messages("gmp.bulk.totals.headers") + "," +
    //        (periodColumns match {
    //          case "" => "";
    //          case _ => periodColumns + ","
    //        }) +
    //        Messages("gmp.bulk.csv.globalerror.headers")
    //      case Some(CsvFilter.Failed) => Messages("gmp.bulk.csv.headers") + "," +
    //        (periodColumns match {
    //          case "" => "";
    //          case _ => periodColumns + ","
    //        }) +
    //        Messages("gmp.bulk.csv.globalerror.headers")
    //      case _ => Messages("gmp.bulk.csv.headers") + "," + Messages("gmp.bulk.totals.headers") + "," + periodColumns
    //}

    //    val columnCount = columnHeaders.split(",").size
    //    val errorColumn = columnCount - 2
    //
    //    val dataRows = result.calculationRequests.map { calculationRequest =>
    //
    //      val sumPeriod = (selector: (CalculationPeriod) => String) => {
    //        calculationRequest.calculationResponse match {
    //          case Some(calculationResponse) => calculationResponse.calculationPeriods.foldLeft(BigDecimal(0)) { (sum, period) => sum + BigDecimal(selector(period)) }
    //          case _ => 0
    //        }
    //      }
    //
    //      calculationRequest.validationErrors match {
    //        case Some(validationError) => {
    //          calculationRequest.validCalculationRequest match {
    //            case Some(request) => {
    //              val dataRow = List(
    //                validationError.isDefinedAt(RequestFieldKey.SCON.toString) match {
    //                  case true => validationError(RequestFieldKey.SCON.toString)
    //                  case _ => request.scon
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.NINO.toString) match {
    //                  case true => validationError(RequestFieldKey.NINO.toString)
    //                  case _ => request.nino
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.FORENAME.toString) match {
    //                  case true => validationError(RequestFieldKey.FORENAME.toString)
    //                  case _ => request.firstForename
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.SURNAME.toString) match {
    //                  case true => validationError(RequestFieldKey.SURNAME.toString)
    //                  case _ => request.surname
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.MEMBER_REFERENCE.toString) match {
    //                  case true => validationError(RequestFieldKey.MEMBER_REFERENCE.toString)
    //                  case _ => request.memberReference match {
    //                    case Some(x) => x
    //                    case _ => ""
    //                  }
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.CALC_TYPE.toString) match {
    //                  case true => validationError(RequestFieldKey.CALC_TYPE.toString)
    //                  case _ => convertCalcType(request.calctype)
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.DATE_OF_LEAVING.toString) match {
    //                  case true => validationError(RequestFieldKey.DATE_OF_LEAVING.toString)
    //                  case _ => convertDate(request.terminationDate)
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.GMP_DATE.toString) match {
    //                  case true => validationError(RequestFieldKey.GMP_DATE.toString)
    //                  case _ => convertDate(request.revaluationDate)
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.REVALUATION_RATE.toString) match {
    //                  case true => validationError(RequestFieldKey.REVALUATION_RATE.toString)
    //                  case _ => convertRevalRate(request.revaluationRate)
    //                },
    //                validationError.isDefinedAt(RequestFieldKey.OPPOSITE_GENDER.toString) match {
    //                  case true => validationError(RequestFieldKey.OPPOSITE_GENDER.toString)
    //                  case _ => request.dualCalc match {
    //                    case Some(1) => Messages("gmp.generic.yes")
    //                    case _ => ""
    //                  }
    //                }
    //              )
    //
    //              (csvFilter match {
    //                case Some(CsvFilter.All) => {
    //                  if (calculationRequest.hasErrors)
    //                    List[String](Messages("gmp.error")) ::: dataRow
    //                  else
    //                    List[String](Messages("gmp.success")) ::: dataRow
    //                }
    //                case _ => dataRow
    //              }).mkString(",")
    //            }
    //            case _ =>
    //              Seq(
    //                (RequestFieldKey.LINE_ERROR_TOO_FEW.toString, Messages("gmp.error.line.too_few")),
    //                (RequestFieldKey.LINE_ERROR_TOO_MANY.toString, Messages("gmp.error.line.too_many")),
    //                (RequestFieldKey.LINE_ERROR_EMPTY.toString, "" /* Intentionally empty */ )
    //              ) collectFirst {
    //                case x if validationError.isDefinedAt(x._1) => x
    //              } match {
    //                case Some(err) =>
    //                  csvFilter match {
    //                    case Some(CsvFilter.All) => s"${Messages("gmp.error")}${"," * errorColumn}${validationError(err._1)},${err._2}"
    //                    case Some(CsvFilter.Failed) => s"${"," * errorColumn}${validationError(err._1)},${err._2}"
    //                    case _ => ""
    //                  }
    //                case _ => ""
    //              }
    //          }
    //        }
    //        case _ => calculationRequest.validCalculationRequest match {
    //          case Some(v) => {
    //
    //            val dataRow = List(v.scon, v.nino, v.firstForename, v.surname, v.memberReference.getOrElse(""),
    //              convertCalcType(v.calctype), convertDate(v.terminationDate), determineGmpAtDate(calculationRequest), convertRevalRate(v.revaluationRate),
    //              v.dualCalc match {
    //                case Some(1) => Messages("gmp.generic.yes")
    //                case _ => Messages("gmp.generic.no")
    //              }) ::: (csvFilter match {
    //              case Some(CsvFilter.Failed) => Nil
    //              case _ =>
    //                List(
    //                  sumPeriod {
    //                    _.gmpTotal
    //                  },
    //                  sumPeriod {
    //                    _.post88GMPTotal
    //                  },
    //                  v.dualCalc match {
    //                    case Some(x) if x == 1 => sumPeriod {
    //                      _.dualCalcPost90TrueTotal match {
    //                        case Some(trueCalc) => trueCalc
    //                        case _ => ""
    //                      }
    //                    }
    //                    case _ => ""
    //                  },
    //                  v.dualCalc match {
    //                    case Some(x) if x == 1 => sumPeriod {
    //                      _.dualCalcPost90OppositeTotal match {
    //                        case Some(oppositeCalc) => oppositeCalc
    //                        case _ => "0"
    //                      }
    //                    }
    //                    case _ => ""
    //                  })
    //            }) ::: (calculationRequest.calculationResponse match {
    //              case Some(calcResponse) =>
    //                calcResponse.calculationPeriods.zipWithIndex.map { case (period, index) =>
    //                  generatePeriodColumnData(period, csvFilter, index)(v)
    //                }
    //              case _ => Nil
    //            }) ::: (fillTrailingCommas(csvFilter, calculationRequest, maxPeriods))
    //
    //            (csvFilter match {
    //              case Some(CsvFilter.All) => {
    //                if (calculationRequest.hasErrors)
    //                  List[String](Messages("gmp.error")) ::: dataRow
    //                else
    //                  List[String](Messages("gmp.success")) ::: dataRow
    //              }
    //              case _ => dataRow
    //            }).mkString(",")
    //          }
    //          case _ => ""
    //        }
    //      }
    //    }.mkString("\n")
    //
    //    guidanceText + ("," * (columnCount - 1)) + "\n" + columnHeaders + "\n" + dataRows

  }

  private def fillTrailingCommas(csvFilter: Option[CsvFilter], calculationRequest: CalculationRequest, maxPeriods: Int): List[String] = {
    csvFilter match {
      case Some(CsvFilter.Successful) =>
        addTrailingCommas(csvFilter, calculationRequest, maxPeriods)
      case _ =>
        addTrailingCommas(csvFilter, calculationRequest, maxPeriods) :::
          List(
            calculationRequest.getGlobalErrorMessageReason match {
              case Some(msg) => msg
              case _ => ""
            },
            calculationRequest.getGlobalErrorMessageWhat match {
              case Some(msg) => msg
              case _ => ""
            }
          )
    }
  }

  private def addTrailingCommas(csvFilter: Option[CsvFilter], calculationRequest: CalculationRequest, maxPeriods: Int): List[String] = {
    val periods = calculationRequest.calculationResponse match {
      case Some(calcResponse) => calcResponse.calculationPeriods.size
      case _ => 0
    }
    csvFilter match {
      case Some(CsvFilter.Successful) => List.fill(maxPeriods - periods)(",,,,,,")
      case _ => List.fill(maxPeriods - periods)(",,,,,,,,")
    }
  }

  private def convertCalcType(calcType: Option[Int]): String = {
    calcType match {
      case Some(0) => Messages("gmp.calc_type.leaving")
      case Some(1) => Messages("gmp.calc_type.specific_date")
      case Some(2) => Messages("gmp.calc_type.payable_age")
      case Some(3) => Messages("gmp.calc_type.survivor")
      case _ => Messages("gmp.calc_type.spa")
    }
  }

  private def generatePeriodHeaders(periodCount: Int)(implicit filter: CsvFilter): Traversable[Row] = {

    (1 to periodCount).map { index =>

      val builder = new PeriodHeaderRowBuilder(index)

      builder.addMsgCell("gmp.period.start_date")
        .addMsgCell("gmp.period.end_date")
        .addMsgCell("gmp.period.total")
        .addMsgCell("gmp.period.post_88")
        .addMsgCell("gmp.period.post_90_true")
        .addMsgCell("gmp.period.post_90_opp")
        .addMsgCell("gmp.period.reval_rate")
        .addFilteredCell({
          case CsvFilter.All | CsvFilter.Failed => builder.buildCellText("gmp.period.error")
        })
        .addFilteredCell({
          case CsvFilter.All | CsvFilter.Failed => builder.buildCellText("gmp.period.what")
        })

      builder.build
    }

  }

  //  private def generatePeriodHeaders(count: Int, csvFilter: Option[CsvFilter]): String = {
  //    (1 to count).map {
  //      i =>
  //        (List(s"${
  //          Messages("gmp.period")
  //        } $i ${
  //          Messages("gmp.period.start_date")
  //        }",
  //          s"${
  //            Messages("gmp.period")
  //          } $i ${
  //            Messages("gmp.period.end_date")
  //          }",
  //          s"${
  //            Messages("gmp.period")
  //          } $i ${
  //            Messages("gmp.period.total")
  //          }",
  //          s"${
  //            Messages("gmp.period")
  //          } $i ${
  //            Messages("gmp.period.post_88")
  //          }",
  //          s"${
  //            Messages("gmp.period")
  //          } $i ${
  //            Messages("gmp.period.post_90_true")
  //          }",
  //          s"${
  //            Messages("gmp.period")
  //          } $i ${
  //            Messages("gmp.period.post_90_opp")
  //          }",
  //          s"${
  //            Messages("gmp.period")
  //          } $i ${
  //            Messages("gmp.period.reval_rate")
  //          }") ::: (csvFilter match {
  //          case Some(CsvFilter.Successful) => Nil
  //          case _ => List(
  //            s"${
  //              Messages("gmp.period")
  //            } $i ${
  //              Messages("gmp.period.error")
  //            }",
  //            s"${
  //              Messages("gmp.period")
  //            } $i ${
  //              Messages("gmp.period.what")
  //            }"
  //          )
  //        })).mkString(",")
  //    }.mkString(",")
  //  }

  private def convertDate(date: Option[String]): String = {

    val DATE_FORMAT: String = "dd/MM/yyyy"
    val inputDateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")

    date match {
      case Some(d) => LocalDate.parse(d, inputDateFormatter).toString(DATE_FORMAT)
      case _ => ""
    }
  }


  private def convertRevalRate(revalRate: Option[Int]): String = {
    revalRate match {
      case Some(0) => RevaluationRate.HMRC
      case Some(1) => RevaluationRate.S148
      case Some(2) => RevaluationRate.FIXED
      case Some(3) => RevaluationRate.LIMITED
      case _ => ""
    }
  }

  //  private def generatePeriodColumnData(calculationPeriod: CalculationPeriod, csvFilter: Option[CsvFilter], index: Int)(implicit request: ValidCalculationRequest): String = {
  //    (List(calculationPeriod.startDate match {
  //      case Some(date) => date.toString("dd/MM/yyyy")
  //      case _ => ""
  //    },
  //      calculationPeriod.endDate.toString("dd/MM/yyyy"),
  //      calculationPeriod.gmpTotal,
  //      calculationPeriod.post88GMPTotal,
  //      request.dualCalc match {
  //        case Some(x) if x == 1 => calculationPeriod.dualCalcPost90TrueTotal.getOrElse("")
  //        case _ => ""
  //      },
  //      request.dualCalc match {
  //        case Some(x) if x == 1 => calculationPeriod.dualCalcPost90OppositeTotal.getOrElse("")
  //        case _ => ""
  //      },
  //      request.calctype match {
  //        case Some(x) if x > 0 => calculatePeriodRevalRate(calculationPeriod, index)
  //        case _ => ""
  //      }) ::: (csvFilter match {
  //      case Some(CsvFilter.Successful) => Nil
  //      case _ => List(
  //        calculationPeriod.getPeriodErrorMessageReason.getOrElse(""),
  //        calculationPeriod.getPeriodErrorMessageWhat.getOrElse("")
  //      )
  //    })).mkString(",")
  //  }

  private def determineGmpAtDate(request: CalculationRequest): String = {

    val DATE_FORMAT: String = "dd/MM/yyyy"
    val inputDateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    request.validCalculationRequest.map {

      calculationRequest =>

        request.calculationResponse.map {
          calculationResponse =>

            calculationRequest.calctype match {

              case Some(2) => calculationResponse.payableAgeDate.map {
                dod => dod.toString(DATE_FORMAT)
              }.getOrElse("")


              case Some(3) => {
                calculationRequest.revaluationDate.map {
                  d => LocalDate.parse(d, inputDateFormatter).toString(DATE_FORMAT)
                }.getOrElse(calculationResponse.dateOfDeath.map {
                  dod => dod.toString(DATE_FORMAT)
                }.getOrElse(""))
              }

              case Some(4) => calculationResponse.spaDate.map {
                dod => dod.toString(DATE_FORMAT)
              }.getOrElse("")

              case _ if !calculationRequest.revaluationDate.isDefined => calculationResponse.calculationPeriods.headOption.map {
                period => period.endDate.toString(DATE_FORMAT)
              }.getOrElse("")

              case _ => convertDate(calculationRequest.revaluationDate)
            }

        }.getOrElse("")
    }.getOrElse("")

  }

  def generateContributionsCsv(request: BulkCalculationRequest): String = {

    Messages("gmp.bulk.csv.contributions.headers") + "\n" + request.calculationRequests.map {
      case calcRequest => {
        calcRequest.validCalculationRequest match {
          case Some(validCalcRequest) => {

            // Write out each period
            val periodRows = calcRequest.calculationResponse match {
              case Some(response) => response.calculationPeriods.map {
                generateContributionsPeriodRowData
              }
              case _ => List.empty[String]
            }

            val firstRow = List(
              validCalcRequest.scon,
              validCalcRequest.nino,
              validCalcRequest.firstForename,
              validCalcRequest.surname,
              periodRows.size match {
                case 0 => ""
                case _ => periodRows.head
              }).mkString(",")

            firstRow + generateLineSeparator(calcRequest) + (periodRows.size match {
              case 0 => ""
              case _ => periodRows.tail.map {
                "," * 4 + _
              }.mkString("\n")
            })

          }
          case _ => ""
        }
      }
    }.mkString("\n")
  }

  private def generateContributionsPeriodRowData(period: CalculationPeriod): String = {

    List(
      (period.startDate match {
        case Some(d) => d.toString("dd/MM/yyyy")
        case _ => ""
      }) + " - " + period.endDate.toString("dd/MM/yyyy"),
      period.contsAndEarnings match {
        case Some(c) =>
          val map = c.foldLeft(Map[Int, String]()) {
            (m, earnings) => m + (earnings.taxYear -> earnings.contEarnings)
          }

          (1978 to 1998).map {
            map.getOrElse(_, "")
          }.mkString(",")
        case _ => "," * 20
      }
    ).mkString(",")

  }

  private def generateLineSeparator(calcRequest: CalculationRequest): String = {
    calcRequest.calculationResponse match {
      case Some(response) if response.calculationPeriods.size > 1 => "\n"
      case _ => ""
    }
  }

}

object CsvGenerator extends CsvGenerator
