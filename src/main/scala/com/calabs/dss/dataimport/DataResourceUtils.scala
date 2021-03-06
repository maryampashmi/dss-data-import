package com.calabs.dss.dataimport

import scala.io.Source
import scala.util.{Failure, Success, Try}
import scala.collection.mutable.{Map => MutableMap}

/**
 * Created by Jordi Aranda
 * <jordi.aranda@bsc.es>
 * 3/12/14
 */

/**
 * A base Mapper trait which extracts document key/values mappings. Every key represents
 * a document property and every value where this document property can be found within a
 * document resource so further on it can be extracted.
 */
trait Mapper {
  def load(path: String) : Try[(List[Map[String, String]])] = {
    val sourceFile = Source.fromFile(path)
    val sourceContent = sourceFile.getLines().toList
    Try(Parsing.extractMappings(sourceContent))
  }
}

case class DataResourceMapper() extends Mapper

/**
 * A base configuration type-class. Every data resource has a configuration which defines where
 * this resource can be found, the resource type and some extra parameters, which are different between
 * different data resources.
 * @tparam A
 */
trait Config[A] {
  def load(path: String) : Try[Map[String, Any]]
  def check(config: Map[String, Any]) : Map[String, Any]
}

object Config {

  implicit val jsonResourceConfig : Config[JSONResource] = new Config[JSONResource]{

    override def load(path: String): Try[Map[String, Any]] = {
      // JSON resource load
      val sourceFile = Source.fromFile(path)
      val sourceContent = sourceFile.getLines().toList
      Try(check(Parsing.extractConfig(sourceContent)))
    }

    override def check(config: Map[String, Any]) : Map[String, Any] = {
      // Resource config check (mandatory properties are: source and resourceType)
      val source = config.get("source") match {
        case Some(source) => source
        case None => throw new NoSuchElementException("Missing source parameter in resource config file")
      }
      val resourceType = config.get("resourceType") match {
        case Some(resourceType) if resourceType == ResourceType.JSON => resourceType
        case Some(resourceType) => throw new IllegalArgumentException(s"Invalid resource type for JSON resource (should be ${ResourceType.JSON} and not $resourceType)")
        case None => throw new NoSuchElementException(s"Missing source parameter 'resourceType' in resource config file")
      }
      Map[String, Any]("source" -> source, "resourceType" -> resourceType)
    }

  }

  implicit val jsonApiResourceConfig : Config[JSONAPIResource] = new Config[JSONAPIResource]{

    override def load(path: String): Try[Map[String, Any]] = {
      // JSON API resource load
      val sourceFile = Source.fromFile(path)
      val sourceContent = sourceFile.getLines().toList
      Try(check(Parsing.extractConfig(sourceContent)))
    }

    override def check(config: Map[String, Any]) : Map[String, Any] = {
      // JSON API resource config check (mandatory properties are: source, resourceType)
      val source = config.get("source") match {
        case Some(source) => source.toString
        case None => throw new NoSuchElementException("Missing source parameter in resource config file.")
      }
      val resourceType = config.get("resourceType") match {
        case Some(resourceType) if resourceType == ResourceType.JSON_API => resourceType
        case Some(resourceType) => throw new IllegalArgumentException(s"Invalid resource type for JSON API resource (should be ${ResourceType.JSON_API} and not $resourceType)")
        case None => throw new NoSuchElementException(s"Missing source parameter 'resourceType' in resource config file")
      }
      val headers = config.get("headers") match {
        case Some(headers) => {
          val httpHeaders = MutableMap[String, String]()
          val rawHeaders = headers.toString.split(Parsing.Tags.MAP_SEPARATOR)
          rawHeaders.foreach(rawHeader => {
            val headerKeyValue = rawHeader.split(Parsing.Tags.MAP_KEY_VALUE_SEPARATOR)
            if(headerKeyValue.length != 2) throw new IllegalArgumentException("Wrong headers specification in resource config file.")
            else httpHeaders.update(headerKeyValue.head, headerKeyValue.tail.head)
          })
          httpHeaders.toMap
        }
        case None => Map[String, Any]()
      }
      Map[String, Any]("source" -> source, "resourceType" -> resourceType, "headers" -> headers)
    }

  }

