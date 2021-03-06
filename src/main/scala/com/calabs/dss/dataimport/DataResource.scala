package com.calabs.dss.dataimport

import java.io.File
import java.net.URL

import com.calabs.dss.dataimport.Implicits._
import com.calabs.dss.dataimport.Parsing.Tags
import com.fasterxml.jackson.databind.ObjectMapper
import io.gatling.jsonpath.JsonPath
import org.apache.poi.ss.usermodel.{Cell, Sheet, WorkbookFactory}
import org.dom4j.{DocumentHelper}
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JObject, JString}

import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success, Try}
import scala.collection.mutable.{Map => MutableMap}
import scala.collection.JavaConverters._
import org.json4s.Xml.{toJson}

import scala.xml.XML

/**
 * Created by Jordi Aranda
 * <jordi.aranda@bsc.es>
 * 20/11/14
 */

object ResourceType {
  val WEBSITE = "website"
  val JSON = "json"
  val JSON_API = "jsonAPI"
  val XML = "xml"
  val XML_API = "xmlAPI"
  val XLSX = "xlsx"
}

case class DataResourceConfig(config: Map[String, Any])
case class DataResourceMapping(mapping: List[Map[String,String]])

trait DataResourceExtractor {
  def extractDocuments: Try[(List[Document], List[Document])]
}

sealed trait DataResource {
  self: DataResourceExtractor =>
  def config: DataResourceConfig
  def mapping: DataResourceMapping
}

trait APIConnection {
  def prepareConnection(dataSource: String, headers: Map[String, String]): BufferedSource = {
    val connection = new URL(dataSource).openConnection()
    headers.foreach{ case (k,v) => connection.setRequestProperty(k,v.toString)}
    Source.fromInputStream(connection.getInputStream)
  }
}

trait JSONResourceBase extends DataResource with DataResourceExtractor {
  lazy val mapper = new ObjectMapper
  def parseJson(s: String) = mapper.readValue(s, classOf[Object])
}

trait XMLResourceBase extends DataResource with DataResourceExtractor

trait XLSXResourceBase extends DataResource with DataResourceExtractor {
  val truthyValues = List("X", "x", "Y", "y", "yes")
  val falsyValues = List("N", "n", "no")

  def openSheet(path: String, sheet: String) : Sheet = WorkbookFactory.create(new File(path)).getSheet(sheet)
}

case class JSONResource(config: DataResourceConfig, mapping: DataResourceMapping) extends JSONResourceBase {

  import Implicits._

  override def extractDocuments: Try[(List[Document], List[Document])] = {
    val c = config.config
    Try{
      (c.get("source"), c.get("resourceType")) match {
        case (Some(source: String), Some(resourceType: String)) => {
          val m = mapping.mapping
          // Load the JSON resource
          val jsonFile = resourceType match {
            case ResourceType.JSON => Source.fromFile(source, "utf-8")
            case _ => throw new IllegalArgumentException(s"Wrong resource type, must be ${ResourceType.JSON} for JSON data resources.")
          }
          val jsonInput = jsonFile.mkString
          val json = parseJson(jsonInput)
          val documents = m.map(documentMapping => {
            // Check document mapping types are supported
            val mapping = Try(Parsing.checkProps(documentMapping))

            mapping match {
              case Success(mapping) => {
                // Check searchable criteria
                val validSearchableCriteria = Try(Parsing.validSearchableCriteria(mapping))
                validSearchableCriteria match {
                  case Success(b) => {
                    // Potential result
                    val propsMap = MutableMap[String, Any]()
                    mapping.foreach {
                      case (metric, key) => {
                        key match {
                          case JString(s) => {
                            if (Parsing.isRawValue(s)) {
                              propsMap.update(metric, Parsing.getRawValue(s))
                            } else {
                              val metricRawValue = JsonPath.query(s, json)
                              val metricValue = metricRawValue match {
                                case Left(error) => throw new IllegalArgumentException(s"Some error occurred when looking up metric $metric: ${error.reason}.")
                                case Right(value) => asScalaRecursive(value.toList)
                              }
                              propsMap.update(metric, metricValue)
                            }
                          }
                          case JObject(o) => {
                            val otherPropsMap = MutableMap[String, Any]()
                            o.foreach{ case(field, value) => {
                              value match {
                                case JString(s) => {
                                  if (Parsing.isRawValue(s)) {
                                    otherPropsMap.update(field, Parsing.getRawValue(s))
                                  } else {
                                    val metricRawValue = JsonPath.query(s, json)
                                    val metricValue = metricRawValue match {
                                      case Left(error) => throw new IllegalArgumentException(s"Some error occurred when looking up metric $metric: ${error.reason}.")
                                      case Right(value) => asScalaRecursive(value.toList)
                                    }
                                    otherPropsMap.update(field, metricValue)
                                  }
                                }
                                case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                              }
                              propsMap.update(metric, otherPropsMap)
                            }}
                          }
                          case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                        }
                      }
                    }

                    val propsChecked = Try(Parsing.checkProps(propsMap.toMap))
                    propsChecked match {
                      case Success(props) => Parsing.extractDocument(props)
                      case Failure(e) => throw new IllegalArgumentException(s"An error occurred when checking element properties for element $documentMapping: ${e.getMessage}.")
                    }
                  }
                  case Failure(e) => throw new IllegalArgumentException(s"Invalid searchable criteria in document mapping: ${e.getMessage}")
                }
              }
              case Failure(e) => throw new IllegalArgumentException(s"Invalid document mapping: ${e.getMessage}")
            }
          })
          (documents.filter(_.isVertex), documents.filter(_.isEdge))
        }
        case _ => throw new IllegalArgumentException(s"Wrong number of parameters expected in JSON resource configuration file ('source' and 'resourceType' are required)")
      }
    }
  }

}

