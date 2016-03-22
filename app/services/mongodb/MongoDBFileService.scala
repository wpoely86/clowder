package services.mongodb

import play.api.mvc.Request
import services._
import models._
import com.mongodb.casbah.commons.MongoDBObject
import java.text.SimpleDateFormat
import _root_.util.{Parsers, License}
import com.novus.salat._

import scala.collection.mutable.ListBuffer
import Transformation.LidoToCidocConvertion
import java.util.{Calendar, ArrayList}
import java.io._
import org.apache.commons.io.FileUtils
import org.json.JSONObject
import play.api.libs.json.{Json, JsValue}
import com.mongodb.util.JSON
import java.nio.file.{FileSystems, Files}
import java.nio.file.attribute.BasicFileAttributes
import collection.JavaConverters._
import scala.collection.JavaConversions._
import javax.inject.{Inject, Singleton}
import com.mongodb.casbah.WriteConcern
import play.api.Logger
import scala.util.parsing.json.JSONArray
import play.api.libs.json.JsArray
import models.File
import play.api.libs.json.JsObject
import java.util.Date
import com.novus.salat.dao.{ModelCompanion, SalatDAO}
import MongoContext.context
import play.api.Play.current
import com.mongodb.casbah.Imports._


/**
 * Use mongo for both metadata and blobs.
 *
 *
 */
