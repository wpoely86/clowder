import com.mongodb.casbah.Imports._
import play.api.{GlobalSettings, Application}
import play.api.Logger
import play.api.Play.current

import services._
import play.libs.Akka
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._
import models.ExtractionInfoSetUp
import services.ExtractorService
import java.util.Date
import java.util.Calendar
import models._

import services.mongodb.MongoSalatPlugin

import play.api.mvc.WithFilters
import play.filters.gzip.GzipFilter


/**
 * Configure application. Ensure mongo indexes if mongo plugin is enabled.
 *
 * @author Luigi Marini
 */

object Global extends WithFilters(new GzipFilter(),CORSFilter()) with GlobalSettings {
        
      var serverStartTime:Date=null

   override def onStart(app: Application) {
    // create mongo indexes if plugin is loaded
    ServerStartTime.startTime = Calendar.getInstance().getTime()
    serverStartTime = ServerStartTime.startTime
    Logger.debug("\n----Server Start Time----" + serverStartTime + "\n \n")
    
    current.plugin[MongoSalatPlugin].map { mongo =>
      mongo.sources.values.map { source =>
        Logger.debug("Ensuring indexes on " + source.uri)
        source.collection("datasets").ensureIndex(MongoDBObject("created" -> -1))
        source.collection("datasets").ensureIndex(MongoDBObject("tags" -> 1))
        source.collection("uploads.files").ensureIndex(MongoDBObject("uploadDate" -> -1))
        source.collection("uploadquery.files").ensureIndex(MongoDBObject("uploadDate" -> -1))
        source.collection("previews.files").ensureIndex(MongoDBObject("uploadDate" -> -1, "file_id" -> 1))
        source.collection("previews.files").ensureIndex(MongoDBObject("uploadDate" -> -1, "section_id" -> 1))
        source.collection("sections").ensureIndex(MongoDBObject("uploadDate" -> -1, "file_id" -> 1))
        source.collection("extractor.servers").ensureIndex(MongoDBObject("server" -> ""))
        source.collection("extractor.names").ensureIndex(MongoDBObject("name" -> ""))
        source.collection("extractor.inputtypes").ensureIndex(MongoDBObject("inputType" -> ""))
        source.collection("dtsrequests").ensureIndex(MongoDBObject("startTime" -> -1, "endTime" -> -1))
      }

    }
 
    
    Akka.system().scheduler.schedule(0.minutes,2 minutes){
           ExtractionInfoSetUp.updateExtractorsInfo()
    }
     

    Logger.info("Application has started")

  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown")
  }

  private lazy val injector = services.DI.injector

  /** Used for dynamic controller dispatcher **/
  override def getControllerInstance[A](clazz: Class[A]) = {
    injector.getInstance(clazz)
  }
}