case class JSONAPIResource(config: DataResourceConfig, mapping: DataResourceMapping) extends JSONResourceBase with APIConnection {

  import Implicits._

  override def extractDocuments: Try[(List[Document], List[Document])] = {
    val c = config.config
    Try{
      (c.get("source"), c.get("resourceType"), c.get("headers")) match {
        case (Some(source: String), Some(resourceType: String), Some(headers: Map[String, String])) => {
          val m = mapping.mapping
          // Load the JSON resource
          val jsonFile = resourceType match {
            case ResourceType.JSON_API => prepareConnection(source, headers)
            case _ => throw new IllegalArgumentException(s"Wrong resource type, must be ${ResourceType.JSON_API} for JSON API data resources.")
          }
          val jsonInput = jsonFile.mkString
          val json = parseJson(jsonInput)
          val documents = m.map(documentMapping => {
            // Check document mapping types are supported
            val mapping = Try(Parsing.checkProps(documentMapping))

            mapping match {
              case Success(mapping) => {
                // Check searchable criteria
                val validSearchableCriteria = Try(Parsing.validSearchableCriteria(mapping))
                validSearchableCriteria match {
                  case Success(b) => {
                    // Potential result
                    val propsMap = MutableMap[String, Any]()
                    mapping.foreach {
                      case (metric, key) => {
                        key match {
                          case JString(s) => {
                            if (Parsing.isRawValue(s)) {
                              propsMap.update(metric, Parsing.getRawValue(s))
                            } else {
                              val metricRawValue = JsonPath.query(s, json)
                              val metricValue = metricRawValue match {
                                case Left(error) => throw new IllegalArgumentException(s"Some error occurred when looking up metric $metric: ${error.reason}.")
                                case Right(value) => asScalaRecursive(value.toList)
                              }
                              propsMap.update(metric, metricValue)
                            }
                          }
                          case JObject(o) => {
                            val otherPropsMap = MutableMap[String, Any]()
                            o.foreach{ case(field, value) => {
                              value match {
                                case JString(s) => {
                                  if (Parsing.isRawValue(s)) {
                                    otherPropsMap.update(field, Parsing.getRawValue(s))
                                  } else {
                                    val metricRawValue = JsonPath.query(s, json)
                                    val metricValue = metricRawValue match {
                                      case Left(error) => throw new IllegalArgumentException(s"Some error occurred when looking up metric $metric: ${error.reason}.")
                                      case Right(value) => asScalaRecursive(value.toList)
                                    }
                                    otherPropsMap.update(field, metricValue)
                                  }
                                }
                                case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                              }
                              propsMap.update(metric, otherPropsMap)
                            }}
                          }
                          case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                        }
                      }
                    }

                    val propsChecked = Try(Parsing.checkProps(propsMap.toMap))
                    propsChecked match {
                      case Success(props) => Parsing.extractDocument(props)
                      case Failure(e) => throw new IllegalArgumentException(s"An error occurred when checking element properties for element $documentMapping: ${e.getMessage}.")
                    }
                  }
                  case Failure(e) => throw new IllegalArgumentException(s"Invalid searchable criteria in document mapping: ${e.getMessage}")
                }
              }
              case Failure(e) => throw new IllegalArgumentException(s"Invalid document mapping: ${e.getMessage}")
            }
          })
          (documents.filter(_.isVertex), documents.filter(_.isEdge))
        }
        case _ => throw new IllegalArgumentException(s"Wrong number of parameters expected in JSON API resource configuration file ('source' and 'resourceType' are required)")
      }
    }
  }

}

