package services.mongodb
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.util.JSON
import play.api.Logger
import models._
import com.novus.salat.dao.{ModelCompanion, SalatDAO}
import MongoContext.context
import play.api.Play.current
import com.mongodb.casbah.Imports._
import play.api.libs.json.{JsObject, JsString, Json, JsValue}
import javax.inject.{Inject, Singleton}
import com.mongodb.casbah.commons.TypeImports.ObjectId
import com.mongodb.casbah.WriteConcern
import services.MetadataService
import services.ContextLDService

/**
 * MongoDB Metadata Service Implementation
 */
@Singleton
class MongoDBMetadataService @Inject() (contextService: ContextLDService) extends MetadataService {

  /**
   * Add metadata to the metadata collection and attach to a section /file/dataset/collection
   */
  def addMetadata(metadata: Metadata): UUID = {
    // TODO: Update context
    val mid = MetadataDAO.insert(metadata, WriteConcern.Safe)
    UUID(mid.get.toString())
  }

  def getMetadataById(id: UUID): Option[Metadata] = {
    MetadataDAO.findOneById(new ObjectId(id.stringify)) match {
      case Some(metadata) => {
        //TODO link to context based on context id
        Some(metadata)
      }
      case None => None
    }
  }

  /** Get Metadata based on Id of an element (section/file/dataset/collection) */
  def getMetadataByAttachTo(resourceRef: ResourceRef): List[Metadata] = {
    val order = MongoDBObject("createdAt"-> -1)
    MetadataDAO.find(MongoDBObject("attachedTo.resourceType" -> resourceRef.resourceType.name,
      "attachedTo._id" -> new ObjectId(resourceRef.id.stringify))).sort(order).toList
  }

  /** Get metadata based on type i.e. user generated metadata or technical metadata  */
  def getMetadataByCreator(resourceRef: ResourceRef, typeofAgent: String): List[Metadata] = {
    val order = MongoDBObject("createdAt"-> -1)
    val metadata = MetadataDAO.find(MongoDBObject("attachedTo.resourceType" -> resourceRef.resourceType.name,
      "attachedTo._id" -> new ObjectId(resourceRef.id.stringify))).sort(order)

    for (md <- metadata.toList; if (md.creator.typeOfAgent == typeofAgent)) yield md
  }

  /** Remove metadata */
  def removeMetadata(id: UUID) = {
    val md = getMetadataById(id).getOrElse(null)
    MetadataDAO.remove(md, WriteConcern.Safe)
  }
  
  /** Get metadata context if available  **/
  def getMetadataContext(metadataId: UUID): Option[JsValue] = {
    val md = getMetadataById(metadataId)
    md match {
      case Some(m) => {
        val contextId = m.contextId
        contextId match {
          case Some(id) => contextService.getContextById(id)
          case None => None
        }
      }
      case None => None
    }
  }

  /** Vocabulary definitions for user fields **/
  def getDefinitions(spaceId: Option[UUID] = None): List[MetadataDefinition] = {
    MetadataDefinitionDAO.findAll().toList.sortWith( _.json.\("label").asOpt[String].getOrElse("") < _.json.\("label").asOpt[String].getOrElse("") )
  }

  def getDefinition(id: UUID): Option[MetadataDefinition] = {
    MetadataDefinitionDAO.findOne(MongoDBObject("_id" -> new ObjectId(id.stringify)))
  }

  /** Add vocabulary definitions **/
  def addDefinition(definition: MetadataDefinition): Unit = {
    val uri = (definition.json \ "uri").as[String]
    MetadataDefinitionDAO.findOne(MongoDBObject("json.uri" -> uri)) match {
      case Some(md) => {
        Logger.debug("Updating existing vocabulary definition: " + definition)
        // make sure to use the same id as the old value
        val writeResult = MetadataDefinitionDAO.update(MongoDBObject("json.uri" -> uri), definition.copy(id=md.id),
          false, false, WriteConcern.Normal)
      }
      case None => {
        Logger.debug("Adding new vocabulary definition " + definition)
        MetadataDefinitionDAO.save(definition)
      }
    }
  }

  /**
    * Update metadata
    * TODO: implement
    * @param metadataId
    * @param json
    */
  def updateMetadata(metadataId: UUID, json: JsValue) = {}

  /**
    * Search by metadata. Uses mongodb query structure.
    * @param query
    * @return
    */
  def search(query: JsValue): List[ResourceRef] = {
    val doc = JSON.parse(Json.stringify(query)).asInstanceOf[DBObject]
    val resources: List[ResourceRef] = MetadataDAO.find(doc).map(_.attachedTo).toList
    resources
  }

  def search(key: String, value: String, count: Int): List[ResourceRef] = {
    val field = "content." + key.trim
    val trimOr = value.trim().replaceAll(" ", "|")
    // for some reason "/"+value+"/i" doesn't work because it gets translate to
    // { "content.Abstract" : { "$regex" : "/test/i"}}
    val regexp = (s"""(?i)$trimOr""").r
    val doc = MongoDBObject(field -> regexp)
    val resources: List[ResourceRef] = MetadataDAO.find(doc).limit(count).map(_.attachedTo).toList
    resources
  }

}

object MetadataDAO extends ModelCompanion[Metadata, ObjectId] {
  val dao = current.plugin[MongoSalatPlugin] match {
    case None => throw new RuntimeException("No MongoSalatPlugin")
    case Some(x) => new SalatDAO[Metadata, ObjectId](collection = x.collection("metadata")) {}
  }
}

object MetadataDefinitionDAO extends ModelCompanion[MetadataDefinition, ObjectId] {
  val dao = current.plugin[MongoSalatPlugin] match {
    case None => throw new RuntimeException("No MongoSalatPlugin")
    case Some(x) => new SalatDAO[MetadataDefinition, ObjectId](collection = x.collection("metadata.definitions")) {}
  }
}