  implicit val xmlResourceConfig : Config[XMLResource] = new Config[XMLResource]{

    override def load(path: String): Try[Map[String, Any]] = {
      // XML resource load
      val sourceFile = Source.fromFile(path)
      val sourceContent = sourceFile.getLines().toList
      Try(check(Parsing.extractConfig(sourceContent)))
    }

    override def check(config: Map[String, Any]) : Map[String, Any] = {
      // XML resource config check (mandatory properties are: source and resourceType)
      val source = config.get("source") match {
        case Some(source) => source.toString
        case None => throw new NoSuchElementException("Missing source parameter in resource config file.")
      }
      val resourceType = config.get("resourceType") match {
        case Some(resourceType) if resourceType == ResourceType.XML => resourceType
        case Some(resourceType) => throw new IllegalArgumentException(s"Invalid resource type for XML resource (should be ${ResourceType.XML} and not $resourceType)")
        case None => throw new NoSuchElementException(s"Missing source parameter 'resourceType' in resource config file")
      }
      Map[String, Any]("source" -> source, "resourceType" -> resourceType)
    }

  }

  implicit val xmlApiResourceConfig : Config[XMLAPIResource] = new Config[XMLAPIResource]{

    override def load(path: String): Try[Map[String, Any]] = {
      // XML API resource load
      val sourceFile = Source.fromFile(path)
      val sourceContent = sourceFile.getLines().toList
      Try(check(Parsing.extractConfig(sourceContent)))
    }

    override def check(config: Map[String, Any]) : Map[String, Any] = {
      // XML API resource config check (mandatory properties are: source, resourceType and headers)
      val source = config.get("source") match {
        case Some(source) => source.toString
        case None => throw new NoSuchElementException("Missing source parameter in resource config file.")
      }
      val resourceType = config.get("resourceType") match {
        case Some(resourceType) if resourceType == ResourceType.XML_API => resourceType
        case Some(resourceType) => throw new IllegalArgumentException(s"Invalid resource type for XML API resource (should be ${ResourceType.XML_API} and not $resourceType)")
        case None => throw new NoSuchElementException(s"Missing source parameter 'resourceType' in resource config file")
      }
      val headers = config.get("headers") match {
        case Some(headers) => {
          val httpHeaders = MutableMap[String, String]()
          val rawHeaders = headers.toString.split(Parsing.Tags.MAP_SEPARATOR)
          rawHeaders.foreach(rawHeader => {
            val headerKeyValue = rawHeader.split(Parsing.Tags.MAP_KEY_VALUE_SEPARATOR)
            if(headerKeyValue.length != 2) throw new IllegalArgumentException("Wrong headers specification in resource config file.")
            else httpHeaders.update(headerKeyValue.head, headerKeyValue.tail.head)
          })
          httpHeaders.toMap
        }
        case None => Map[String,Any]()
      }
      Map[String, Any]("source" -> source, "resourceType" -> resourceType, "headers" -> headers)
    }
  }

  implicit val xlsxResourceConfig : Config[XLSXResource] = new Config[XLSXResource]{

    override def load(path: String): Try[Map[String,Any]] = {
      // XLSX resource load
      val sourceFile = Source.fromFile(path)
      val sourceContent = sourceFile.getLines().toList
      Try(check(Parsing.extractConfig(sourceContent)))
    }

    override def check(config: Map[String, Any]): Map[String,Any] = {
      // XLSX resource config check (mandatory properties are: source, resourceType and sheet)
      val source = config.get("source") match {
        case Some(source) => source.toString
        case None => throw new NoSuchElementException("Missing source parameter in resource config file.")
      }
      val resourceType = config.get("resourceType") match {
        case Some(resourceType) => resourceType.toString
        case None => throw new NoSuchElementException("Missing resource type parameter in resource config file.")
      }
      val sheet = config.get("sheet") match {
        case Some(sheet) => sheet.toString
        case None => throw new NoSuchElementException("Missing sheet parameter in resource config file.")
      }
      Map[String, Any]("source" -> source, "resourceType" -> resourceType, "sheet" -> sheet)
    }

  }

}