case class XMLResource(config: DataResourceConfig, mapping: DataResourceMapping) extends XMLResourceBase {

  import Implicits._

  override def extractDocuments: Try[(List[Document], List[Document])] = {
    val c = config.config
    Try{
      (c.get("source"), c.get("resourceType")) match {
        case (Some(source: String), Some(resourceType: String)) => {
          val m = mapping.mapping
          // Load the XML resource
          val xmlFile = resourceType match {
            case ResourceType.XML => Source.fromFile(source, "utf-8")
            case _ => throw new IllegalArgumentException(s"Wrong resource type, must be ${ResourceType.XML} for XML data resources.")
          }
          val xmlInput = xmlFile.mkString
          val xml = DocumentHelper.parseText(xmlInput)
          val documents = m.map(documentMapping => {
            // Check document mapping types are supported
            val mapping = Try(Parsing.checkProps(documentMapping))

            mapping match {
              case Success(mapping) => {
                // Check searchable criteria
                val validSearchableCriteria = Try(Parsing.validSearchableCriteria(mapping))
                validSearchableCriteria match {
                  case Success(b) => {
                    // Potential result
                    val propsMap = MutableMap[String, Any]()
                    mapping.foreach {
                      case (metric, key) => {
                        key match {
                          case JString(s) => {
                            if (Parsing.isRawValue(s)) {
                              propsMap.update(metric, asScalaRecursive(Parsing.getRawValue(s)))
                            } else {
                              propsMap.update(metric, asScalaRecursive(xml.selectNodes(s)))
                            }
                          }
                          case JObject(o) => {
                            val otherPropsMap = MutableMap[String, Any]()
                            o.foreach{ case(field, value) => {
                              value match {
                                case JString(s) => {
                                  if (Parsing.isRawValue(s)) {
                                    propsMap.update(metric, asScalaRecursive(Parsing.getRawValue(s)))
                                  } else {
                                    propsMap.update(metric, asScalaRecursive(xml.selectNodes(s)))
                                  }
                                }
                                case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                              }
                              propsMap.update(metric, otherPropsMap)
                            }}
                          }
                          case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                        }
                      }
                    }

                    val propsChecked = Try(Parsing.checkProps(propsMap.toMap))
                    propsChecked match {
                      case Success(props) => Parsing.extractDocument(props)
                      case Failure(e) => throw new IllegalArgumentException(s"An error occurred when checking element properties for element $documentMapping: ${e.getMessage}.")
                    }
                  }
                  case Failure(e) => throw new IllegalArgumentException(s"Invalid searchable criteria in document mapping: ${e.getMessage}")
                }
              }
              case Failure(e) => throw new IllegalArgumentException(s"Invalid document mapping: ${e.getMessage}")
            }

          })
          (documents.filter(_.isVertex), documents.filter(_.isEdge))
        }
        case _ => throw new IllegalArgumentException(s"Wrong number of parameters expected in XML resource configuration file ('source' and 'resourceType' are required)")
      }
    }
  }

}