@Singleton
class MongoDBFileService @Inject() (
  datasets: DatasetService,
  collections: CollectionService,
  sections: SectionService,
  comments: CommentService,
  previews: PreviewService,
  thumbnails: ThumbnailService,
  threeD: ThreeDService,
  sparql: RdfSPARQLService,
  storage: ByteStorageService,
  userService: UserService,
  folders: FolderService,
  metadatas:MetadataService) extends FileService {

  object MustBreak extends Exception {}

  /**
   * Count all files
   */
  def count(): Long = {
    FileDAO.count(MongoDBObject())
  }

  /**
   * List all files.
   */
  def listFiles(): List[File] = {
    (for (file <- FileDAO.find(MongoDBObject())) yield file).toList
  }
  
  /**
   * List all files in the system that are not intermediate result files generated by the extractors.
   */
  def listFilesNotIntermediate(): List[File] = {
    (for (file <- FileDAO.find("isIntermediate" $ne true)) yield file).toList
  }

  /**
   * List files after a specified date.
   */
  def listFilesAfter(date: String, limit: Int): List[File] = {
    val order = MongoDBObject("uploadDate" -> -1)
    if (date == "") {
      FileDAO.find("isIntermediate" $ne true).sort(order).limit(limit).toList
    } else {
      val sinceDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse(date)
      Logger.info("After " + sinceDate)
      FileDAO.find($and("isIntermediate" $ne true, "uploadDate" $lt sinceDate)).sort(order).limit(limit).toList
    }
  }

  /**
   * List files before a specified date.
   */
  def listFilesBefore(date: String, limit: Int): List[File] = {
    var order = MongoDBObject("uploadDate" -> -1)
    if (date == "") {
      FileDAO.find("isIntermediate" $ne true).sort(order).limit(limit).toList
    } else {
      order = MongoDBObject("uploadDate" -> 1)
      val sinceDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse(date)
      Logger.info("Before " + sinceDate)
      FileDAO.find($and("isIntermediate" $ne true, "uploadDate" $gt sinceDate)).sort(order).limit(limit).toList.reverse
    }
  }
  
  /**
   * List files specific to a user after a specified date.
   */
  def listUserFilesAfter(date: String, limit: Int, email: String): List[File] = {
    val order = MongoDBObject("uploadDate"-> -1 )
    if (date == "") {
      FileDAO.find(("isIntermediate" $ne true) ++ ("author.email" $eq email)).sort(order).limit(limit).toList
    } else {
      val sinceDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(date)
      FileDAO.find(("isIntermediate" $ne true) ++ ("uploadDate" $lt sinceDate) ++ ("author.email" -> email))
        .sort(order).limit(limit).toList
    }
  }

  /**
   * List files specific to a user before a specified date.
   */
  def listUserFilesBefore(date: String, limit: Int, email: String): List[File] = {
    var order = MongoDBObject("uploadDate"-> -1)
    if (date == "") {
      FileDAO.find(("isIntermediate" $ne true) ++ ("author.email" $eq email)).sort(order).limit(limit).toList
    } else {
      order = MongoDBObject("uploadDate"-> 1)
      val sinceDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(date)
      FileDAO.find(("isIntermediate" $ne true) ++ ("uploadDate" $gt sinceDate) ++ ("author.email" $eq email))
        .sort(order).limit(limit).toList.reverse
    }
  }

  def latest(): Option[File] = {
    val results = FileDAO.find("isIntermediate" $ne true).sort(MongoDBObject("uploadDate" -> -1)).limit(1).toList
    if (results.size > 0)
      Some(results(0))
    else
      None
  }

  def latest(i: Int): List[File] = {
    FileDAO.find(MongoDBObject()).sort(MongoDBObject("uploadDate" -> -1)).limit(i).toList
  }

  def first(): Option[File] = {
    val results = FileDAO.find("isIntermediate" $ne true).sort(MongoDBObject("uploadDate" -> 1)).limit(1).toList
    if (results.size > 0)
      Some(results(0))
    else
      None
  }

  /**
   * Save blob.
   */
  def save(inputStream: InputStream, filename: String, contentType: Option[String], author: User, showPreviews: String = "DatasetLevel"): Option[File] = {
    val extra = Map("showPreviews" -> showPreviews,
                    "author" -> SocialUserDAO.toDBObject(author),
                    "licenseData" -> grater[LicenseData].asDBObject(License.fromAppConfig()))
    MongoUtils.writeBlob[File](inputStream, filename, contentType, extra, "uploads", "medici2.mongodb.storeFiles").flatMap(x => get(x._1))
  }

  /**
   * Get blob.
   */
  def getBytes(id: UUID): Option[(InputStream, String, String, Long)] = {
    MongoUtils.readBlob(id, "uploads", "medici2.mongodb.storeFiles")
  }

  def index(id: Option[UUID]) = {
    id match {
      case Some(fileId) => index(fileId)
      case None => FileDAO.find(MongoDBObject()).foreach(f => index(f.id))
    }
  }

  def index(id: UUID) {
    get(id) match {
      case Some(file) => {
        var tagListBuffer = new ListBuffer[String]()

        for (tag <- file.tags) {
          tagListBuffer += tag.name
        }

        val tagsJson = new JSONArray(tagListBuffer.toList)

        Logger.debug("tagStr=" + tagsJson);

        val commentsByFile = for (comment <- comments.findCommentsByFileId(id)) yield {
          comment.text
        }
        val commentJson = new JSONArray(commentsByFile)

        Logger.debug("commentStr=" + commentJson.toString())

        val usrMd = getUserMetadataJSON(id)
        Logger.debug("usrmd=" + usrMd)

        val techMd = getTechnicalMetadataJSON(id)
        Logger.debug("techmd=" + techMd)

        val xmlMd = getXMLMetadataJSON(id)
        Logger.debug("xmlmd=" + xmlMd)

        var fileDsId = ""
        var fileDsName = ""

        for (dataset <- datasets.findByFileId(file.id)) {
          fileDsId = fileDsId + dataset.id.stringify + " %%% "
          fileDsName = fileDsName + dataset.name + " %%% "
        }
        
        val formatter = new SimpleDateFormat("dd/MM/yyyy")

        current.plugin[ElasticsearchPlugin].foreach {
          _.index("data", "file", id,
            List(("filename", file.filename), ("contentType", file.contentType),("author",file.author.fullName),("uploadDate",formatter.format(file.uploadDate)),("datasetId",fileDsId),("datasetName",fileDsName), ("tag", tagsJson.toString), ("comments", commentJson.toString), ("usermetadata", usrMd), ("technicalmetadata", techMd), ("xmlmetadata", xmlMd)))
        }
        
      }
      case None => Logger.error("File not found: " + id)
    }
  }

  /**
    * Directly insert a file into the db (even with a local path)
    */
  def insert(file: File): Option[String] = {
    FileDAO.insert(file).map(_.toString)
  }

  /**
   * Return a list of tags and counts found in sections
   */
  def getTags(): Map[String, Long] = {
    val x = FileDAO.dao.collection.aggregate(MongoDBObject("$unwind" -> "$tags"),
      MongoDBObject("$group" -> MongoDBObject("_id" -> "$tags.name", "count" -> MongoDBObject("$sum" -> 1L))))
    x.results.map(x => (x.getAsOrElse[String]("_id", "??"), x.getAsOrElse[Long]("count", 0L))).toMap
  }

  def modifyRDFOfMetadataChangedFiles() {
    val changedFiles = findMetadataChangedFiles()
    for (changedFile <- changedFiles) {
      modifyRDFUserMetadata(changedFile.id)
    }
  }


  def modifyRDFUserMetadata(id: UUID, mappingNumber: String = "1") = { implicit request: Request[Any] =>
    sparql.removeFileFromGraphs(id, "rdfCommunityGraphName")
    get(id) match {
      case Some(file) => {
        val theJSON = getUserMetadataJSON(id)
        val fileSep = System.getProperty("file.separator")
        val tmpDir = System.getProperty("java.io.tmpdir")
        var resultDir = tmpDir + fileSep + "medici__rdfuploadtemporaryfiles" + fileSep + UUID.generate.stringify
        val resultDirFile = new java.io.File(resultDir)
        resultDirFile.mkdirs()

        if (!theJSON.replaceAll(" ", "").equals("{}")) {
          val xmlFile = jsonToXML(theJSON)
          new LidoToCidocConvertion(play.api.Play.configuration.getString("filesxmltordfmapping.dir_" + mappingNumber).getOrElse(""), xmlFile.getAbsolutePath(), resultDir)
          xmlFile.delete()
        }
        else {
          new java.io.File(resultDir + fileSep + "Results.rdf").createNewFile()
        }
        val resultFile = new java.io.File(resultDir + fileSep + "Results.rdf")

        //Connecting RDF metadata with the entity describing the original file
        val rootNodes = new ArrayList[String]()
        val rootNodesFile = play.api.Play.configuration.getString("rootNodesFile").getOrElse("")
        Logger.debug(rootNodesFile)
        if (!rootNodesFile.equals("*")) {
          val rootNodesReader = new BufferedReader(new FileReader(new java.io.File(rootNodesFile)))
          var line = rootNodesReader.readLine()
          while (line != null) {
            Logger.debug((line == null).toString())
            rootNodes.add(line.trim())
            line = rootNodesReader.readLine()
          }
          rootNodesReader.close()
        }

        val resultFileConnected = java.io.File.createTempFile("ResultsConnected", ".rdf")

        val fileWriter = new BufferedWriter(new FileWriter(resultFileConnected))
        val fis = new FileInputStream(resultFile)
        val data = new Array[Byte](resultFile.length().asInstanceOf[Int])
        fis.read(data)
        fis.close()
        resultFile.delete()
        FileUtils.deleteDirectory(resultDirFile)
        //
        val s = new String(data, "UTF-8")
        val rdfDescriptions = s.split("<rdf:Description")
        fileWriter.write(rdfDescriptions(0))
        var i = 0
        for (i <- 1 to (rdfDescriptions.length - 1)) {
          fileWriter.write("<rdf:Description" + rdfDescriptions(i))
          if (rdfDescriptions(i).contains("<rdf:type")) {
            var isInRootNodes = false
            if (rootNodesFile.equals("*"))
              isInRootNodes = true
            else {
              var j = 0
              try {
                for (j <- 0 to (rootNodes.size() - 1)) {
                  if (rdfDescriptions(i).contains("\"" + rootNodes.get(j) + "\"")) {
                    isInRootNodes = true
                    throw MustBreak
                  }
                }
              } catch {
                case MustBreak =>
              }
            }

            if (isInRootNodes) {
              val theResource = rdfDescriptions(i).substring(rdfDescriptions(i).indexOf("\"") + 1, rdfDescriptions(i).indexOf("\"", rdfDescriptions(i).indexOf("\"") + 1))
              // TODO RK : need to make sure we know if it is https
              var connection = "<rdf:Description rdf:about=\"" + api.routes.Files.get(id).absoluteURL(false)
              connection = connection + "\"><P129_is_about xmlns=\"http://www.cidoc-crm.org/rdfs/cidoc_crm_v5.0.2.rdfs#\" rdf:resource=\"" + theResource
              connection = connection + "\"/></rdf:Description>"
              fileWriter.write(connection)
            }
          }
        }
        fileWriter.close()

        sparql.addFromFile(id, resultFileConnected, "file")
        resultFileConnected.delete()

        sparql.addFileToGraph(id, "rdfCommunityGraphName")

        setUserMetadataWasModified(id, false)
      }
      case None => {}
    }
  }

  def jsonToXML(theJSON: String): java.io.File = {

    val jsonObject = new JSONObject(theJSON)
    var xml = org.json.XML.toString(jsonObject)

    //Remove spaces from XML tags
    var currStart = xml.indexOf("<")
    var currEnd = -1
    var xmlNoSpaces = ""
    while (currStart != -1) {
      xmlNoSpaces = xmlNoSpaces + xml.substring(currEnd + 1, currStart)
      currEnd = xml.indexOf(">", currStart + 1)
      xmlNoSpaces = xmlNoSpaces + xml.substring(currStart, currEnd + 1).replaceAll(" ", "_")
      currStart = xml.indexOf("<", currEnd + 1)
    }
    xmlNoSpaces = xmlNoSpaces + xml.substring(currEnd + 1)

    val xmlFile = java.io.File.createTempFile("xml", ".xml")
    val fileWriter = new BufferedWriter(new FileWriter(xmlFile))
    fileWriter.write(xmlNoSpaces)
    fileWriter.close()

    return xmlFile
  }

  def getXMLMetadataJSON(id: UUID): String = {
    FileDAO.dao.collection.findOneByID(new ObjectId(id.stringify)) match {
      case None => "{}"
      case Some(x) => {
        x.getAs[DBObject]("xmlMetadata") match {
          case Some(y) => {
            val returnedMetadata = com.mongodb.util.JSON.serialize(x.getAs[DBObject]("xmlMetadata").get)
            returnedMetadata
          }
          case None => "{}"
        }
      }
    }
  }
  
 
  

  def removeTags(id: UUID, userIdStr: Option[String], eid: Option[String], tags: List[String]) {
    Logger.debug("Removing tags in file " + id + " : " + tags + ", userId: " + userIdStr + ", eid: " + eid)
    val file = get(id).get
    val existingTags = file.tags.filter(x => userIdStr == x.userId && eid == x.extractor_id).map(_.name)
    Logger.debug("existingTags after user and extractor filtering: " + existingTags.toString)
    // Only remove existing tags.
    tags.intersect(existingTags).map {
      tag =>
        FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $pull("tags" -> MongoDBObject("name" -> tag)), false, false, WriteConcern.Safe)
    }
  }

  def addMetadata(fileId: UUID, metadata: JsValue) {
    val doc = JSON.parse(Json.stringify(metadata)).asInstanceOf[DBObject]
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(fileId.stringify)), $addToSet("metadata" -> doc), false, false, WriteConcern.Safe)
  }

  def updateMetadata(fileId: UUID, metadata: JsValue, extractor_id: String) {
    val doc = JSON.parse(Json.stringify(metadata)).asInstanceOf[DBObject]
    FileDAO.findOneById(new ObjectId(fileId.stringify)) match {
      case None => None
      case Some(file) => {
        FileDAO.update(MongoDBObject("_id" -> new ObjectId(fileId.stringify), "metadata.extractor_id" -> extractor_id), $set("metadata.$" -> doc), false, false, WriteConcern.Safe)
      }
    }
  }

  def get(id: UUID): Option[File] = {
    FileDAO.findOneById(new ObjectId(id.stringify)) match {
      case Some(file) => {
        val previewsByFile = previews.findByFileId(file.id)
        val sectionsByFile = sections.findByFileId(file.id)
        val sectionsWithPreviews = sectionsByFile.map { s =>
          val p = PreviewDAO.findOne(MongoDBObject("section_id"->s.id))
          s.copy(preview = p)
        }
        Some(file.copy(sections = sectionsWithPreviews, previews = previewsByFile))
      }
      case None => None
    }
  }

  def listOutsideDataset(dataset_id: UUID): List[File] = {
    datasets.get(dataset_id) match{
      case Some(dataset) => {
        val list = for (file <- FileDAO.findAll(); if(!isInDataset(file,dataset) && !file.isIntermediate.getOrElse(false))) yield file
        return list.toList
      }
      case None =>{
        return FileDAO.findAll.toList
      }
    }
  }

  def isInDataset(file: File, dataset: Dataset): Boolean = {
    for(dsFile <- dataset.files){
      if(dsFile == file.id)
        return true
    }
    return false
  }

  //Not used yet
  def getMetadata(id: UUID): scala.collection.immutable.Map[String,Any] = {
    FileDAO.dao.collection.findOneByID(new ObjectId(id.stringify)) match {
      case None => new scala.collection.immutable.HashMap[String,Any]
      case Some(x) => {
        val returnedMetadata = x.getAs[DBObject]("metadata").get.toMap.asScala.asInstanceOf[scala.collection.mutable.Map[String,Any]].toMap
        returnedMetadata
      }
    }
  }

  def getUserMetadata(id: UUID): scala.collection.mutable.Map[String,Any] = {
    FileDAO.dao.collection.findOneByID(new ObjectId(id.stringify)) match {
      case None => new scala.collection.mutable.HashMap[String,Any]
      case Some(x) => {
        x.getAs[DBObject]("userMetadata") match{
          case Some(y)=>{
            val returnedMetadata = x.getAs[DBObject]("userMetadata").get.toMap.asScala.asInstanceOf[scala.collection.mutable.Map[String,Any]]
            returnedMetadata
          }
          case None => new scala.collection.mutable.HashMap[String,Any]
        }
      }
    }
  }

  def getUserMetadataJSON(id: UUID): String = {
    FileDAO.dao.collection.findOneByID(new ObjectId(id.stringify)) match {
      case None => "{}"
      case Some(x) => {
        x.getAs[DBObject]("userMetadata") match{
          case Some(y)=>{
            val returnedMetadata = com.mongodb.util.JSON.serialize(x.getAs[DBObject]("userMetadata").get)
            returnedMetadata
          }
          case None => "{}"
        }
      }
    }
  }

  def getTechnicalMetadataJSON(id: UUID): String = {
    FileDAO.dao.collection.findOneByID(new ObjectId(id.stringify)) match {
      case None => "{}"
      case Some(x) => {
        x.getAs[DBObject]("metadata") match{
          case Some(y)=>{
            val returnedMetadata = com.mongodb.util.JSON.serialize(x.getAs[DBObject]("metadata").get)
            returnedMetadata
          }
          case None => "{}"
        }
      }
    }
  }

  
  
  /**
   *  Add versus descriptors to the Versus.descriptors collection associated to a file
   *
   */
 def addVersusMetadata(id:UUID,json:JsValue){
    val doc = JSON.parse(Json.stringify(json)).asInstanceOf[DBObject].toMap
              .asScala.asInstanceOf[scala.collection.mutable.Map[String,Any]].toMap
       VersusDAO.insert(new Versus(id,doc),WriteConcern.Safe)
       Logger.info("--Added versus descriptors in json format received from versus to the metadata field --")
  }
 
