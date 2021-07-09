package org.ecdc.epitweetr.geo

import org.ecdc.twitter.{JavaBridge, Language}
import Geonames.Geolocate
import demy.util.{log => l, util}
import org.ecdc.epitweetr.{Settings, EpitweetrActor}
import org.ecdc.epitweetr.fs.{TextToGeo}
import akka.pattern.{ask, pipe}
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import akka.actor.{ActorSystem, Actor, ActorLogging, Props}
import akka.pattern.ask
import akka.actor.ActorRef
import akka.Done
import akka.util.ByteString
import akka.util.{Timeout}
import scala.collection.JavaConverters._
import org.apache.spark.sql.{SparkSession, Column, Dataset, Row, DataFrame}
import org.apache.spark.sql.functions.{col, lit, array_join, udf}
import demy.storage.{Storage, FSNode}
import scala.concurrent.ExecutionContext
import scala.util.{Try, Success, Failure}
import java.nio.charset.StandardCharsets
import spray.json.JsonParser

class GeonamesActor(conf:Settings) extends Actor with ActorLogging {
  implicit val executionContext = context.system.dispatchers.lookup("lucene-dispatcher")
  implicit val cnf = conf
  def receive = {
    case GeonamesActor.TrainingSetRequest(excludedLangs, locationSamples, jsonnl, caller) => 
      implicit val timeout: Timeout = conf.fsQueryTimeout.seconds //For ask property
      implicit val s = GeonamesActor.getSparkSession
      implicit val st = GeonamesActor.getSparkStorage
      import s.implicits._
      var sep = ""
      Future {
        if(!jsonnl) { 
          Await.result(caller ?  ByteString("[", ByteString.UTF_8), Duration.Inf)
        }
        val nonGeos =
          conf.languages
            .get
            .filterNot(l => excludedLangs.contains(l.code))
            .map{l => 
              l.getNonGeoVectors(conf.geonames).limit(500).select(col("word"), lit(false), lit(l.code)).as[(String, Boolean, String)]
            }
        val trainingData = if(locationSamples) {
          nonGeos ++ Seq(
            conf.geonames
                .getLocationSample(splitAliases=true, otherCitiesCount = 200)
                .map{loc => (loc, true, "all")}
          ) 
        } else {
          nonGeos
        }

        if(trainingData.size > 0) {
          trainingData
            .reduce(_.union(_))
            .toDF("word", "isLocation", "lang")
            .toJSON
            .toLocalIterator
            .asScala
            .foreach{line => 
               Await.result(caller ? ByteString(
                 s"${sep}${line.toString}\n", 
                 ByteString.UTF_8
               ), Duration.Inf)
               if(!jsonnl) sep = ","
            }
          }
        if(!jsonnl) { 
          Await.result(caller ?  ByteString("]", ByteString.UTF_8), Duration.Inf)
        }
        caller ! Done
      }.recover {
        case e: Exception => 
          caller ! ByteString(s"[Stream--error]: ${e.getCause} ${e.getMessage}: ${e.getStackTrace.mkString("\n")}", ByteString.UTF_8)
      }.onComplete { case  _ =>
      }
    case GeonamesActor.GeolocateTextsRequest(toGeo, minScore, jsonnl, caller) =>
      implicit val timeout: Timeout = conf.fsQueryTimeout.seconds //For ask property
      implicit val s = GeonamesActor.getSparkSession
      implicit val st = GeonamesActor.getSparkStorage
      import s.implicits._
      var sep = ""
      Future {
        if(!jsonnl) { 
          Await.result(caller ?  ByteString("[", ByteString.UTF_8), Duration.Inf)
        }
        implicit val s = GeonamesActor.getSparkSession
        implicit val st = GeonamesActor.getSparkStorage
        import s.implicits._
        val readyLangs = GeoTraining.trainedLanguages()
        println(s"langs ready: ${readyLangs.size}")
        val df0 = 
          toGeo.toDS
            .withColumn("lang", udf((lang:String) =>  if(lang == null || lang == "all") null else lang).apply(col("lang")))
        val df =  df0.geolocate(
              textLangCols = Map("text" -> (if(readyLangs.size > 0) Some("lang") else None)) 
              , maxLevDistance = 0
              , minScore = minScore.getOrElse(0)
              , nBefore = conf.geoNBefore
              , nAfter = conf.geoNAfter
              , tokenizerRegex = conf.splitter
              , langs = readyLangs
              , geonames = conf.geonames
              , reuseGeoIndex = true
              , langIndexPath=conf.langIndexPath
              , reuseLangIndex = true
             ).select(col("id"), col("text"), col("lang"), col("geo_code"), col("geo_country_code"), col("geo_country"), col("geo_name"), array_join(col("_tags_"), " ").as("tags"))
             .withColumn("geo_country", udf((country:String)=>if(country == null) null else country.split(",").head.trim).apply(col("geo_country")))
        df.toJSON
          .collect
          .foreach{line =>
             Await.result(caller ? ByteString(
               s"${sep}${line.toString}\n", 
               ByteString.UTF_8
             ), Duration.Inf)
             if(!jsonnl) sep = ","
          }
        if(!jsonnl) { 
          Await.result(caller ?  ByteString("]", ByteString.UTF_8), Duration.Inf)
        }
        caller ! Done
      }.recover {
        case e: Exception => 
          caller ! ByteString(s"[Stream--error]: ${e.getCause} ${e.getMessage}: ${e.getStackTrace.mkString("\n")}", ByteString.UTF_8)
      }.onComplete { case  _ =>
      }
    case GeonamesActor.TrainLanguagesRequest(trainingSet) => 
      Future {
        implicit val s = GeonamesActor.getSparkSession
        implicit val st = GeonamesActor.getSparkStorage
        val ret = Language.updateLanguages(trainingSet, conf.languages.get, conf.geonames, indexPath=conf.langIndexPath, conf.sparkCores)
        l.msg(s"Training finished, metrics = ${ret.map(_._4).reduce(_.sum(_))}")
      }
      .map{_ =>
        GeonamesActor.LanguagesTrained("ok")
      }
      .pipeTo(sender())
    case b => 
      Future(EpitweetrActor.Failure(s"Cannot understund $b of type ${b.getClass.getName} as message")).pipeTo(sender())
  }
}

 
object GeonamesActor {
  var spark:Option[SparkSession] = None
  case class TrainingSetRequest(excludedLangs:Seq[String], locationSamples:Boolean, jsonnl:Boolean, caller:ActorRef)
  case class TrainLanguagesRequest(trainingSet:Seq[GeoTraining])
  case class GeolocateTextsRequest(toGeo:Seq[TextToGeo], minScore:Option[Int], jsonnl:Boolean, caller:ActorRef)
  case class LanguagesTrained(msg:String)
  def closeSparkSession() = {
    spark match{
      case Some(spark) =>
        spark.stop()
      case _ =>
    }
  }

  def getSparkSession()(implicit conf:Settings) = {
    GeonamesActor.synchronized {
      conf.load()
      if(GeonamesActor.spark.isEmpty) {
        GeonamesActor.spark =  Some(JavaBridge.getSparkSession(conf.sparkCores.getOrElse(0))) 
      }
    }
    GeonamesActor.spark.get
  }
  def getSparkStorage = Storage.getSparkStorage
}