case class XMLAPIResource(config: DataResourceConfig, mapping: DataResourceMapping) extends XMLResourceBase with APIConnection {

  import Implicits._

  override def extractDocuments: Try[(List[Document], List[Document])] = {
    val c = config.config
    // Potential result
    val mutableMap = MutableMap[String, Any]()
    Try{
      (c.get("source"), c.get("resourceType"), c.get("headers")) match {
        case (Some(source: String), Some(resourceType: String), Some(headers: Map[String, String])) => {
          val m = mapping.mapping
          // Load the XML resource
          val xmlFile = resourceType match {
            case ResourceType.XML_API => prepareConnection(source, headers)
            case _ => throw new IllegalArgumentException(s"Wrong resource type, must be ${ResourceType.XML_API} for XML data resources.")
          }
          val xmlInput = xmlFile.mkString
          val xml = DocumentHelper.parseText(xmlInput)
          val documents = m.map(documentMapping => {
            // Check document mapping types are supported
            val mapping = Try(Parsing.checkProps(documentMapping))

            mapping match {
              case Success(mapping) => {
                // Check searchable criteria
                val validSearchableCriteria = Try(Parsing.validSearchableCriteria(mapping))
                validSearchableCriteria match {
                  case Success(b) => {
                    // Potential result
                    val propsMap = MutableMap[String, Any]()
                    mapping.foreach {
                      case (metric, key) => {
                        key match {
                          case JString(s) => {
                            if (Parsing.isRawValue(s)) {
                              propsMap.update(metric, asScalaRecursive(Parsing.getRawValue(s)))
                            } else {
                              propsMap.update(metric, asScalaRecursive(xml.selectNodes(s)))
                            }
                          }
                          case JObject(o) => {
                            val otherPropsMap = MutableMap[String, Any]()
                            o.foreach{ case(field, value) => {
                              value match {
                                case JString(s) => {
                                  if (Parsing.isRawValue(s)) {
                                    propsMap.update(metric, asScalaRecursive(Parsing.getRawValue(s)))
                                  } else {
                                    propsMap.update(metric, asScalaRecursive(xml.selectNodes(s)))
                                  }
                                }
                                case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                              }
                              propsMap.update(metric, otherPropsMap)
                            }}
                          }
                          case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                        }
                      }
                    }

                    val propsChecked = Try(Parsing.checkProps(propsMap.toMap))
                    propsChecked match {
                      case Success(props) => Parsing.extractDocument(props)
                      case Failure(e) => throw new IllegalArgumentException(s"An error occurred when checking element properties for element $documentMapping: ${e.getMessage}.")
                    }
                  }
                  case Failure(e) => throw new IllegalArgumentException(s"Invalid searchable criteria in document mapping: ${e.getMessage}")
                }
              }
              case Failure(e) => throw new IllegalArgumentException(s"Invalid document mapping: ${e.getMessage}")
            }

          })
          (documents.filter(_.isVertex), documents.filter(_.isEdge))
        }
        case _ => throw new IllegalArgumentException(s"Wrong number of parameters expected in XML API resource configuration file ('source' and 'resourceType' are required)")
      }
    }
  }

}