/**
 * Get Versus descriptors as Json Array for a file
 */
  def getVersusMetadata(id: UUID): Option[JsValue] = {
    val versusDescriptors = VersusDAO.find(MongoDBObject("fileId" -> new ObjectId(id.stringify)))
    var vdArray = new JsArray()
    for (vd <- versusDescriptors) {
      var x = com.mongodb.util.JSON.serialize(vd.asInstanceOf[Versus].descriptors("versus_descriptors"))
      vdArray = vdArray :+ Json.parse(x)
      Logger.debug("array=" + vdArray.toString)
    }
    Some(vdArray)
  } 
   /*convert list of JsObject to JsArray*/
  def getJsonArray(list: List[JsObject]): JsArray = {
    list.foldLeft(JsArray())((acc, x) => acc ++ Json.arr(x))
  }

  def addUserMetadata(id: UUID, json: String) {
    Logger.debug("Adding/modifying user metadata to file " + id + " : " + json)
    val md = com.mongodb.util.JSON.parse(json).asInstanceOf[DBObject]
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("userMetadata" -> md), false, false, WriteConcern.Safe)
  }

  def addXMLMetadata(id: UUID, json: String) {
    Logger.debug("Adding/modifying XML file metadata to file " + id + " : " + json)
    val md = com.mongodb.util.JSON.parse(json).asInstanceOf[DBObject]
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("xmlMetadata" -> md), false, false, WriteConcern.Safe)
  }

  def findByTag(tag: String): List[File] = {
    FileDAO.find(MongoDBObject("tags.name" -> tag)).toList
  }

  def findByTag(tag: String, start: String, limit: Integer, reverse: Boolean): List[File] = {
    val filter = if (start == "") {
      MongoDBObject("tags.name" -> tag)
    } else {
      if (reverse) {
        MongoDBObject("tags.name" -> tag) ++ ("uploadDate" $gte Parsers.fromISO8601(start))
      } else {
        MongoDBObject("tags.name" -> tag) ++ ("uploadDate" $lte Parsers.fromISO8601(start))
      }
    }
    val order = if (reverse) {
      MongoDBObject("uploadDate" -> 1, "filename" -> 1)
    } else {
      MongoDBObject("uploadDate" -> -1, "filename" -> 1)
    }
    FileDAO.dao.find(filter).sort(order).limit(limit).toList
  }

  def findIntermediates(): List[File] = {
    FileDAO.find(MongoDBObject("isIntermediate" -> true)).toList
  }
  
  /**
   * Implementation of updateLicenseing defined in services/FileService.scala.
   */
  def updateLicense(id: UUID, licenseType: String, rightsHolder: String, licenseText: String, licenseUrl: String,
    allowDownload: String) {
      val licenseData = models.LicenseData(m_licenseType = licenseType, m_rightsHolder = rightsHolder,
        m_licenseText = licenseText, m_licenseUrl = licenseUrl, m_allowDownload = allowDownload.toBoolean)
      val result = FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), 
          $set("licenseData" -> LicenseData.toDBObject(licenseData)), 
          false, false, WriteConcern.Safe);      
  }

  // ---------- Tags related code starts ------------------
  // Input validation is done in api.Files, so no need to check again.
  def addTags(id: UUID, userIdStr: Option[String], eid: Option[String], tags: List[String]) {
    Logger.debug("Adding tags to file " + id + " : " + tags)
    val file = get(id).get
    val existingTags = file.tags.filter(x => userIdStr == x.userId && eid == x.extractor_id).map(_.name)
    val createdDate = new Date
    val maxTagLength = play.api.Play.configuration.getInt("clowder.tagLength").getOrElse(100)
    tags.foreach(tag => {
      val shortTag = if (tag.length > maxTagLength) {
        Logger.error("Tag is truncated to " + maxTagLength + " chars : " + tag)
        tag.substring(0, maxTagLength)
      } else {
        tag
      }
      // Only add tags with new values.
      if (!existingTags.contains(shortTag)) {
        val tagObj = models.Tag(name = shortTag, userId = userIdStr, extractor_id = eid, created = createdDate)
        FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $addToSet("tags" -> Tag.toDBObject(tagObj)), false, false, WriteConcern.Safe)
      }
    })
  }

  def removeAllTags(id: UUID) {
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("tags" -> List()), false, false,
      WriteConcern.Safe)
  }
  // ---------- Tags related code ends ------------------

  def comment(id: UUID, comment: Comment) {
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $addToSet("comments" -> Comment.toDBObject(comment)),
      false, false, WriteConcern.Safe)
  }
  
  def setIntermediate(id: UUID){
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("isIntermediate" -> Some(true)), false, false,
      WriteConcern.Safe)
  }

  def renameFile(id: UUID, newName: String){
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("filename" -> newName), false, false,
      WriteConcern.Safe)
  }

  def setContentType(id: UUID, newType: String){
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("contentType" -> newType), false, false,
      WriteConcern.Safe)
  }

  def setUserMetadataWasModified(id: UUID, wasModified: Boolean){
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)), $set("userMetadataWasModified" -> Some(wasModified)),
      false, false, WriteConcern.Safe)
  }

  def removeFile(id: UUID){
    get(id) match{
      case Some(file) => {
        if(file.isIntermediate.isEmpty){
          val fileDatasets = datasets.findByFileId(file.id)
          for(fileDataset <- fileDatasets){
            datasets.removeFile(fileDataset.id, id)
            if(!file.xmlMetadata.isEmpty){
              datasets.index(fileDataset.id)
            }

            if(!file.thumbnail_id.isEmpty && !fileDataset.thumbnail_id.isEmpty){            
              if(file.thumbnail_id.get.equals(fileDataset.thumbnail_id.get)){ 
                datasets.newThumbnail(fileDataset.id)
                
                	for(collectionId <- fileDataset.collections){
		                          collections.get(collectionId) match{
		                            case Some(collection) =>{		                              
		                            	if(!collection.thumbnail_id.isEmpty){	                            	  
		                            		if(collection.thumbnail_id.get.equals(fileDataset.thumbnail_id.get)){
		                            			collections.createThumbnail(collection.id)
		                            		}		                        
		                            	}
		                            }
		                            case None=>Logger.debug(s"Could not find collection $collectionId") 
		                          }
		                        }		        	  
		        }
            }
                     
          }
          val fileFolders = folders.findByFileId(file.id)
          for(fileFolder <- fileFolders) {
            folders.removeFile(fileFolder.id, file.id)
          }
          for(section <- sections.findByFileId(file.id)){
            sections.removeSection(section)
          }
          for(preview <- previews.findByFileId(file.id)){
            previews.removePreview(preview)
          }
          for(comment <- comments.findCommentsByFileId(id)){
            comments.removeComment(comment)
          }
          for(texture <- threeD.findTexturesByFileId(file.id)){
            ThreeDTextureDAO.removeById(new ObjectId(texture.id.stringify))
          }
          for (follower <- file.followers) {
            userService.unfollowFile(follower, id)
          }
          if(!file.thumbnail_id.isEmpty)
            thumbnails.remove(UUID(file.thumbnail_id.get))
          metadatas.removeMetadataByAttachTo(ResourceRef(ResourceRef.file, id))
        }

        // finally delete the actual file
        MongoUtils.removeBlob(id, "uploads", "medici2.mongodb.storeFiles")
      }
      case None => Logger.debug("File not found")
    }
  }

  def removeTemporaries(){
    val cal = Calendar.getInstance()
    val timeDiff = play.Play.application().configuration().getInt("rdfTempCleanup.removeAfter")
    cal.add(Calendar.MINUTE, -timeDiff)
    val oldDate = cal.getTime()

    val tmpDir = System.getProperty("java.io.tmpdir")
    val filesep = System.getProperty("file.separator")
    val rdfTmpDir = new java.io.File(tmpDir + filesep + "medici__rdfdumptemporaryfiles")
    if(!rdfTmpDir.exists()){
      rdfTmpDir.mkdir()
    }

    val listOfFiles = rdfTmpDir.listFiles()
    for(currFileDir <- listOfFiles){
      val currFile = currFileDir.listFiles()(0)
      val attrs = Files.readAttributes(FileSystems.getDefault().getPath(currFile.getAbsolutePath()),  classOf[BasicFileAttributes])
      val timeCreated = new Date(attrs.creationTime().toMillis())
      if(timeCreated.compareTo(oldDate) < 0){
        currFile.delete()
        currFileDir.delete()
      }
    }
  }

  def findMetadataChangedFiles(): List[File] = {
    FileDAO.find(MongoDBObject("userMetadataWasModified" -> true)).toList
  }

  def searchAllMetadataFormulateQuery(requestedMetadataQuery: Any): List[File] = {
    Logger.debug("top: "+ requestedMetadataQuery.asInstanceOf[java.util.LinkedHashMap[String,Any]].toString()  )
    var theQuery =  searchMetadataFormulateQuery(requestedMetadataQuery.asInstanceOf[java.util.LinkedHashMap[String,Any]], "all")
    Logger.debug("thequery: "+theQuery.toString)
    FileDAO.find(theQuery).toList
  }


  def searchUserMetadataFormulateQuery(requestedMetadataQuery: Any): List[File] = {
    Logger.debug("top: "+ requestedMetadataQuery.asInstanceOf[java.util.LinkedHashMap[String,Any]].toString()  )
    var theQuery =  searchMetadataFormulateQuery(requestedMetadataQuery.asInstanceOf[java.util.LinkedHashMap[String,Any]], "userMetadata")
    Logger.debug("thequery: "+theQuery.toString)
    FileDAO.find(theQuery).toList
  }

  def searchMetadataFormulateQuery(requestedMap: java.util.LinkedHashMap[String,Any], root: String): MongoDBObject = {
    Logger.debug("req: "+ requestedMap)
    var queryMap = MongoDBList()
    var builder = MongoDBList()
    var orFound = false
    for((reqKey, reqValue) <- requestedMap){
      val keyTrimmed = reqKey.replaceAll("__[0-9]+$","")

      if(keyTrimmed.equals("OR")){
        queryMap.add(MongoDBObject("$and" ->  builder))
        builder = MongoDBList()
        orFound = true
      }
      else{
        var actualKey = keyTrimmed
        if(keyTrimmed.endsWith("__not")){
          actualKey = actualKey.substring(0, actualKey.length()-5)
        }

        if(!root.equals("all")){

          if(!root.equals(""))
            actualKey = root + "." + actualKey

          if(reqValue.isInstanceOf[String]){
            val currValue = reqValue.asInstanceOf[String]
                        
            if(keyTrimmed.endsWith("__not")){
              if(currValue.contains(" IGNORE CASE") || currValue.contains(" ANYWHERE")){
                var realValue = currValue.replace(" IGNORE CASE", "").replace(" ANYWHERE", "");                
                if(!currValue.contains(" ANYWHERE")){
                  realValue = "^"+realValue+"$";
                }
                if(currValue.contains(" IGNORE CASE")){
                  realValue = "(?i)"+realValue;
                }
                builder += MongoDBObject(actualKey -> MongoDBObject("$not" ->  realValue.r))
              }
              else{
                builder += MongoDBObject(actualKey -> MongoDBObject("$ne" ->  currValue))
              }
            }
            else{
              if(currValue.contains(" IGNORE CASE") || currValue.contains(" ANYWHERE")){
                var realValue = currValue.replace(" IGNORE CASE", "").replace(" ANYWHERE", "");                
                if(!currValue.contains(" ANYWHERE")){
                  realValue = "^"+realValue+"$";
                }
                if(currValue.contains(" IGNORE CASE")){
                  realValue = "(?i)"+realValue;
                }
                builder += MongoDBObject(actualKey -> realValue.r)
              }
              else{
                builder += MongoDBObject(actualKey -> currValue)
              }
            }
          }else{
            //recursive
            if(root.equals("userMetadata")){
              val currValue =  searchMetadataFormulateQuery(reqValue.asInstanceOf[java.util.LinkedHashMap[String,Any]], "")
              val elemMatch = actualKey $elemMatch currValue
              builder.add(elemMatch)
            }
            else{
              val currValue =  searchMetadataFormulateQuery(reqValue.asInstanceOf[java.util.LinkedHashMap[String,Any]], actualKey)
              builder += currValue
            }
          }
        } else {
          var objectForEach = MongoDBList()
          val allRoots = Map(1 -> "userMetadata", 2 -> "metadata", 3 -> "xmlMetadata")
          allRoots.keys.foreach{ i =>
            var tempActualKey = allRoots(i) + "." + actualKey

            if(reqValue.isInstanceOf[String]){
              val currValue = reqValue.asInstanceOf[String]
              if(keyTrimmed.endsWith("__not")){
                if(currValue.contains(" IGNORE CASE") || currValue.contains(" ANYWHERE")){
	                var realValue = currValue.replace(" IGNORE CASE", "").replace(" ANYWHERE", "");                
	                if(!currValue.contains(" ANYWHERE")){
	                  realValue = "^"+realValue+"$";
	                }
	                if(currValue.contains(" IGNORE CASE")){
	                  realValue = "(?i)"+realValue;
	                }
	                objectForEach += MongoDBObject(tempActualKey -> MongoDBObject("$not" ->  realValue.r))
                }
                else{
                	objectForEach += MongoDBObject(tempActualKey -> MongoDBObject("$ne" ->  currValue))
                }
              }
              else{
                if(currValue.contains(" IGNORE CASE") || currValue.contains(" ANYWHERE")){
	                var realValue = currValue.replace(" IGNORE CASE", "").replace(" ANYWHERE", "");                
	                if(!currValue.contains(" ANYWHERE")){
	                  realValue = "^"+realValue+"$";
	                }
	                if(currValue.contains(" IGNORE CASE")){
	                  realValue = "(?i)"+realValue;
	                }
	                objectForEach += MongoDBObject(tempActualKey -> realValue.r)
                }
                else{
                	objectForEach += MongoDBObject(tempActualKey -> currValue)
                }
              }
            }else{
              //recursive
              if(allRoots(i).equals("userMetadata")){
                val currValue =  searchMetadataFormulateQuery(reqValue.asInstanceOf[java.util.LinkedHashMap[String,Any]], "")
                val elemMatch = tempActualKey $elemMatch currValue
                objectForEach.add(elemMatch)
              }
              else{
                val currValue =  searchMetadataFormulateQuery(reqValue.asInstanceOf[java.util.LinkedHashMap[String,Any]], tempActualKey)
                objectForEach += currValue
              }
            }
          }

          builder.add(MongoDBObject("$or" ->  objectForEach))

        }
      }
    }

    if(orFound){
      queryMap.add(MongoDBObject("$and" ->  builder))
      return MongoDBObject("$or" ->  queryMap)
    }
    else if(!builder.isEmpty)  {
      return MongoDBObject("$and" ->  builder)
    }
    else if(!root.equals("")){
      return (root $exists true)
    }
    else{
      return new MongoDBObject()
    }
  }

  def removeOldIntermediates(){
    val cal = Calendar.getInstance()
    val timeDiff = play.Play.application().configuration().getInt("intermediateCleanup.removeAfter")
    cal.add(Calendar.HOUR, -timeDiff)
    val oldDate = cal.getTime()
    val fileList = FileDAO.find($and("isIntermediate" $eq true, "uploadDate" $lt oldDate)).toList
    for(file <- fileList)
      removeFile(file.id)
  }

  /**
   * Update thumbnail used to represent this dataset.
   */
  def updateThumbnail(fileId: UUID, thumbnailId: UUID) {
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(fileId.stringify)),
      $set("thumbnail_id" -> thumbnailId.stringify), false, false, WriteConcern.Safe)
  }

  def dumpAllFileMetadata(): List[String] = {
		    Logger.debug("Dumping metadata of all files.")

		    val fileSep = System.getProperty("file.separator")
		    val lineSep = System.getProperty("line.separator")
		    var fileMdDumpDir = play.api.Play.configuration.getString("filedump.dir").getOrElse("")
			if(!fileMdDumpDir.endsWith(fileSep))
				fileMdDumpDir = fileMdDumpDir + fileSep
			var fileMdDumpMoveDir = play.api.Play.configuration.getString("filedumpmove.dir").getOrElse("")
			if(fileMdDumpMoveDir.equals("")){
				Logger.warn("Will not move dumped files metadata to staging directory. No staging directory set.")
			}
			else{
			    if(!fileMdDumpMoveDir.endsWith(fileSep))
				  fileMdDumpMoveDir = fileMdDumpMoveDir + fileSep
			}

			var unsuccessfulDumps: ListBuffer[String] = ListBuffer.empty

			for(file <- FileDAO.findAll){
			  try{
				  val fileId = file.id.toString

				  val fileTechnicalMetadata = getTechnicalMetadataJSON(file.id)
				  val fileUserMetadata = getUserMetadataJSON(file.id)
				  if(fileTechnicalMetadata != "{}" || fileUserMetadata != "{}"){

				    val filenameNoExtension = file.filename.substring(0, file.filename.lastIndexOf("."))
				    val filePathInDirs = fileId.charAt(fileId.length()-3)+ fileSep + fileId.charAt(fileId.length()-2)+fileId.charAt(fileId.length()-1)+ fileSep + fileId + fileSep + filenameNoExtension + "__metadata.txt"
				    val mdFile = new java.io.File(fileMdDumpDir + filePathInDirs)
				    mdFile.getParentFile().mkdirs()

				    val fileWriter =  new BufferedWriter(new FileWriter(mdFile))
					fileWriter.write(fileTechnicalMetadata + lineSep + lineSep + fileUserMetadata)
					fileWriter.close()

					if(!fileMdDumpMoveDir.equals("")){
					  try{
						  val mdMoveFile = new java.io.File(fileMdDumpMoveDir + filePathInDirs)
					      mdMoveFile.getParentFile().mkdirs()

						  if(mdFile.renameTo(mdMoveFile)){
			            	Logger.info("File metadata dumped and moved to staging directory successfully.")
						  }else{
			            	Logger.warn("Could not move dumped file metadata to staging directory.")
			            	throw new Exception("Could not move dumped file metadata to staging directory.")
						  }
					  }catch {case ex:Exception =>{
						  val badFileId = file.id.toString
						  Logger.error("Unable to stage dumped metadata of file with id "+badFileId+": "+ex.printStackTrace())
						  unsuccessfulDumps += badFileId
					  }}
					}
				  }

			  }catch {case ex:Exception =>{
			    val badFileId = file.id.toString
			    Logger.error("Unable to dump metadata of file with id "+badFileId+": "+ex.printStackTrace())
			    unsuccessfulDumps += badFileId
			  }}
			}

		    return unsuccessfulDumps.toList

	}

  def addFollower(id: UUID, userId: UUID) {
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)),
                    $addToSet("followers" -> new ObjectId(userId.stringify)), false, false, WriteConcern.Safe)
  }

  def removeFollower(id: UUID, userId: UUID) {
    FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)),
                    $pull("followers" -> new ObjectId(userId.stringify)), false, false, WriteConcern.Safe)
  }

  def updateDescription(id: UUID, description: String) {
    val result = FileDAO.update(MongoDBObject("_id" -> new ObjectId(id.stringify)),
      $set("description" -> description),
      false, false, WriteConcern.Safe)

  }
}

object FileDAO extends ModelCompanion[File, ObjectId] {
  val dao = current.plugin[MongoSalatPlugin] match {
    case None => throw new RuntimeException("No MongoSalatPlugin");
    case Some(x) => new SalatDAO[File, ObjectId](collection = x.collection("uploads.files")) {}
  }
}

object VersusDAO extends ModelCompanion[Versus,ObjectId]{
    val dao = current.plugin[MongoSalatPlugin] match {
    case None => throw new RuntimeException("No MongoSalatPlugin");
    case Some(x) => new SalatDAO[Versus, ObjectId](collection = x.collection("versus.descriptors")) {}
  }
}