case class XLSXResource(config: DataResourceConfig, mapping: DataResourceMapping) extends XLSXResourceBase {

  def getCellValue(metric: String, s: Sheet, rowColumn: String) : Any = {
    val metricRawValue = rowColumn.split(Tags.MAP_SEPARATOR)
    if (metricRawValue.length != 2 && metricRawValue.length != 3) throw new IllegalArgumentException(s"Wrong metric path/flag for metric $metric ($rowColumn): value must be three numbers (separated by comma) indicating row/cell position and flag (behaviour) respectively. Flag can be optionally omitted, indicating default behaviour")
    else {
      val (row, column, flag) = if (metricRawValue.length == 2) {
        val r = Try(metricRawValue.head.toInt - 1)
        val c = Try(metricRawValue.tail.head.toInt - 1)
        (r, c) match {
          case (Failure(_), _) => throw new IllegalArgumentException(s"Invalid row number in $rowColumn")
          case (_, Failure(_)) => throw new IllegalArgumentException(s"Invalid column number in $rowColumn")
          case _ => (r.get, c.get, None)
        }
      } else {
        val r = Try(metricRawValue.head.toInt - 1)
        val c = Try(metricRawValue.tail.head.toInt - 1)
        val f = Try(Some(metricRawValue.tail.tail.head.toInt))
        (r,c,f) match {
          case (Failure(_), _, _) => throw new IllegalArgumentException(s"Invalid row number in $rowColumn")
          case (_, Failure(_), _) => throw new IllegalArgumentException(s"Invalid column number in $rowColumn")
          case (_, _, Failure(_)) => throw new IllegalArgumentException(s"Invalid flag number in $rowColumn")
          case _ => (r.get, c.get, f.get)
        }
      }
      val cell = Option(s.getRow(row).getCell(column))
      cell match {
        case Some(c) => {
          val value = c.getCellType match {
            case Cell.CELL_TYPE_BOOLEAN => c.getBooleanCellValue
            case Cell.CELL_TYPE_NUMERIC => c.getNumericCellValue
            case Cell.CELL_TYPE_STRING => {
              val cellValue = c.getStringCellValue
              if (truthyValues.contains(cellValue)) true
              else if (falsyValues.contains(cellValue)) false
              else cellValue
            }
            case _ => throw new IllegalArgumentException(s"Cell located in sheet ${s.getSheetName} ($row,$column) is empty or contains an invalid value.")
          }
          flag match {
            case Some(f) => f match {
              case 0 => value // default behaviour
              case 1 => value match { // use flags (0 => default behaviour, 1 => check if cell is empty or not (value is then false or true respectively)
                case b: Boolean => true
                case n: Double => true
                case s: String => if (s == "") false else true
              }
              case _ => throw new IllegalArgumentException(s"Invalid flag: only 0 (default behaviour) and 1 (if cell empty => false, true otherwise) are valid flags")
            }
            case None => value
          }
        }
        case None => throw new IllegalArgumentException(s"No cell is available in row $row and column $column")
      }
    }
  }

  override def extractDocuments: Try[(List[Document], List[Document])] = {
    val c = config.config
    // Potential result
    val mutableMap = MutableMap[String, Any]()
    Try {
      (c.get("source"), c.get("resourceType"), c.get("sheet")) match {
        case (Some(source: String), Some(resourceType: String), Some(sheet: String)) => {
          val m = mapping.mapping
          val sh = resourceType match {
            case ResourceType.XLSX => {
              Option(openSheet(source, sheet)) match {
                case Some(s) => s
                case None => throw new IllegalArgumentException(s"No sheet named $sheet was found in file $source")
              }
            }
            case _ => throw new IllegalArgumentException(s"Wrong resource type, must be ${ResourceType.XLSX} for XLSX data resources.")
          }

          val documents = m.map(documentMapping => {
            // Check document mapping types are supported
            val mapping = Try(Parsing.checkProps(documentMapping))

            mapping match {
              case Success(mapping) => {
                // Check searchable criteria
                val validSearchableCriteria = Try(Parsing.validSearchableCriteria(mapping))

                validSearchableCriteria match {
                  case Success(b) => {
                    // Potential result
                    val propsMap = MutableMap[String, Any]()
                    mapping.foreach {
                      case (metric, key) => {
                        key match {
                          case JString(s) => {
                            if (Parsing.isRawValue(s)) {
                              propsMap.update(metric, asScalaRecursive(Parsing.getRawValue(s)))
                            } else {
                              propsMap.update(metric, asScalaRecursive(getCellValue(metric, sh, s)))
                            }
                          }
                          case JObject(o) => {
                            val otherPropsMap = MutableMap[String, Any]()
                            o.foreach { case (field, value) => {
                              value match {
                                case JString(s) => {
                                  if (Parsing.isRawValue(s)) {
                                    propsMap.update(metric, asScalaRecursive(Parsing.getRawValue(s)))
                                  } else {
                                    propsMap.update(metric, asScalaRecursive(getCellValue(metric, sh, s)))
                                  }
                                }
                                case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                              }
                              propsMap.update(metric, otherPropsMap)
                            }
                            }
                          }
                          case _ => throw new IllegalArgumentException(s"Unexpected type in metric ($metric, $key)")
                        }
                      }
                    }

                    val propsChecked = Try(Parsing.checkProps(propsMap.toMap))
                    propsChecked match {
                      case Success(props) => Parsing.extractDocument(props)
                      case Failure(e) => throw new IllegalArgumentException(s"An error occurred when checking element properties for element $documentMapping: ${e.getMessage}.")
                    }
                  }
                  case Failure(e) => throw new IllegalArgumentException(s"Invalid searchable criteria in document mapping: ${e.getMessage}")
                }
              }
              case Failure(e) => throw new IllegalArgumentException(s"Invalid document mapping: ${e.getMessage}")
            }
          })

          (documents.filter(_.isVertex), documents.filter(_.isEdge))
        }
        case _ => throw new IllegalArgumentException(s"Wrong number of parameters expected in XML API resource configuration file ('source' and 'resourceType' are required)")
      }
    }
  }

}